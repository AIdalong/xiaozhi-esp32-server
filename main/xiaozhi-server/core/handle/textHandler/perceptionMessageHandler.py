"""
处理固件发来的 perception 控制消息
"""
from typing import Dict, Any

from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType

TAG = __name__


class PerceptionMessageHandler(TextMessageHandler):
    """Perception控制消息处理器"""

    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.PERCEPTION

    async def handle(self, conn, msg_json: Dict[str, Any]) -> None:
        """
        处理 perception 控制消息
        消息格式：{"type": "perception", "state": "start|stop", "reason": "..."}

        Args:
            conn: WebSocket连接对象
            msg_json: 消息的JSON数据
        """
        state = msg_json.get("state")
        reason = msg_json.get("reason", "")

        if state == "start":
            conn.logger.bind(tag=TAG).info(f"感知模式启动, 原因: {reason}")
            conn.stream_role = "perception"  # 切换到 perception 模式
            conn.perception_audio_buffer = []  # 清空 buffer
            if not hasattr(conn, "perception_packet_count"):
                conn.perception_packet_count = 0
            # 更新活动时间
            import time
            conn.last_activity_time = time.time() * 1000

        elif state == "stop":
            conn.logger.bind(tag=TAG).info(f"感知模式停止, 原因: {reason}")
            # 如果 buffer 还有数据，触发一次完成处理
            if conn.perception_audio_buffer:
                from core.handle import perceptionAudioHandle
                await perceptionAudioHandle.on_perception_segment_complete(conn)
            # 切换回 dialog 模式
            conn.stream_role = "dialog"
            # 更新活动时间，防止超时断开
            import time
            conn.last_activity_time = time.time() * 1000
