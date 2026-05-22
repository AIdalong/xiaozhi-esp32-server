# Lukka 感知模式、后端协同与 VAD/声音分类 — 需求与技术方案（持续更新）

> **文档用途**：汇总「底座 + 联网」感知模式、后端融合 MQTT/音频与 Agent 下发、语音唤醒与流式对话回退，以及 **VAD/声音事件** 的扩展目标。后续讨论的需求变更、技术结论请在本文件中追加或修订，并保留简短变更记录。

---

## 变更记录

| 日期 | 摘要 |
|------|------|
| 2026-04-21 | 初稿：整合感知/对话/后端方案与 VAD 扩展（人声 + 人体非语音 + 车内环境声）。 |
| 2026-04-21 | 决策更新：equipment→感知启停；复用现有音频上行并用 `stream_role` 区分；补充 Agent 下行 schema 草案；明确断网/independent 立即停止上报；导航/播客分类交给后端，SAD 侧仅要求不漏检。 |
| 2026-04-21 | 进度更新：固件侧已落地 perception/dialog 分 session、perception 控制消息（方案 B）、Idle 自动启停、SAD(60ms)+200ms pre-roll 仅段内上报、`agent_cmd` 解析骨架；待编译/烧录联调与宏映射表补全。 |

---

## 0. 开发计划与进度（持续更新）

### 0.1 当前状态（截至 2026-04-21）

- **已完成（固件侧）**
  - **协议层**：`OpenAudioChannel(AudioSessionKind)` 支持 `dialog/perception`；`hello` 携带 `stream_role`。
  - **perception 控制消息（方案 B）**：新增 `type:"perception", state:"start|stop"` 发送接口。
  - **session 生命周期**：equipment + Wi‑Fi 在线 → Idle 自动开启 perception session；唤醒词触发前关闭 perception；`dialog` 结束后按条件恢复 perception。
  - **SAD gating**：60ms 帧、200ms pre-roll；仅在 segment 内编码/上报；停止 perception 时清理 SAD 状态。
  - **Agent 下行骨架**：支持 `type:"agent_cmd"`，解析 `emoji_play(aaf_id)` / `sound_play(sound_id)` / `head_turn(steps)` / `tts_speak(text)`（宏 ID + steps）。
  - **Lukka 接入**：`PlacementState` 变化回调已接入 `Application::SetEquipmentPresent()`。

- **未验证 / 待联调**
  - **构建验证**：本次修改未在本机执行 `idf.py build`（需要你本地构建/烧录跑通）。
  - **服务端联调**：确认 perception session 的 `start/stop` 行为与路由（`stream_role`）是否按预期实现。
  - **宏 ID 映射表**：目前 `aaf_id/sound_id` 只内置了少量常用项，需要按你们实际下发清单补全。

### 0.2 里程碑与任务清单

- **M1：固件编译/烧录通过（你本地）**
  - 在目标板配置下完成构建与烧录，确认无崩溃、可连网。

- **M1.5：本地检测融合降本（FFT 降频 + 特征复用）**
  - 将 SAD / LOUD_SOUND / music detection 的 **基础特征提取**（RMS/peak/ZCR 等）合并为“一次计算，多处复用”。
  - music 的 FFT 改为 **降频 + 门控**（perception 下更保守）以降低资源消耗。

- **M2：perception session 联调**
  - equipment 后（非 independent）且 Wi‑Fi 在线：自动开启 perception session，并向服务端发送 `perception start`。
  - independent 或断网：立即发送 `perception stop` 并停止上报。
  - 唤醒词：切换到 dialog session（关闭 perception session），对话结束后满足条件再恢复 perception session。

- **M3：SAD 仅段内上报验证**
  - 静音/无活动：确认不编码不上行。
  - 导航播报/播客/音乐/喇叭/车门：确认能触发 segment 上报（召回优先），并带 200ms pre-roll。

- **M4：Agent 指令联调**
  - 服务端下发 `agent_cmd`：表情、音效、转头、说话分别可触发执行。
  - 补全 `aaf_id/sound_id` 宏映射表（按你们实际会下发的 ID 列表）。

### 0.3 关键待办（你提供/我们一起补）

