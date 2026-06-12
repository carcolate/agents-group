# Copilot 对话 API

基础路径：`/copilot`

---

## 目录

| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 1 | `POST` | `/copilot/chat` | 提交对话任务，返回 task uuid |
| 2 | `GET` | `/copilot/chat/result` | 按 uuid 查询任务执行轨迹与状态 |
| 3 | `POST` | `/copilot/chat/cancel` | 中断指定 uuid 的任务 |

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
| `otherParams` | `Map<String, Object>` | 否 | 额外业务参数（支持嵌套对象），如 `{"userArea": "MA"}`。配合 Agent 配置的「额外参数Key」，以 `{{key}}` 占位符注入各 Prompt，详见 [动态变量](#动态变量-otherparams) |

#### 请求示例

```json
{
  "agentId": 1,
  "currentMessage": "这辆车多少钱？",
  "historyMessages": [
    { "role": "客户", "content": "你好",                "time": "2026-05-19 11:30:00" },
    { "role": "客户", "image":   "https://x.com/car.jpg",  "time": "2026-05-19 11:30:30" },
    { "role": "客户", "content": "就这辆", "image": "https://x.com/car2.jpg", "time": "2026-05-19 11:30:40" },
    { "role": "客服", "content": "您好，请问有什么可以帮您", "time": "2026-05-19 11:31:00" }
  ],
  "historySummary": "客户前几日咨询过价格，倾向 30 万左右",
  "otherParams": {
    "userArea": "MA",
    "uuid": "u-10086",
    "user": { "age": 28 }
  }
}
```

### 动态变量 (otherParams)

`otherParams` 中的值可作为动态变量注入 Agent 的 **前置 Prompt / 回复格式约束 / 语言风格 Prompt**：

1. **Agent 侧配置**：在 Agent 管理页「额外参数 Key」中声明本 Agent 使用的参数 key，多个用英文逗号隔开，如 `uuid,user.age`。key 支持点号路径取嵌套字段（`user.age` → `otherParams.user.age`）。
2. **Prompt 中引用**：在上述三类 Prompt 中以 `{{key}}` 占位，如 `{{uuid}}`、`{{user.age}}`。
3. **运行时替换规则**：
   - 仅替换「额外参数 Key」中声明过的 key，未声明的 `{{xxx}}` 原样保留；
   - 声明了但请求未传值 → 替换为空字符串；
   - 取值优先整键直查（兼容客户端直接传扁平 key `"user.age"`），未命中再按点号逐级下钻嵌套对象；
   - 值为字符串 / 数字 / 布尔时直接输出，对象 / 数组输出为 JSON 字符串。

**示例**：Agent 配置额外参数 Key 为 `uuid,user.age`，前置 Prompt 写 `客户ID：{{uuid}}，年龄：{{user.age}}`，请求传上方示例的 `otherParams`，实际注入 LLM 的内容为 `客户ID：u-10086，年龄：28`。

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

## 3. 中断任务

```
POST /copilot/chat/cancel?uuid={uuid}
```

向 Redis 写入中断标记，并立即在 snapshot 上追加一条「已收到中断请求」步骤。
真正终止由后台 ReAct 编排循环在**下一轮顶端**检测后执行：将 status 置为 `CANCELLED`、`finishedAt` 写当前时间、`errorMsg` 写中断原因，并落 `tb_copilot_history`。

> 实际生效时间 = 当前 LLM 调用剩余耗时。当前轮的 HTTP 调用无法被强行打断，必须等本轮结束。

### 查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `uuid` | `String` | 是 | 提交任务时返回的唯一标识 |

### 成功响应 (code=0)

```json
{
  "code": 0,
  "msg": null,
  "data": {
    "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "result": "REQUESTED"
  }
}
```

`result` 取值：

| 取值 | 说明 |
|------|------|
| `REQUESTED` | 已写入中断标记，runner 将在下一轮 ReAct 顶端退出 |

### 错误响应

| code | msg | 说明 |
|------|-----|------|
| `601` | 任务不存在或已过期 | uuid 无效或 Redis 中已过期清除 |
| `602` | 任务已结束，无需中断 | 当前 status 已是 `SUCCESS` / `FAILED` / `CANCELLED` |

### 推荐前端流程

1. 提交 `/copilot/chat` 后，UI 显示「中断」按钮
2. 用户点击 → `POST /copilot/chat/cancel?uuid=xxx`
3. 继续轮询 `/copilot/chat/result`，直到 status 变为 `CANCELLED`
4. 收到 `CANCELLED` 后隐藏「中断」按钮

---

## 4. 数据模型

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

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `role` | `String` | 是 | 角色，例如：客户 / 客服 / system |
| `content` | `String` | 否 | 消息正文。允许为空（纯图消息时只填 `image` 即可） |
| `image` | `String` | 否 | 单张图片 URL。后端会异步下载并以 `base64 + mimeType` 形式作为独立多模态 `UserMessage` 送入 LLM；下载失败不阻塞主流程，仅在 system prompt 文本里以「已附图：{url}」占位标注 |
| `time` | `String` | 否 | 消息时间，格式：`yyyy-MM-dd HH:mm:ss`，会被拼接为 `yyyy-MM-dd HH:mm:ss 周X` 注入 prompt |

> `content` 与 `image` 至少一个有值；二者都空的条目会被服务端跳过。

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
| `IMAGE_FETCH` | 历史图片下载日志（成功 / 失败都会落一条，content 含 URL、MIME、字节数、耗时或错误原因） | 青色 |
| `STYLE_REWRITE` | 风格润色（agent.stylePrompt 非空时对草稿做的二次改写） | 绿松石 |
| `FINAL_REPLY` | 最终回复给用户的内容 | 绿色 |
| `ERROR` | 执行过程中发生的错误 | 红色 |

### 任务状态枚举 (TaskStatus)

| 状态 | 说明 |
|------|------|
| `RUNNING` | 任务正在执行中，ReAct 循环尚未结束 |
| `SUCCESS` | 任务执行成功，已产出最终回复 |
| `FAILED` | 任务执行失败，可查看 errorMsg |
| `CANCELLED` | 用户主动中断（通过 `POST /copilot/chat/cancel`），errorMsg 记录中断时所处轮次 |

---

## 5. 工作流程

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

## 6. 错误码汇总

| code | msg | 触发场景 |
|------|-----|----------|
| `0` | `null` | 成功 |
| `400` | 参数错误 | 请求参数缺失或格式错误 |
| `500` | 服务器状态不佳，请稍后再试 | 通用服务端错误 |
| `600` | Agent不存在或已禁用 | agentId 无效 |
| `601` | 任务不存在或已过期 | uuid 无效或任务已过期 |
| `602` | 任务已结束，无需中断 | cancel 接口针对已是 SUCCESS / FAILED / CANCELLED 的任务调用 |
| `700` | 大模型调用异常 | LLM 调用失败 |
| `500004` | 业务异常：{详情} | 运行时业务异常 |
| `500201` | 数据不存在 | 查询的数据不存在 |
| `500203` | {自定义消息} | 业务中断（如工具调用失败） |

---

## 7. 注意事项 - 历史图片 (image)

- **图片必须外网可达**：服务端会通过 HTTP GET 拉取，不支持鉴权 URL。
- **大小上限**：默认 5MB（`copilot.image.max-bytes`），超限直接判失败。
- **MIME 白名单**：默认 `image/png,image/jpeg,image/webp,image/gif`（`copilot.image.allowed-mime`）。MIME 解析顺序：响应头 `Content-Type` → URL 后缀 → 默认 `image/jpeg`。
- **下载超时**：默认 10s（`copilot.image.timeout-seconds`）。每张图独立计时，单张失败不影响其他图和主流程。
- **本地缓存**：所有下载成功的图片以 URL 的 MD5 为文件名缓存在 `copilot.image.cache-dir` 目录下，每张图落两个文件：`{md5}.dat`（二进制）+ `{md5}.mime`（MIME 类型）。下次同 URL 请求**先查盘命中即跳过 HTTP 下载**，对应 `IMAGE_FETCH` 步骤文案为「缓存命中」，未命中则为「下载成功」。
  - 默认路径：`./cache`（即 app.jar 同级 / 启动时的工作目录下的 `cache` 目录）
  - Docker 部署：`Dockerfile` 已通过 `ENV COPILOT_IMAGE_CACHE_DIR=/app/cache` 覆盖，并声明 `VOLUME` 便于宿主机挂载，避免容器重启缓存失效
  - 缓存写入采用 `.tmp + ATOMIC_MOVE` 防止半成品被误命中；读取异常时自动回退到 HTTP 下载
- **失败兜底**：下载失败时不注入多模态 UserMessage，但 system prompt 文本里仍保留「已附图：{url}」占位，并产出一条 `IMAGE_FETCH` 失败步骤。
- **模型要求**：含图请求必须用多模态模型（如 `doubao-seed-2.0-lite/pro`、`gpt-4o` 系列）。`deepseek-chat` 等纯文本模型收到 image 参数会被上游网关拒绝，触发 `ERROR` 步骤并 `markFailed`。
- **快照体积**：base64 图片只入 LLM 请求 messages，不写入 Redis 的 TaskSnapshot；`IMAGE_FETCH` 步骤里只记录 URL + 元数据。
