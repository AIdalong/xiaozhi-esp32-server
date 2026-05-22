# 音频感知服务改造方案（精简版）

## 一、方案概述

基于当前对话式服务，新增对 `perception` 模式音频流的支持。

### 核心职责
服务端只需做两件事：
1. **区分并接收** perception 音频流 vs dialog 音频流
2. **处理** perception 音频片段

### 非职责范围
- ❌ SAD（语音活动检测）- 由固件处理
- ❌ Agent 与 agent_cmd 指令下发

---

## 二、UDP音频流区分方案（采用方案B）

### 现有UDP音频包格式

当前通过MQTT网关传输的UDP音频包包含16字节头部：

```
┌─────────────────────────────────────────────────────────────┐
│  0  │  1  │  2-3  │  4-7   │   8-11   │   12-15   │  payload │
│ type│ RSV │ length│sequence│ timestamp│opus_length│  opus... │
└─────────────────────────────────────────────────────────────┘
```

字节说明：
- `[0]`: type (当前固定为 1)
- `[1]`: 保留
- `[2-3]`: payload length (2字节, big-endian)
- `[4-7]`: sequence (4字节, big-endian)
- `[8-11]`: timestamp (4字节, big-endian)
- `[12-15]`: opus_length (4字节, big-endian)
- `[16..]`: Opus音频数据

---

### 实施方案B：WebSocket 连接级别区分

**前提**：perception 和 dialog 使用独立的 WebSocket 连接

**设计逻辑**：
- 每个 WebSocket 连接在 hello 时声明 `stream_role`
- 该连接后续的所有音频（无论是WebSocket直传还是通过UDP网关）都属于这个 role
- **无需修改UDP包格式**！

**服务端实现**：
```python
# 1. hello 消息时设置 conn.stream_role
# 2. handleAudioMessage() 中按 conn.stream_role 分发
# 3. _process_mqtt_audio_message() 无需改动！
```

---

---

## 三、部署说明

**无需额外部署新服务！**

perception 处理能力直接集成在现有 `xiaozhi-server` 中，部署方式保持不变。

### 部署方式

#### 方式1：标准部署（使用预构建镜像）
与原系统完全一致：

| 部署方式 | 说明 |
|---------|------|
| **Docker 部署** | `docker compose up -d` |
| **源码部署** | `python app.py` |

#### 方式2：开发部署（挂载本地代码）
如果你需要修改perception模式相关代码并快速测试，可以使用开发模式的docker-compose.yml配置，挂载本地代码到容器中：

```yaml
services:
  xiaozhi-esp32-server:
    image: ghcr.nju.edu.cn/xinnan-tech/xiaozhi-esp32-server:server_latest
    container_name: xiaozhi-esp32-server
    restart: always
    ports:
      - "8000:8000"
      - "8003:8003"
    volumes:
      - ./data:/opt/xiaozhi-esp32-server/data
      # 挂载配置目录
      - ./config:/opt/xiaozhi-esp32-server/config
      # 挂载 perception 模式修改的代码文件
      - ./core/connection.py:/opt/xiaozhi-esp32-server/core/connection.py
      - ./core/handle/helloHandle.py:/opt/xiaozhi-esp32-server/core/handle/helloHandle.py
      - ./core/handle/receiveAudioHandle.py:/opt/xiaozhi-esp32-server/core/handle/receiveAudioHandle.py
      - ./core/handle/perceptionAudioHandle.py:/opt/xiaozhi-esp32-server/core/handle/perceptionAudioHandle.py
      - ./core/handle/textHandler/listenMessageHandler.py:/opt/xiaozhi-esp32-server/core/handle/textHandler/listenMessageHandler.py
      - ./core/handle/sendAudioHandle.py:/opt/xiaozhi-esp32-server/core/handle/sendAudioHandle.py
      - ./core/handle/textHandler/perceptionMessageHandler.py:/opt/xiaozhi-esp32-server/core/handle/textHandler/perceptionMessageHandler.py
      - ./core/providers/asr/base.py:/opt/xiaozhi-esp32-server/core/providers/asr/base.py
      - ./core/handle/textMessageHandlerRegistry.py:/opt/xiaozhi-esp32-server/core/handle/textMessageHandlerRegistry.py
      - ./core/handle/textMessageType.py:/opt/xiaozhi-esp32-server/core/handle/textMessageType.py
```

**⚠️ 关键注意事项：**
- **不要单独挂载 `model.pt` 文件！** 如果本地 `models/SenseVoiceSmall/model.pt` 是个空目录，会覆盖镜像里的模型文件，导致服务启动失败！
- 如果需要挂载本地模型，请挂载整个目录：`./models/SenseVoiceSmall:/opt/xiaozhi-esp32-server/models/SenseVoiceSmall`
- 或者直接注释掉模型挂载这一行，使用镜像里自带的模型！

### 配置变更

**无需新增配置项**，沿用现有配置即可。

如需配置 perception 相关的扩展功能（如音频保存、特殊LLM调用等），可在后续迭代中新增配置。

---

## 四、运行验证