- **宏映射清单**：你们计划下发的 `aaf_id` 与 `sound_id` 列表（宏名），我来补全固件侧映射表。
- **服务端协议确认**：服务端收到 `perception start/stop` 后，是否需要回 ACK/状态，及音频上行的归属与存储策略。
- **融合版参数（已决策）**：FFT 降频可接受；门控阈值偏保守（见 §4.3.3）。

## 1. 背景与目标

### 1.1 感知模式（环境感知）

当 Lukka **在方案底座上**且 **已联网** 时：

- 打开麦克风进行音频采集；
- 音频经 **VAD / 声音活动与分类** 处理后，将「有效信息」上报后端；
- 后端调用大模型等能力分析音频（及上下文），再下发交互指令。

**设备侧现状（代码结构要点）**：

- 采集与 AFE：`Application::AudioLoop` / `OnAudioInput`，`audio_processor_`（AFE）输出经 Opus 编码进入发送队列。
- **当前上行限制**：`MqttProtocol::SendAudio` 依赖 UDP 会话（`OpenAudioChannel` 之后）；Idle 下持续编码但无稳定「感知专用」上行路径，需在实现阶段新增 **感知通道或会话角色**，避免无效入队与丢包。
- 底座状态：`BaseController::PlacementState`（`kPlacementRotatingBase` / `kPlacementStaticBase` / `kPlacementIndependent`）；产品需明确「方案底座」对应哪一种或组合。
- 联网：MQTT 会话 + revent / device_state 遥测 topic（见 `docs/lukka_revent_mqtt_protocol_v1.md` 等）。

### 1.1.1 「方案底座」与 `PlacementState` 的映射（已决策）

本项目中「方案底座」在需求讨论里按 **equipment** 事件口径定义：**发生 equipment（设备被安装到方案底座）时进入感知模式**。

实现落点（与现有代码结构兼容的解释）：

- **进入 Perception 的条件**：
  - `PlacementState != kPlacementIndependent`（即 *equipment* 发生后进入 `kPlacementRotatingBase` 或 `kPlacementStaticBase` 之一）
  - 且 **联网可用**（Wi‑Fi 已连 + MQTT 已连，详见第 3 节的链路复用方案）
- **退出 Perception 的条件**：
  - `PlacementState == kPlacementIndependent`（un-equipment / 拆离）
  - 或 **断网**（Wi‑Fi 或 MQTT 断开）
  - 退出后 **立即停止音频上报**（不做缓存、不做补发）

> 注：如果后续要更精确区分“某一种底座才感知”，可在此处把 equipment 映射从「非 independent」细化为「只对 `kPlacementRotatingBase` 生效」等，但当前按“equipment 即开启”处理。

### 1.2 后端协同

后端综合分析：

- 实时或准实时上报的 **音频流/分段**；
- **MQTT 事件**（revent、device_state 等）；

调用 **Agent** 生成 **交互指令**，并下发终端执行，包括但不限于：

- 播放什么表情；
- 是否转动 / 如何转动；
- 播放什么声音（本地提示或 TTS）；
- 是否说话、说什么。

**设备侧现状**：`OnIncomingJson` 已支持 `tts`、`llm`（含 `emotion`）等；转动与表情在板级有 `SetMotion`、`EmojiWidget` 等能力，需在协议层统一 **Agent 指令 schema**。

### 1.3 语音唤醒与流式对话

- **保留语音唤醒**（需联网）：唤醒词触发后，与后端协同打通 **音频流式传输**（现有 `OpenAudioChannel` + UDP Opus 链路），进入实时对话。
- **退出对话**：识别到 **结束语** 或 **超时** 后退出对话模式，**回到感知模式**（需显式 `SessionMode` 与资源清理，避免与感知上行冲突）。

---

## 2. 会话与状态机（建议）

建议使用与 `DeviceState`（Idle / Listening / Speaking …）**正交**的 **`SessionMode`**：

| 模式 | 含义 |
|------|------|
| `Perception` | 底座 + 在线：环境感知、分段/分类上报（见第 4 节） |
| `Dialog` | 唤醒后：对话通道、流式上下行 |
| （可选）`Degraded` | 仅唤醒、不上传感知等降级策略 |

**唤醒抢占**：进入 `Dialog` 时应暂停/清空感知缓冲，避免同一段音频双重语义。

**退出对话**：服务端 `goodbye` 或显式 `session: end_dialog` + 设备端兜底超时；`CloseAudioChannel` 后若仍满足底座+在线条件则回到 `Perception`。

---

