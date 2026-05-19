# Copilot 对话 API

基础路径：`/copilot`

---

## 目录

| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 1 | `POST` | `/copilot/chat` | 提交对话任务，返回 task uuid |
| 2 | `GET` | `/copilot/chat/result` | 按 uuid 查询任务执行轨迹与状态 |

---

## 1. 提交对话任务

```
POST /copilot/chat
Content-Type: application/json
```

### 请求体 (CopilotRequest)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agentId` | `Long` | 是 | Agent ID |
| `currentMessage` | `String` | 是 | 客户当前发送的消息 |
| `historyMessages` | `List<HistoryMessage>` | 否 | 历史消息（按时间正序，每条包含角色、内容、时间） |
| `historySummary` | `String` | 否 | 更早历史消息的总结 |

#### 请求示例

```json
{
  "agentId": 1,
  "currentMessage": "极石汽车多少钱？",
  "historyMessages": [
    { "role": "客户", "content": "你好", "time": "2026-05-19 11:30:00" },
    { "role": "客服", "content": "您好，请问有什么可以帮您", "time": "2026-05-19 11:30:05" }
  ],
  "historySummary": "客户前几日咨询过价格，倾向 30 万左右"
}
```

### 成功响应 (code=0)

```json
{
  "code": 0,
  "msg": null,
  "data": {
    "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  }
}
```

### 错误响应

| code | msg | 说明 |
|------|-----|------|
| `400` | 参数错误 | 请求参数校验失败 |
| `600` | Agent不存在或已禁用 | agentId 无效 |
| `500004` | 业务异常：{详情} | 服务端运行时异常 |

---

## 2. 查询任务轨迹

```
GET /copilot/chat/result?uuid={uuid}
```

### 查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `uuid` | `String` | 是 | 提交任务时返回的唯一标识 |

### 成功响应 (code=0)

#### 任务运行中 (status=RUNNING)

```json
{
  "code": 0,
  "msg": null,
  "data": {
    "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "agentId": 1,
    "agentName": "售前客服助手",
    "status": "RUNNING",
    "finalReply": null,
    "errorMsg": null,
    "createdAt": "2026-05-18T10:00:00Z",
    "finishedAt": null,
    "steps": [
      { "type": "USER_INPUT",  "content": "极石汽车多少钱？",           "toolName": null,      "toolArgs": null,                           "time": "2026-05-18T10:00:00Z" },
      { "type": "LLM_THINK",  "content": "用户询问极石汽车价格...",     "toolName": null,      "toolArgs": null,                           "time": "2026-05-18T10:00:01Z" },
      { "type": "TOOL_CALL",  "content": "正在查询极石汽车价格...",     "toolName": "ragSearch","toolArgs": "{\"query\":\"极石汽车 价格\"}", "time": "2026-05-18T10:00:02Z" },
      { "type": "TOOL_RESULT","content": "{\"price\":\"29.99万起\"}",   "toolName": "ragSearch","toolArgs": null,                           "time": "2026-05-18T10:00:03Z" },
      { "type": "LLM_THINK",  "content": "根据搜索结果组织回复...",     "toolName": null,      "toolArgs": null,                           "time": "2026-05-18T10:00:04Z" }
    ]
  }
}
```

#### 任务已完成 (status=SUCCESS)

```json
{
  "code": 0,
  "msg": null,
  "data": {
    "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "agentId": 1,
    "agentName": "售前客服助手",
    "status": "SUCCESS",
    "finalReply": "极石汽车目前售价为 29.99 万元起，具体配置价格请咨询当地经销商。",
    "errorMsg": null,
    "createdAt": "2026-05-18T10:00:00Z",
    "finishedAt": "2026-05-18T10:00:05Z",
    "steps": [
      { "type": "USER_INPUT",  "content": "极石汽车多少钱？",           "toolName": null,      "toolArgs": null,                           "time": "2026-05-18T10:00:00Z" },
      { "type": "LLM_THINK",  "content": "用户询问极石汽车价格...",     "toolName": null,      "toolArgs": null,                           "time": "2026-05-18T10:00:01Z" },
      { "type": "TOOL_CALL",  "content": "正在查询极石汽车价格...",     "toolName": "ragSearch","toolArgs": "{\"query\":\"极石汽车 价格\"}", "time": "2026-05-18T10:00:02Z" },
      { "type": "TOOL_RESULT","content": "{\"price\":\"29.99万起\"}",   "toolName": "ragSearch","toolArgs": null,                           "time": "2026-05-18T10:00:03Z" },
      { "type": "LLM_THINK",  "content": "根据搜索结果组织回复...",     "toolName": null,      "toolArgs": null,                           "time": "2026-05-18T10:00:04Z" },
      { "type": "FINAL_REPLY","content": "极石汽车目前售价为 29.99 万元起...", "toolName": null, "toolArgs": null,                           "time": "2026-05-18T10:00:05Z" }
    ]
  }
}
```

