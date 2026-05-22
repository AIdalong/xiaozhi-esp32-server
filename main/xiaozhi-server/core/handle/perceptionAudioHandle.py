"""
处理 perception 模式的音频片段
"""
import asyncio
import os
import time
import hashlib
from typing import TYPE_CHECKING, List
import opuslib_next
import redis
from config.logger import setup_logging

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()

# perception 音频保存目录
PERCEPTION_OUTPUT_DIR = "/tmp/perception_audio"
os.makedirs(PERCEPTION_OUTPUT_DIR, exist_ok=True)

# Redis配置（与docker-compose中redis服务对应）
REDIS_HOST = "xiaozhi-esp32-server-redis"
REDIS_PORT = 6379
REDIS_DB = 0
PERCEPTION_QUEUE_KEY = "perception_pcm_queue"


class PerceptionAudioManager:
    """感知音频管理器"""

    _instance = None
    _redis_client = None

    @classmethod
    def get_instance(cls):
        """获取单例"""
        if cls._instance is None:
            cls._instance = PerceptionAudioManager()
        return cls._instance

    def __init__(self):
        self._init_redis()

    def _init_redis(self):
        """初始化Redis连接"""
        try:
            self._redis_client = redis.Redis(
                host=REDIS_HOST,
                port=REDIS_PORT,
                db=REDIS_DB,
                decode_responses=False,
                socket_connect_timeout=5
            )
            # 测试连接
            self._redis_client.ping()
            logger.bind(tag=TAG).info("Redis连接成功")
        except Exception as e:
            logger.bind(tag=TAG).warning(f"Redis连接失败，将尝试重新连接: {e}")
            self._redis_client = None

    def get_redis_client(self):
        """获取Redis客户端，失败时重试"""
        if self._redis_client is None:
            self._init_redis()

        if self._redis_client is not None:
            try:
                self._redis_client.ping()
            except Exception as e:
                logger.bind(tag=TAG).warning(f"Redis连接断开，重试连接: {e}")
                self._init_redis()

        return self._redis_client

    @staticmethod
    def decode_opus(opus_data: List[bytes]) -> List[bytes]:
        """
        将Opus音频数据解码为PCM数据（与dialog模式ASR解码完全一致）

        Args:
            opus_data: Opus数据包列表

        Returns:
            PCM帧列表，每个元素是一个PCM帧(bytes)
        """
        try:
            decoder = opuslib_next.Decoder(16000, 1)
            pcm_frames = []
            buffer_size = 960  # 每次处理960个采样点 (60ms at 16kHz)

            for i, opus_packet in enumerate(opus_data):
                try:
                    if not opus_packet or len(opus_packet) == 0:
                        continue

                    pcm_frame = decoder.decode(opus_packet, buffer_size)
                    if pcm_frame and len(pcm_frame) > 0:
                        pcm_frames.append(pcm_frame)

                except opuslib_next.OpusError as e:
                    logger.bind(tag=TAG).warning(f"Opus解码错误，跳过数据包 {i}: {e}")
                except Exception as e:
                    logger.bind(tag=TAG).error(f"音频处理错误，数据包 {i}: {e}")

            return pcm_frames

        except Exception as e:
            logger.bind(tag=TAG).error(f"音频解码过程发生错误: {e}")
            return []

    def save_pcm_to_file(self, pcm_data: bytes, device_id: str) -> str:
        """
        保存PCM数据到文件

        Args:
            pcm_data: PCM字节数据
            device_id: 设备ID

        Returns:
            保存的文件路径
        """
        if not pcm_data:
            logger.bind(tag=TAG).warning("PCM数据为空，跳过保存")
            return ""

        timestamp = int(time.time() * 1000)
        filename = f"perception_{device_id or 'unknown'}_{timestamp}_{len(pcm_data)}b.pcm"
        file_path = os.path.join(PERCEPTION_OUTPUT_DIR, filename)

        try:
            with open(file_path, "wb") as f:
                f.write(pcm_data)
            logger.bind(tag=TAG).info(f"PCM文件已保存: {file_path}")
            return file_path
        except Exception as e:
            logger.bind(tag=TAG).error(f"保存PCM文件失败: {e}")
            return ""

    def push_to_queue(self, file_path: str, device_id: str, metadata: dict = None):
        """
        将PCM文件路径推送到Redis队列

        Args:
            file_path: PCM文件路径
            device_id: 设备ID
            metadata: 附加元数据

        Returns:
            是否推送成功
        """
        if not file_path:
            return False

        redis_client = self.get_redis_client()
        if redis_client is None:
            logger.bind(tag=TAG).error("Redis不可用，无法推送队列")
            return False

        import json
        message = {
            "file_path": file_path,
            "device_id": device_id,
            "timestamp": int(time.time() * 1000),
            "metadata": metadata or {}
        }

        try:
            # 使用LPUSH将消息推送到队列头部
            message_json = json.dumps(message, ensure_ascii=False)
            queue_size = redis_client.rpush(PERCEPTION_QUEUE_KEY, message_json)
            logger.bind(tag=TAG).info(
                f"已推送到Redis队列，key={PERCEPTION_QUEUE_KEY}, "
                f"当前队列大小={queue_size}, file={file_path}"
            )
            return True
        except Exception as e:
            logger.bind(tag=TAG).error(f"推送到Redis队列失败: {e}")
            return False