## 3. 上行与下行（技术选项摘要）

### 3.1 感知音频上行（待选型）

| 方案 | 要点 |
|------|------|
| A. MQTT 专用 topic | 分段元数据 + Opus（或 Base64/分块）；实现快，需注意包大小与限流 |
| B. 独立 UDP/QUIC | 低延迟；运维与防火墙成本高 |
| C. 与对话共用通道 + `stream_role` | 省连接；hello/会话语义耦合度高 |

### 3.1.1 已决策：复用现有音频上行链路 + `stream_role` 区分

本次讨论决定：**感知音频上行复用当前「音频上行链路」（`OpenAudioChannel` 后的 UDP Opus 上行），通过字段区分对话流与感知流**。

核心要求：

- **区分方式**：所有音频包（或音频包所属的 JSON 控制帧）需携带 `stream_role`：
  - `dialog`：唤醒后的对话流（现有语义）
  - `perception`：感知模式上报流（新增语义）
- **会话关系（已决策）**：`perception` 与 `dialog` **不共用 session**。
  - 触发唤醒词后：结束（close）`perception` session → 打开 `dialog` session。
  - `dialog` session 结束后：若仍满足 equipment + 在线条件，则重新打开 `perception` session。
- **互斥策略**：只存在一个活跃 session；Dialog 期间不做 perception 上报。
- **断网/independent**：立即停止 `perception` 上报，不做缓存/补发。

协议承载位置（两种可选，后续实现时二选一即可）：

1. **控制面绑定**：在 `StartListening` / `StopListening` / `hello` / `goodbye` 的 JSON 中，增加 `stream_role` 或 `session_mode`，服务端以此路由后续 UDP 包。
2. **数据面绑定**：在 UDP 音频包头扩展 1 字节 role（需要改二进制格式，侵入更大）。

建议优先 **控制面绑定**：侵入小、兼容现有 UDP 加密格式。

#### 关于 `OpenAudioChannel()` 的职责拆分（实现约束）

现有代码中 `OpenAudioChannel()` 同时承担了“开始对话链路/创建 UDP 上行下行通道”等职责；而本次需求里 **perception 需要在 Idle 就能打开上行**，且提到“OpenAudioChannel 是打开麦克风采集还是打开扬声器”的歧义。

实现阶段建议将能力语义拆为两类（命名仅供参考）：

- **上行采集通道（Mic Upstream）**：用于 `perception` / `dialog` 的 Opus 上行（麦克风采集 + 编码 + 发送），与扬声器无关。
- **下行播放通道（Speaker Downstream）**：用于 TTS/音频下发播放，是否开启由 `Speaking` 状态与播放需求控制。

目标：**perception session 只需要 Mic Upstream**；而 dialog session 可能需要 Mic Upstream + Speaker Downstream。

### 3.2 Agent 下行（建议统一 JSON）

在现有 `tts` / `llm` 基础上扩展统一 **交互指令**（含 `cmd_id`、过期时间、优先级），映射表情、`motion`/电机、本地音效、TTS 文案等；冲突策略建议：**用户 Dialog > 感知触发的轻量反馈**。

### 3.2.1 Agent 下行 JSON schema（草案 v1）

目标：用一条指令覆盖你提出的字段：**表情 ID（宏定义）、播放音效 or 说话、音效 ID（宏定义）、说话内容、是否转动头部、转动方向与步进数**。

#### 顶层字段

- `type`: 固定 `"agent_cmd"`
- `schema_version`: number，固定 `1`
- `cmd_id`: string（幂等去重；服务端生成）
- `ts_ms`: number（服务端下发时间，Unix ms）
- `expires_in_ms`: number（过期时间，设备过期丢弃）
- `priority`: number（冲突仲裁，建议：Dialog > Perception）
- `context`：可选，服务端给设备的路由提示
  - `stream_role`: `"dialog"` 或 `"perception"`（本指令来源/适用场景）

#### `actions[]`（按序执行）

`actions` 为数组，设备按序执行；每个 action 互相独立，可组合出「先换表情再播音效/说话、再转头」等效果。

action 类型与字段：

1. `emoji_play`
   - `aaf_id`: string（例如 `"MMAP_OTA_0_SURPRISED_AAF"`；使用固件侧宏/枚举名，而非文件名）
   - `loop`: boolean（可选，默认 false）
   - `duration_ms`: number（可选；未填则按 aaf 默认/由设备策略）