1. 启动 xiaozhi-server（方式不变）
2. 固件建立 perception 连接，发送 hello 消息带 `stream_role: "perception"`
3. 查看服务端日志，确认识别到 `stream_role: perception`
4. 发送音频数据，确认进入 perception 处理分支

---

**文档版本**: v1.3
**更新日期**: 2026-04-22

---

## 二、协议变更

### 2.1 hello 消息新增 stream_role 字段

固件 → 服务端：
```json
{
  "type": "hello",
  "version": "2.0",
  "audio_params": {...},
  "features": {...},
  "stream_role": "dialog" | "perception"  // 新增字段
}
```

### 2.2 perception 控制消息（可选）

固件 → 服务端：
```json
{
  "type": "perception",
  "state": "start" | "stop",
  "reason": "equipment_attached" | "equipment_detached" | "network_lost" | ...
}
```

### 2.3 二进制音频数据

- `stream_role=dialog` → 走原有对话音频处理逻辑
- `stream_role=perception` → 走感知音频处理逻辑

---

## 三、文件变更清单

| 操作 | 文件路径 | 说明 |
|------|----------|------|
| 新增 | `core/handle/perceptionAudioHandle.py` | 感知音频处理模块 |
| 新增 | `core/handle/textHandler/perceptionMessageHandler.py` | 感知控制消息处理器 |
| 修改 | `core/connection.py` | 新增 stream_role 状态与音频缓冲，修复MQTT消息处理顺序 |
| 修改 | `core/handle/helloHandle.py` | 解析 stream_role 字段，添加日志 |
| 修改 | `core/handle/receiveAudioHandle.py` | 按流角色分发音频 |
| 修改 | `core/handle/sendAudioHandle.py` | perception模式下不发送tts/stt消息 |
| 修改 | `core/handle/textHandler/listenMessageHandler.py` | perception模式下不处理listen消息 |
| 修改 | `core/providers/asr/base.py` | perception模式下忽略handle_voice_stop |
| 修改 | `core/handle/textMessageHandlerRegistry.py` | 注册 perception 控制消息处理器 |
| 修改 | `core/handle/textMessageType.py` | 新增 PERCEPTION 消息类型枚举 |
| 修改 | `docker-compose.yml` | 新增开发模式代码挂载配置 |

---

## 四、详细实现设计

### 4.1 修改 `core/connection.py`

新增状态管理：

```python
class ConnectionHandler:
    def __init__(self, ...):
        ...
        # 新增感知模式相关
        self.stream_role: str = "dialog"  # "dialog" | "perception"
        self.perception_audio_buffer: list = []  # 暂存perception音频片段
```

---

### 4.2 修改 `core/handle/helloHandle.py`

解析 `stream_role` 字段：

```python
async def handleHelloMessage(conn: "ConnectionHandler", msg_json):
    ...
    # 新增：解析 stream_role
    stream_role = msg_json.get("stream_role", "dialog")
    conn.stream_role = stream_role
    conn.logger.bind(tag=TAG).info(f"流模式: {stream_role}")
    ...
```

---

### 4.3 新增 `core/handle/perceptionAudioHandle.py`

感知音频处理核心模块：

```python
"""
处理 perception 模式的音频片段
核心逻辑：
- 累积音频片段
- 音频结束时调用 ASR 转文字
- 触发后续业务处理（可配置）
"""
import asyncio
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__


async def handle_perception_audio(conn: "ConnectionHandler", audio_data: bytes):
    """处理单块 perception 音频"""
    # 1. 累积到 buffer
    conn.perception_audio_buffer.append(audio_data)
    
    # 2. 这里可以根据需求处理：
    # 选项A：实时流式 ASR（边收边转）
    # 选项B：等固件发结束信号后再批量处理
    # 选项C：到一定大小就处理
    
    # 示例：直接用现有 ASR 处理
    if hasattr(conn, 'asr') and conn.asr:
        # 复用 connection 已有的 ASR 实例
        await conn.asr.receive_audio(conn, audio_data, have_voice=True)


async def on_perception_segment_complete(conn: "ConnectionHandler"):
    """感知音频片段结束时的回调"""
    # 合并 buffer 中的音频
    full_audio = b''.join(conn.perception_audio_buffer)
    
    # 清空 buffer
    conn.perception_audio_buffer.clear()
    
    # 后续处理：
    # - 可以调用 ASR 批量转文字
    # - 可以保存到文件
    # - 可以发送给其他服务
    conn.logger.bind(tag=TAG).info(f"感知片段完成，大小: {len(full_audio)} 字节")
    
    # 可选：触发后续业务处理钩子
    # await process_perception_result(conn, text)


async def process_perception_result(conn: "ConnectionHandler", text: str):
    """
    感知音频转文字后的处理钩子
    可根据需求实现：
    - 调用 LLM 分析
    - 记录日志
    - 触发其他动作
    """
    conn.logger.bind(tag=TAG).info(f"感知结果: {text}")
```
---

### 4.4 新增 `core/handle/perceptionControlHandle.py`

处理固件发来的 perception 控制消息：