### 错误响应

| code | msg | 说明 |
|------|-----|------|
| `601` | 任务不存在或已过期 | uuid 无效或 Redis 中已过期清除 |

---

## 3. 数据模型

### 统一响应 (Rsp\<T\>)

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | `int` | 状态码，`0` 表示成功，非 `0` 表示错误 |
| `msg` | `String` | 提示消息，成功时为 `null` |
| `data` | `T` | 业务数据 |
| `extra` | `T` | 扩展数据（可选，本接口未使用） |
| `total` | `long` | 分页总数（可选，本接口未使用） |

### 任务快照 (TaskSnapshot)

| 字段 | 类型 | 说明 |
|------|------|------|
| `uuid` | `String` | 任务唯一标识 |
| `agentId` | `Long` | Agent ID |
| `agentName` | `String` | Agent 名称 |
| `status` | `String` | 任务状态：`RUNNING` / `SUCCESS` / `FAILED` |
| `finalReply` | `String` | 最终回复内容（仅 status=SUCCESS 时非空） |
| `errorMsg` | `String` | 失败原因（仅 status=FAILED 时非空） |
| `createdAt` | `Instant` | 任务创建时间 (ISO-8601) |
| `finishedAt` | `Instant` | 任务完成时间 (ISO-8601，未完成时为 `null`) |
| `steps` | `List<StepRecord>` | 决策步骤列表（按时间正序） |

### 历史消息 (HistoryMessage)

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | `String` | 角色，例如：客户 / 客服 / system |
| `content` | `String` | 消息正文 |
| `time` | `String` | 消息时间，格式：`yyyy-MM-dd HH:mm:ss` |

### 步骤记录 (StepRecord)

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `String` | 步骤类型（见下表） |
| `content` | `String` | 步骤内容（自然语言文本或 JSON 字符串） |
| `toolName` | `String` | 工具名称（仅 `TOOL_CALL` / `TOOL_RESULT` 类型） |
| `toolArgs` | `String` | 工具入参 JSON（仅 `TOOL_CALL` 类型） |
| `time` | `Instant` | 时间戳 (ISO-8601) |

### 步骤类型枚举 (StepType)

| 类型 | 说明 | 展示色 |
|------|------|--------|
| `USER_INPUT` | 用户输入消息 | 蓝色 |
| `LLM_THINK` | 大模型推理过程（思考链） | 紫色 |
| `TOOL_CALL` | 工具调用（如 RAG 搜索） | 橙色 |
| `TOOL_RESULT` | 工具返回结果 | 黄色 |
| `FINAL_REPLY` | 最终回复给用户的内容 | 绿色 |
| `ERROR` | 执行过程中发生的错误 | 红色 |

### 任务状态枚举 (TaskStatus)

| 状态 | 说明 |
|------|------|
| `RUNNING` | 任务正在执行中，ReAct 循环尚未结束 |
| `SUCCESS` | 任务执行成功，已产出最终回复 |
| `FAILED` | 任务执行失败，可查看 errorMsg |

---

## 4. 工作流程

```
┌──────────┐         POST /copilot/chat          ┌──────────────┐
│  前端    │ ──────────────────────────────────→  │  后端        │
│          │     ← { uuid: "xxx" }               │  创建任务     │
│          │                                      │  异步启动     │
│          │         GET /copilot/chat/result     │  ReAct 循环   │
│          │ ──────────────────────────────────→  │              │
│          │     ← { status: "RUNNING", steps }   │  逐步产出     │
│          │         (每 800ms 轮询)               │  步骤记录     │
│          │                                      │              │
│          │     ← { status: "SUCCESS",           │  任务完成     │
│          │         finalReply, steps }          │              │
└──────────┘                                      └──────────────┘
```

### 前端轮询策略

1. 调用 `POST /copilot/chat` 提交任务，获得 `uuid`
2. 以 **800ms** 间隔调用 `GET /copilot/chat/result?uuid=xxx`
3. `renderSteps()` 将返回的 `steps` 数组反转渲染，最新步骤展示在最上方，并自动滚动到顶部
4. 当 `status` 为 `SUCCESS` 或 `FAILED` 时，继续轮询一次（800ms 后）确认后停止

---

## 5. 错误码汇总

| code | msg | 触发场景 |
|------|-----|----------|
| `0` | `null` | 成功 |
| `400` | 参数错误 | 请求参数缺失或格式错误 |
| `500` | 服务器状态不佳，请稍后再试 | 通用服务端错误 |
| `600` | Agent不存在或已禁用 | agentId 无效 |
| `601` | 任务不存在或已过期 | uuid 无效或任务已过期 |
| `700` | 大模型调用异常 | LLM 调用失败 |
| `500004` | 业务异常：{详情} | 运行时业务异常 |
| `500201` | 数据不存在 | 查询的数据不存在 |
| `500203` | {自定义消息} | 业务中断（如工具调用失败） |