2. `sound_play`
   - `sound_id`: string（例如 `"P3_POPUP"`；使用固件侧宏/枚举名，而非文件名）
   - `volume`: number（0–100，可选）

3. `tts_speak`
   - `text`: string（说话内容）
   - `voice_id`: string（可选；后端选声）
   - `interruptible`: boolean（可选；是否允许被唤醒/更高优先级打断）

4. `head_turn`
   - `enable`: boolean（true=需要转动）
   - `direction`: `"left" | "right" | "center"`
   - `steps`: number（步进数；与现有电机控制/指令保持一致）
   - `speed`: `"slow" | "normal" | "fast"`（可选）

> 说明：当前固件板端更接近固定动作/步进控制，而非角度闭环，因此该 schema 直接采用 `steps`。

#### 示例

```json
{
  "type": "agent_cmd",
  "schema_version": 1,
  "cmd_id": "7b0f0d6a-8b6d-4b47-9aa3-2c8c7c7c6c2a",
  "ts_ms": 1776750000000,
  "expires_in_ms": 3000,
  "priority": 50,
  "context": { "stream_role": "perception" },
  "actions": [
    { "type": "emoji_play", "aaf_id": "MMAP_OTA_0_SURPRISED_AAF", "duration_ms": 1500 },
    { "type": "sound_play", "sound_id": "P3_POPUP", "volume": 80 },
    { "type": "head_turn", "enable": true, "direction": "left", "steps": 48, "speed": "normal" }
  ]
}
```

兼容策略（与现有 `tts`/`llm` 并存）：

- 服务器可逐步灰度：先仅下发 `agent_cmd`；设备不支持时仍可用旧 `tts`/`llm`。
- 设备端收到 `agent_cmd`：优先按 `actions` 执行；若 action 不支持则跳过并继续后续 action（防止整体失败）。

---

## 4. VAD 与声音事件 — 扩展需求（本次补充）

### 4.1 目标

在 **现有 AFE/VAD 基础**上调整策略与管线，使系统不仅覆盖「**说话的人声**」，还希望覆盖并区分（至少能 **检测/上报**，最好能 **粗分类**）以下类别：

#### A. 人体发声（非普通对话语音）

- 咳嗽；
- 呼喊；
- 唱歌。

#### B. 车内/环境声音

- 开关车门；
- 鸣笛 / 喇叭；
- 播放音乐；
- 导航播报；
- 播客等媒体播放。

### 4.2 与「经典 VAD」的差异

传统 **语音 VAD** 多针对 **窄带对话**：平稳语音段起止，对 **短促非语音人体声**（咳嗽）、**高能量呼喊**、**音乐+歌唱**、**车门/喇叭瞬态** 等，仅 VAD 往往 **不够**：

- 可能出现 **漏检**（非典型语音谱）或 **误检**（音乐/导航像语音）；
- **起止边界** 对喇叭、关门等 **瞬态事件** 与对 **长时音乐** 完全不同。

因此建议将需求拆成两层（可分期）：

1. **层 1 — 广义「有声活动」检测（SAD / energy+hangover）**  
   判断「相对安静 vs 有明显声学活动」，作为 **是否切片上报** 的门槛（可与现有 VAD 并行或替代部分场景）。

2. **层 2 — 声音事件分类（Audio Event Classification）**  
   在层 1 触发的片段上，输出 **标签 + 置信度**（例如：`speech` / `shout` / `cough` / `sing` / `music` / `horn` / `door` / `nav_tts` / `podcast` / `unknown`）。  
   **导航播报 vs 播客 vs 普通人声** 在仅声学特征下易混淆；本次讨论决定：这类细分 **交给后端**（ASR/语义/上下文）完成，设备侧 **SAD 的要求是“不漏检这两种声音”**，不要求在端侧区分两者。

### 4.3 设备侧实现路径（与当前仓库对齐）

**当前默认配置**（`sdkconfig.defaults`）：`CONFIG_USE_AUDIO_PROCESSOR=y`，且 **未** 开启 `CONFIG_USE_DEVICE_AEC` 时 AFE 内 **VAD 可用**；若未来开启设备 AEC，需核对 AFE 是否禁用 VAD，并准备 **独立 SAD/轻量分类模型** 路径。

建议技术路线（按投入递增）：