```python
"""
处理固件发来的 perception 控制消息
"""
import json
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__


async def handle_perception_control(conn: "ConnectionHandler", msg_json: dict):
    """处理 perception 控制消息"""
    state = msg_json.get("state")
    reason = msg_json.get("reason", "")
    
    if state == "start":
        conn.logger.bind(tag=TAG).info(f"感知模式启动，原因: {reason}")
        conn.perception_audio_buffer = []  # 清空 buffer
        
    elif state == "stop":
        conn.logger.bind(tag=TAG).info(f"感知模式停止，原因: {reason}")
        # 如果 buffer 还有数据，可以触发一次完成处理
        if conn.perception_audio_buffer:
            from core.handle import perceptionAudioHandle
            await perceptionAudioHandle.on_perception_segment_complete(conn)
```

---

### 4.5 修改 `core/handle/receiveAudioHandle.py`

按流角色分发音频处理：

```python
async def handleAudioMessage(conn: "ConnectionHandler", audio):
    if conn.is_exiting:
        return
    
    # 新增：按 stream_role 分流
    if hasattr(conn, 'stream_role') and conn.stream_role == 'perception':
        # perception 模式：走感知处理分支
        from core.handle import perceptionAudioHandle
        await perceptionAudioHandle.handle_perception_audio(conn, audio)
        return
    
    # 原有 dialog 模式逻辑保持不变
    ...
```

---

### 4.6 修改 `core/handle/textMessageHandlerRegistry.py`

注册 perception 控制消息处理器：

```python
from core.handle import perceptionControlHandle

_handlers = {
    ...,
    "perception": perceptionControlHandle.handle_perception_control,
}
```

---

## 五、处理流程图

```
┌─────────────────────────────────────────────────────────┐
│                    新连接建立                            │
└─────────────────────┬───────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────┐
│              收到 hello 消息                             │
│         解析 stream_role 字段                           │
│  ┌─────────────────────┐    ┌───────────────────────┐  │
│  │ stream_role=dialog  │    │ stream_role=perception│  │
│  └──────────┬──────────┘    └──────────┬────────────┘  │
└─────────────┼──────────────────────────┼───────────────┘
              │                          │
              ▼                          ▼
    ┌──────────────────┐     ┌──────────────────────┐
    │  原有对话处理    │     │  感知音频处理        │
    │  (保持不变)      │     │  perceptionAudioHandle│
    └──────────────────┘     └─────────┬────────────┘
                                       │
                                       ▼
                          ┌──────────────────────┐
                          │ 累积到 buffer        │
                          │ 可选：调用 ASR       │
                          └──────────────────────┘
                                       │
                                       ▼
                          ┌──────────────────────┐
                          │ 感知控制消息 (可选)   │
                          │ state=stop 时收尾    │
                          └──────────────────────┘
```

---

## 六、扩展点

在 `perceptionAudioHandle.py` 中的 `process_perception_result()` 函数预留了后续业务处理钩子，可根据需求扩展：

- 调用 LLM 分析环境音频内容
- 保存音频片段到文件/对象存储
- 发送事件到其他业务服务
- 记录审计日志

---

## 七、配置项（可选）

可在 `config/settings.py` 中新增感知模式配置（如需要）：

```python
PERCEPTION_CONFIG = {
    "enabled": True,
    "buffer_max_size": 10 * 1024 * 1024,  # 最大缓冲 10MB
    "enable_asr": True,  # 是否对感知音频做 ASR
    "save_to_file": False,  # 是否保存音频到文件
    "output_dir": "/tmp/perception_audio",
}
```

---

## 八、音频文件存储说明

### 8.1 文件保存路径

**容器内路径**：`/tmp/perception_audio/`

### 8.2 文件命名格式

```
perception_{device_id}_{timestamp_ms}_{size}b.opus
```

示例：
- `perception_unknown_1713857000000_4080b.opus`
- `perception_my_device_1713857123456_8192b.opus`

字段说明：
- `device_id`：设备 ID（从连接 header 获取，未知则为 `unknown`）
- `timestamp_ms`：文件保存时的 Unix 时间戳（毫秒）
- `size`：音频数据总大小（字节）

### 8.3 常用查看命令

#### 查看容器内文件列表
```bash
docker exec xiaozhi-esp32-server ls -la /tmp/perception_audio/
```

#### 查看文件详细信息（大小、时间等）
```bash
docker exec xiaozhi-esp32-server ls -lh /tmp/perception_audio/
```

#### 复制文件到宿主机当前目录
```bash
# 单个文件
docker cp xiaozhi-esp32-server:/tmp/perception_audio/文件名.opus ./

# 整个目录
docker cp xiaozhi-esp32-server:/tmp/perception_audio/ ./
```

#### 实时查看服务端日志（观察接收状态）
```bash
docker logs -f xiaozhi-esp32-server --tail 50 | grep -i perception
```

### 8.4 当前实现说明

- **缓存策略**：音频数据先缓存在内存 `conn.perception_audio_buffer`
- **保存时机**：收到 `perception state: "stop"` 消息时才写入文件
- **文件格式**：原始 Opus 编码音频（可直接用播放器打开）

---

**文档版本**: v1.1  
**更新日期**: 2026-04-23