async def handle_perception_audio(conn: "ConnectionHandler", audio_data: bytes):
    """
    处理单块 perception 音频

    Args:
        conn: 连接对象
        audio_data: 音频数据
    """
    # 更新活动时间，防止超时断开
    conn.last_activity_time = time.time() * 1000

    # 初始化包序号
    if not hasattr(conn, "perception_packet_count"):
        conn.perception_packet_count = 0
    conn.perception_packet_count += 1

    # 计算数据哈希验证完整性
    md5 = hashlib.md5(audio_data).hexdigest()[:8]

    conn.logger.bind(tag=TAG).debug(
        f"[Perception] 收到包 #{conn.perception_packet_count}, "
        f"大小: {len(audio_data)} 字节, "
        f"MD5: {md5}"
    )

    manager = PerceptionAudioManager.get_instance()
    pcm_frames = manager.decode_opus([audio_data])
    combined_pcm_data = b"".join(pcm_frames)

    if combined_pcm_data:
        conn.logger.bind(tag=TAG).info(
            f"[Perception] 实时Opus解码完成, 包序号: {conn.perception_packet_count}, "
            f"Opus大小: {len(audio_data)} 字节, PCM大小: {len(combined_pcm_data)} 字节"
        )

        device_id = conn.device_id or "unknown"
        file_path = manager.save_pcm_to_file(combined_pcm_data, device_id)

        if file_path:
            metadata = {
                "opus_packets": 1,
                "opus_size": len(audio_data),
                "pcm_frames": len(pcm_frames),
                "pcm_size": len(combined_pcm_data),
                "session_id": conn.session_id,
                "packet_index": conn.perception_packet_count,
                "realtime": True
            }
            manager.push_to_queue(file_path, device_id, metadata)
    else:
        conn.logger.bind(tag=TAG).warning(
            f"[Perception] 实时PCM解码结果为空, 包序号: {conn.perception_packet_count}, Opus大小: {len(audio_data)} 字节"
        )


async def on_perception_segment_complete(conn: "ConnectionHandler"):
    """
    感知音频片段完成时的回调

    Args:
        conn: 连接对象
    """
    # 获取管理器实例
    manager = PerceptionAudioManager.get_instance()

    # 合并 buffer 中的音频
    opus_data = conn.perception_audio_buffer.copy()

    # 清空 buffer
    conn.perception_audio_buffer.clear()

    # 计算整个片段的哈希
    full_opus = b"".join(opus_data)
    total_md5 = hashlib.md5(full_opus).hexdigest()[:16] if full_opus else ""

    conn.logger.bind(tag=TAG).info(
        f"[Perception] 片段完成! "
        f"总包数: {conn.perception_packet_count if hasattr(conn, 'perception_packet_count') else 0}, "
        f"Opus总大小: {len(full_opus)} 字节, "
        f"总MD5: {total_md5}"
    )

    # 重置包序号
    if hasattr(conn, "perception_packet_count"):
        conn.perception_packet_count = 0

    # 1. 将Opus解码为PCM（与dialog模式流程完全一致）
    if opus_data and len(opus_data) > 0:
        pcm_frames = manager.decode_opus(opus_data)
        combined_pcm_data = b"".join(pcm_frames)

        if combined_pcm_data:
            conn.logger.bind(tag=TAG).info(
                f"[Perception] Opus解码完成, 帧数: {len(pcm_frames)}, "
                f"PCM总大小: {len(combined_pcm_data)} 字节"
            )

            # 2. 保存PCM到文件
            device_id = conn.device_id or "unknown"
            file_path = manager.save_pcm_to_file(combined_pcm_data, device_id)

            if file_path:
                # 3. 推送到Redis队列
                metadata = {
                    "opus_packets": len(opus_data),
                    "opus_size": len(full_opus),
                    "pcm_frames": len(pcm_frames),
                    "pcm_size": len(combined_pcm_data),
                    "session_id": conn.session_id
                }
                manager.push_to_queue(file_path, device_id, metadata)
        else:
            conn.logger.bind(tag=TAG).warning("[Perception] PCM解码结果为空")
    else:
        conn.logger.bind(tag=TAG).warning("[Perception] 没有音频数据需要处理")