| 阶段 | 内容 |
|------|------|
| P0 | 保留 AFE VAD 作 **对话/语音段** 参考；感知模式增加 **能量/过零率/谱通量** 等 **SAD**，降低「只有说话才算有效」的漏报 |
| P1 | 增加 **轻量级 ESC（Environmental Sound Classification）或关键词式事件模型**（喇叭/关门等），输出标签入 MQTT/感知 payload |
| P2 | 云端 **大模型/专用声学模型** 对分段做细分类；设备只负责 **无损或低损切片上传**（受带宽约束） |
| P3 | 与 **音乐检测**（仓库内已有 `CONFIG_USE_LOCAL_MUSIC_DETECTION` 等启发式）协同：**音乐/歌唱** 与 **语音** 的互斥与并存策略写清 |

### 4.3.1 SAD 如何实现（建议 P0 方案，强调不漏检）

目标：在 `SessionMode=Perception` 且联网、非 independent 时，尽量 **不漏检** 下列声音触发（之后由后端识别/分类）：

- 导航播报 / 播客（媒体语音类）
- 播放音乐
- 鸣笛/喇叭
- 车门开关等瞬态
- 人体咳嗽/呼喊/唱歌

建议实现为 **双通道触发**（任一满足即判定“有声活动”，进入切片/上报）：
1. **能量触发（RMS/峰值）**：对每个 20–60ms 帧计算 RMS 与 peak。
   - 用较低的 RMS 阈值保证不漏检媒体播报/播客。
   - 用 peak 辅助捕获喇叭/关门的瞬态。

2. **谱变化触发（ZCR/谱通量/带宽能量比）**：用于补足“RMS 不高但具有结构”的声音（部分播报、低音量音乐）。

同时引入 **hangover（尾随保持）** 与 **最小时长/最大时长**，把连续活动拼成片段：

- `enter`: 连续 \(N_{enter}\) 帧触发则进入 active（例如 2–3 帧）
- `exit`: 连续 \(N_{exit}\) 帧不触发则退出 active（例如 8–15 帧，约 0.5–1s）
- `min_segment_ms`: 例如 200–300ms（过滤极短毛刺，但注意不要漏掉关门/喇叭；对瞬态可走“事件片段”特殊分支）
- `max_segment_ms`: 例如 8–15s（防止长音乐无限段；超长段可切块上报）

输出：SAD 仅输出片段边界与“触发原因位图”（energy / peak / spectral），后端再做细分。

> 重要：你已明确“不考虑功耗和流量”，因此 P0 里阈值选择应偏向 **召回优先（recall-first）**，宁可多报由后端过滤，也避免漏检导航/播客等关键声音。

### 4.3.2 已决策：仅在 segment 内上报音频

- **无触发**（SAD 未 active）时：音频 **丢弃**，不编码、不上行。
- **触发后**：仅在 segment active 的窗口内上报音频（必要时可在 start 前做极短 pre-roll，例如 100–300ms，用于捕获瞬态的起始边沿；若不需要可不做）。

### 4.3.3 本地检测融合降本：SAD / LOUD_SOUND / music 共用特征 + FFT 降频（已决策）

目标：在保留 **本地 music** 与 **Sprouting LOUD_SOUND** 的前提下，避免与 perception 的 SAD 重复计算，尤其降低 FFT 的整体开销。

#### 统一“每帧基础特征”（一次计算，多处复用）

对每个 60ms PCM 帧仅计算一次以下特征，并在同一管线内复用：

- `rms`（用于 SAD、LOUD_SOUND、music FFT 门控）
- `peak`（用于 SAD 捕获瞬态，如喇叭/关门）
- `zcr`（用于 SAD 的“谱变化”补足）

> 决策：SAD 帧长固定 60ms，LOUD_SOUND 直接复用同一 `rms`，不再重复调用 RMS 计算函数。

#### music FFT “按需运行”（降频 + 保守门控）

保留 music 检测能力，但把 FFT 从“每帧跑”改为“满足条件才跑”：

- **降频**：在 `perception` session 下，FFT 以较低频率执行（建议初始值：**每 5 帧**一次，即约 300ms 更新）。
- **门控（偏保守）**：在 `perception` session 下，FFT 仅在 `rms` 达到门控阈值时运行，降低无效 FFT。

