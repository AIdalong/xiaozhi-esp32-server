# 从 Docker Redis 消息队列读取 Perception PCM 数据

本文说明如何从 Docker 中的 Redis 队列读取 perception 实时音频 PCM 数据。

## 数据流说明

perception 音频进入 server 后会按以下流程处理：

1. MQTT/WebSocket 收到 Opus 音频包
2. server 实时将 Opus 解码为 PCM
3. PCM 文件保存到容器内目录：

```text
/tmp/perception_audio
```

4. Redis 队列写入一条消息，消息中包含 PCM 文件路径和元数据

Redis 队列名：

```text
perception_pcm_queue
```

## 查看队列长度

在宿主机执行：

```bash
docker exec xiaozhi-esp32-server-redis redis-cli LLEN perception_pcm_queue
```

返回值是当前队列中的 PCM 消息数量，例如：

```text
852
```

## 查看队列中的最新消息

查看最后 3 条消息：

```bash
docker exec xiaozhi-esp32-server-redis redis-cli LRANGE perception_pcm_queue -3 -1
```

查看最早的 1 条消息：

```bash
docker exec xiaozhi-esp32-server-redis redis-cli LINDEX perception_pcm_queue 0
```

查看最新的 1 条消息：

```bash
docker exec xiaozhi-esp32-server-redis redis-cli LINDEX perception_pcm_queue -1
```

## 消息格式

队列中的每条消息是 JSON 字符串，格式如下：

```json
{
  "file_path": "/tmp/perception_audio/perception_d0:cf:13:21:de:cc_1779425718939_1920b.pcm",
  "device_id": "d0:cf:13:21:de:cc",
  "timestamp": 1779425718940,
  "metadata": {
    "opus_packets": 1,
    "opus_size": 107,
    "pcm_frames": 1,
    "pcm_size": 1920,
    "session_id": "6ff6c21f-a8b2-4f97-b5cf-98732d022faf",
    "packet_index": 854,
    "realtime": true
  }
}
```

字段说明：

| 字段 | 说明 |
|------|------|
| `file_path` | PCM 文件在 server 容器内的路径 |
| `device_id` | 设备 ID |
| `timestamp` | 入队时间戳，毫秒 |
| `metadata.opus_packets` | 本条 PCM 对应的 Opus 包数量，实时模式通常为 `1` |
| `metadata.opus_size` | 原始 Opus 包大小，字节 |
| `metadata.pcm_frames` | 解码出的 PCM 帧数量 |
| `metadata.pcm_size` | PCM 数据大小，字节 |
| `metadata.session_id` | 当前连接会话 ID |
| `metadata.packet_index` | perception 音频包序号 |
| `metadata.realtime` | 是否实时入队 |

## 消费队列消息

Redis 使用 list 存储队列消息。server 端使用 `RPUSH` 写入，所以消费者推荐使用 `BLPOP` 从左侧阻塞读取，保持先进先出。

一次性读取一条：

```bash
docker exec xiaozhi-esp32-server-redis redis-cli LPOP perception_pcm_queue
```

阻塞等待读取：

```bash
docker exec xiaozhi-esp32-server-redis redis-cli BLPOP perception_pcm_queue 0
```

`BLPOP` 的最后一个参数是超时时间，单位秒：

- `0`：一直等待
- `5`：最多等待 5 秒

## 在宿主机读取 PCM 文件

队列消息里的 `file_path` 是 server 容器内路径，例如：

```text
/tmp/perception_audio/perception_d0:cf:13:21:de:cc_1779425718939_1920b.pcm
```

如果需要从宿主机读取文件，可以用 `docker cp`：

```bash
docker cp xiaozhi-esp32-server:/tmp/perception_audio/perception_d0:cf:13:21:de:cc_1779425718939_1920b.pcm ./sample.pcm
```

也可以直接在容器内查看文件：

```bash
docker exec xiaozhi-esp32-server ls -lh /tmp/perception_audio
```

## Python 消费示例

以下示例从 Docker Redis 容器对应的 Redis 服务读取队列消息，并从 server 容器中复制 PCM 文件到宿主机当前目录。

```python
import json
import subprocess
import redis

REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379
QUEUE_KEY = "perception_pcm_queue"
SERVER_CONTAINER = "xiaozhi-esp32-server"

r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=0, decode_responses=True)

while True:
    item = r.blpop(QUEUE_KEY, timeout=0)
    if not item:
        continue

    _, message = item
    payload = json.loads(message)

    file_path = payload["file_path"]
    device_id = payload.get("device_id", "unknown")
    timestamp = payload.get("timestamp")
    pcm_size = payload.get("metadata", {}).get("pcm_size")

    local_file = f"./{device_id}_{timestamp}.pcm".replace(":", "-")

    subprocess.run(
        ["docker", "cp", f"{SERVER_CONTAINER}:{file_path}", local_file],
        check=True,
    )

    print(f"saved {local_file}, pcm_size={pcm_size}")
```

如果 Python 程序运行在与 Redis 同一个 Docker 网络内，可以把 `REDIS_HOST` 改为：

```python
REDIS_HOST = "xiaozhi-esp32-server-redis"
```

## PCM 音频参数

当前 perception PCM 数据来自 16kHz、单声道 Opus 解码，单包通常对应：

```text
sample_rate: 16000
channels: 1
sample_width: 16-bit signed little-endian
frame_duration: 60ms
pcm_size: 1920 bytes
```

换算关系：

```text
16000 samples/s × 0.06s × 2 bytes = 1920 bytes
```

## 播放 PCM 文件

可以用 `ffplay` 播放：

```bash
ffplay -f s16le -ar 16000 -ac 1 sample.pcm
```

也可以转换为 WAV：

```bash
ffmpeg -f s16le -ar 16000 -ac 1 -i sample.pcm sample.wav
```

## 注意事项

1. 当前队列没有最大长度限制，会持续增长，直到消费者消费、手动清理或 Redis 达到内存限制。
2. 队列消息只保存 PCM 文件路径，不直接保存 PCM 二进制内容。
3. 如果 server 容器重启或 `/tmp/perception_audio` 被清理，Redis 中旧消息指向的 PCM 文件可能不存在。
4. 如果需要只保留最近 N 条消息，可以在 producer 侧 `RPUSH` 后增加 `LTRIM perception_pcm_queue -N -1`。

## 清理队列

清空 perception PCM 队列：

```bash
docker exec xiaozhi-esp32-server-redis redis-cli DEL perception_pcm_queue
```

清理 server 容器内 PCM 文件：

```bash
docker exec xiaozhi-esp32-server rm -f /tmp/perception_audio/*.pcm
```

清理操作会删除未消费数据，请谨慎执行。