> 决策：FFT 降频可接受；门控阈值选择偏保守。具体阈值以路测标定为准（初始值可先偏高，保证不在“静音/低能”环境频繁 FFT）。

#### 验收点

- perception 开启时：SAD 能正常触发上报 segment；music 状态仍可用但 FFT 调用次数显著降低。
- Sprouting 阶段：LOUD_SOUND 检测不重复计算 RMS，且不影响 SAD 的段内上报。

### 4.4 上报语义（建议）

每条感知分段或事件建议包含（与后端对齐）：

- `t_start_ms` / `t_end_ms`（设备单调时钟或 UTC，与 revent 对齐方式一致）；
- `session_mode` = `perception`；
- `vad_or_sad` 标志；
- `event_labels[]`: `{ type, confidence }`；
- 可选：`opus_attachment` 或云端拉取句柄；
- `placement_state`、`mqtt_connected` 等上下文快照（按需）。

后端再将上述信息与 **revent**（DRIVE/PARK/INSTALL 等）做时间关联。

### 4.5 风险与约束

- **隐私**：持续环境监听需产品与合规确认；建议配置开关与状态提示。
- **误报/漏报**：车门/喇叭与地区车型差异大，需 **实地路测集** 迭代。
- **算力与耗电**：板端分类模型需控制在 ESP 可承受范围，否则 **SAD 在端 + 分类在云**。
- **AEC 与 VAD**：若启用设备 AEC 导致 VAD 关闭，需提前在本文档中固定 **替代检测方案**。

---

## 5. 可行性结论（摘要）

| 方向 | 可行性 | 主要缺口 |
|------|--------|----------|
| 底座 + 联网门控 + 常采音 | 高 | 感知专用稳定上行；`SessionMode` 状态机 |
| 后端融合音频 + MQTT + Agent 下发 | 高 | 统一下行指令 schema；服务端编排 |
| 唤醒 + 流式对话 + 结束回感知 | 中高 | 退出条件与通道清理；与感知抢占 |
| 扩展 VAD 至咳嗽/呼喊/歌唱及环境声 | **中**（分期） | 经典 VAD 不足部分需 **SAD + 分类/云端**；导航/播客细分类难，需预期管理 |

---

## 6. 待决问题（讨论清单）

- [x] 「方案底座」与 `PlacementState` 的精确映射：按 equipment 事件口径，`PlacementState != kPlacementIndependent` 视为在底座上；independent 或断网立即停止上报。
- [x] 感知上行：复用现有音频上行链路，通过 `stream_role` 区分 `dialog/perception`。
- [ ] Agent 下行 JSON 最终字段表与版本号 `schema_version`（已给 v1 草案，待你们服务端/固件一起确认字段名与文件名规范）。
- [ ] VAD/SAD/分类：P0～P2 分期与是否板端模型选型（TensorFlow Micro / ESP-SR 扩展 / 纯启发式）。
- [x] 导航播报 vs 播客：端侧不区分，后端处理；端侧 SAD 仅要求不漏检。
- [x] 功耗与流量上限：当前阶段不考虑；断网或 independent 即停止上报。

本轮新增决策（已反映在正文）：

- [x] `perception` 与 `dialog` **不共用 session**（唤醒触发切换，结束后按条件恢复）。
- [x] perception：**只在 SAD segment 内上报音频**（非 segment 丢弃）。
- [x] Agent 指令资源标识：表情/音效使用 **宏定义 ID**（如 `MMAP_OTA_0_SURPRISED_AAF`、`P3_POPUP`），不使用文件名。
- [x] 转头控制：使用 **步进数 `steps`**，不使用角度。

---

## 7. 参考代码与文档路径（仓库内）

- 音频主路径：`main/application.cc`（`AudioLoop`、`OnAudioInput`、`audio_processor_->OnOutput`、`MainEventLoop`）
- AFE / VAD：`main/audio_processing/afe_audio_processor.cc`
- MQTT：`main/protocols/mqtt_protocol.cc`
- Lukka 底座：`main/boards/lukka/base_controller.*`、`main/boards/lukka/lukka.cc`（`OnPlacementChanged` 等）
- Revent 协议：`docs/lukka_revent_mqtt_protocol_v1.md`
- 下行 JSON 类型示例：`docs/websocket.md`（`tts` / `llm` 等）

---

*文末：后续请在「变更记录」表中追加行，并在对应章节直接修订正文，避免多份方案分叉。*
