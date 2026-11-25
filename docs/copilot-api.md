# Carcolate Agents API 文档

本文档以当前 AgentGroup 实现为准，包含 Copilot 运行接口、Agent/知识库管理接口和调用历史接口。

## 1. 基础约定

- 默认服务端口：7893，以实际部署配置为准。
- 基础路径没有额外的 /api 前缀。
- AgentGroup 不在应用代码中增加鉴权、Token、CORS 或来源限制；访问策略由外部网关负责。
- 所有 Copilot 任务均为异步任务：提交接口只创建任务并返回 uuid，模型调用通过结果接口轮询。
- 任务快照默认在 Redis 中保留 86400 秒，可由 copilot.task.ttl-seconds 调整。
- Agent、知识库和历史记录 ID 在服务端均为 Long。JavaScript 客户端不要使用超过安全整数范围的 ID 做普通 Number 运算。

### 1.1 统一响应

成功响应：

~~~json
{
  "code": 0,
  "msg": null,
  "data": {},
  "extra": null,
  "total": 0
}
~~~

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| code | int | 0 表示成功 |
| msg | String | 失败提示；成功时通常为 null |
| data | Object | 业务数据 |
| extra | Object | 可选扩展数据 |
| total | long | 分页接口的总记录数 |

分页接口返回：

~~~json
{
  "code": 0,
  "msg": null,
  "data": [],
  "extra": null,
  "total": 25
}
~~~

## 2. Copilot 接口总览

基础路径：/copilot

| 方法 | 路径 | 功能 | 使用的 Prompt | 知识库 |
|---|---|---|---|---|
| POST | /copilot/chat | 实时回复 | 仅主 Prompt（prePrompt） | 支持 |
| POST | /copilot/summarize | 客户信息总结 | 服务端内置总结 Prompt，按 summarizeLanguage 输出 | 不使用 |
| POST | /copilot/generate | 单次模型生成 | 调用方传入系统和用户 Prompt | 不使用 |
| GET | /copilot/result?uuid={uuid} | 通用任务结果查询 | - | - |
| GET | /copilot/chat/result?uuid={uuid} | 结果查询兼容路径 | - | - |
| POST | /copilot/chat/cancel?uuid={uuid} | 请求中断任务 | - | - |

### 2.1 Prompt 与知识库

`chat` 使用 Agent 的主 Prompt，并从历史摘要、历史消息和客户参数构建会话上下文。AgentGroup 会检查启用文档的 URL 刷新。engageType=1 的文档按需检索，engageType=2 的文档全文注入。

`summarize` 使用内置总结 Prompt 和历史对话进行单次模型调用。summarizeLanguage 为 zh_en 时返回中英双语，其余返回中文。

`generate` 通过 agentId 选择启用的 Agent 和对应模型，只发起一次模型调用。调用方负责拼装上下文、约定输出格式并校验结果。AgentGroup 直接使用传入的 Prompt 和可选图片，返回模型文本。知识库和工具由 `chat` 使用。

## 3. Copilot 请求体

### Chat 和 Summarize 请求

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| agentId | Long | 是 | 已启用 Agent 的 ID |
| userId | String | 否 | 调用方用户标识，仅写入调用历史，不参与 Prompt |
| currentMessage | String | 否 | 当前消息。建议提供 |
| historyMessages | List<HistoryMessage> | 否 | 历史消息，建议按时间正序提交 |
| historySummary | String | 否 | 更早历史消息的压缩摘要 |
| otherParams | Map<String,Object> | 否 | 客户或业务参数，也会作为客户信息注入 Prompt |

服务端校验 agentId 存在且对应 Agent 已启用。Chat 允许空的 currentMessage。

### 3.1 Chat 和 Summarize 请求示例

~~~json
{
  "agentId": 836774729814085,
  "userId": "819789371277381",
  "currentMessage": "客户想了解价格和到店安排",
  "historyMessages": [
    {
      "role": "客户",
      "content": "你好，我想了解一下这款车",
      "time": "2026-08-19 05:30:42"
    },
    {
      "role": "客服",
      "content": "您好，请问您更关注价格还是配置？",
      "time": "2026-08-19 05:42:42"
    }
  ],
  "historySummary": "客户此前关注新能源车，尚未确定到店时间。",
  "otherParams": {
    "userArea": "BH",
    "customerType": "dealer"
  }
}
~~~

### 3.2 HistoryMessage

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| role | String | 是 | 例如：客户、客服、system |
| content | String | 否 | 消息正文 |
| image | String | 否 | 单张图片 URL |
| time | String | 否 | 建议格式：yyyy-MM-dd HH:mm:ss |

content 和 image 至少一个有值；二者都为空的消息会被跳过。

图片处理规则：

- 服务端通过 HTTP GET 下载图片，并以多模态消息提交给模型。
- 默认单张图片大小上限为 5 MB。
- 默认允许 image/png、image/jpeg、image/webp、image/gif。
- 图片下载失败不会阻断主任务，只在步骤中记录失败，并在上下文中保留 URL 占位。
- 图片请求需要使用支持多模态的模型。

### 3.3 otherParams 动态变量

Agent 的 otherParamKeys 配置声明允许使用的变量，多个 key 使用英文逗号分隔，例如：

~~~text
uuid,user.age
~~~

功能 Prompt 中可以使用：

~~~text
客户 ID：{{uuid}}，年龄：{{user.age}}
~~~

规则：

1. 只有声明过的 key 会被替换。
2. 声明了但请求未传值时替换为空字符串。
3. 支持点号路径；先尝试整键直查，再按嵌套对象逐级查找。
4. 字符串、数字、布尔值直接转文本；对象和数组转为 JSON 字符串。
5. 变量替换作用于当前任务实际使用的功能 Prompt，不会把某个功能 Prompt 复制到其他任务。

## 4. 提交 Copilot 任务

### 4.1 请求

~~~http
POST /copilot/{task}
Content-Type: application/json
~~~

任务类型可选 `chat`、`summarize` 或 `generate`。Chat 和 Summarize 请求体见第 3 节。

`POST /copilot/generate` 请求字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| agentId | Long | 是 | 用来选择模型的启用 Agent |
| userId | String | 否 | 调用方标识，写入调用历史 |
| systemPrompt | String | 是 | 调用方组装的系统指令，包含所需的输出格式 |
| userPrompt | String | 是 | 用户内容和任务上下文 |
| attachments | Array | 否 | 可选图片。元素包含 URL 字符串 `url` 和可选的说明字符串 `caption` |

~~~json
{
  "agentId": 836774729814085,
  "userId": "819789371277381",
  "systemPrompt": "总结这段对话，只返回 JSON 对象 {\"zh\":\"中文总结\"}。",
  "userPrompt": "客户询问周末是否可以到店试驾。"
}
~~~

### 4.2 成功响应

~~~json
{
  "code": 0,
  "msg": null,
  "data": {
    "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  }
}
~~~

提交成功只代表任务已创建，不代表模型已经完成。

### 4.3 最终输出

任务结果通过 `GET /copilot/result?uuid={uuid}` 查询，成功时读取 `finalReply` 字符串。

| 任务 | finalReply |
|---|---|
| chat | 固定 JSON 字符串，例如 `{"msg":"周末可以到店试驾。"}`，调用方解析 `msg` |
| summarize | JSON 字符串，中文为 `{"zh":"中文总结"}`，双语为 `{"zh":"中文总结","en":"English summary"}`。缺少配置语言的非空文本时，任务状态为 FAILED |
| generate | 模型返回的原始文本。若调用方要求 JSON，由调用方校验并解析 |

Carcolate-IM 的会话压缩、客户信息总结、标签判断和资料提取共用 `generate`。IM 为各任务生成输出指令。IM 根据客户平台账号所属的业务模型读取允许 Copilot 管理且有判定条件的标签。标签 Agent 负责选择模型。

Carcolate-IM 的客户信息总结使用 IM 内置 Prompt，要求模型同时返回非空的 zh 和 en，由 IM 写入现有的中英文总结字段。

会话压缩要求模型返回 `{"rsp":"压缩后的会话摘要"}`。IM 将 rsp 用作后续对话的内部上下文。

AgentGroup 和 IM 后端需在同一次发布中更新。标签判定读取 IM 的 tb_stage.copilot_prompt。未填写条件的标签会跳过自动判定。

### 4.4 提交错误

| code | msg | 说明 |
|---:|---|---|
| 400 | 参数错误 | 请求为空、agentId 缺失，或 generate 的 systemPrompt、userPrompt 为空 |
| 600 | Agent不存在或已禁用 | Agent 不存在或 status != 1 |
| 500004 | 业务异常：{详情} | 提交阶段发生其他服务异常；异步运行异常请查看任务的 errorMsg |

## 5. 查询任务结果

以下两个路径执行相同逻辑：

~~~http
GET /copilot/result?uuid={uuid}
GET /copilot/chat/result?uuid={uuid}
~~~

### 5.1 运行中响应

~~~json
{
  "code": 0,
  "msg": null,
  "data": {
    "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "agentId": 836774729814085,
    "agentName": "招商顾问",
    "taskType": "tag",
    "modelName": "qwen3.6-plus",
    "status": "RUNNING",
    "finalReply": null,
    "errorMsg": null,
    "createdAt": "2026-08-21T02:53:16.709Z",
    "finishedAt": null,
    "steps": [
      {
        "type": "USER_INPUT",
        "content": "请根据历史对话判断客户当前标签。",
        "toolName": null,
        "toolArgs": null,
        "thinking": null,
        "time": "2026-08-21T02:53:16.710Z"
      },
      {
        "type": "LLM_THINK",
        "content": "...",
        "toolName": null,
        "toolArgs": null,
        "thinking": null,
        "time": "2026-08-21T02:53:17.200Z"
      }
    ],
    "systemPrompt": "..."
  }
}
~~~

### 5.2 成功响应

以 chat 为例：

~~~json
{
  "code": 0,
  "msg": null,
  "data": {
    "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "agentId": 1,
    "agentName": "售前客服助手",
    "taskType": "chat",
    "modelName": "qwen3.6-plus",
    "status": "SUCCESS",
    "finalReply": "{\"msg\":\"极石汽车目前售价为29.99万元起，具体配置价格可以继续帮您确认。\"}",
    "errorMsg": null,
    "createdAt": "2026-08-21T02:53:16.709Z",
    "finishedAt": "2026-08-21T02:53:20.105Z",
    "steps": [
      {
        "type": "USER_INPUT",
        "content": "这辆车多少钱？",
        "toolName": null,
        "toolArgs": null,
        "thinking": null,
        "time": "2026-08-21T02:53:16.710Z"
      },
      {
        "type": "FINAL_REPLY",
        "content": "{\"msg\":\"极石汽车目前售价为29.99万元起，具体配置价格可以继续帮您确认。\"}",
        "toolName": null,
        "toolArgs": null,
        "thinking": null,
        "time": "2026-08-21T02:53:20.105Z"
      }
    ],
    "systemPrompt": "..."
  }
}
~~~

systemPrompt 是本次实际提交给模型的 System Prompt 原文，可能包含功能 Prompt、历史内容、客户信息和 chat 的知识库内容，调用方应按敏感数据处理。

### 5.3 TaskSnapshot

| 字段 | 类型 | 说明 |
|---|---|---|
| uuid | String | 任务唯一标识 |
| agentId | Long | Agent ID |
| agentName | String | Agent 名称快照 |
| taskType | String | 新任务为 chat、summarize 或 generate。历史记录可能包含旧任务类型 |
| modelName | String | 本次实际调用的模型；Agent 未配置时为全局模型列表第一项 |
| status | String | RUNNING、SUCCESS、FAILED、CANCELLED |
| finalReply | String | 成功时的固定格式 JSON 字符串 |
| errorMsg | String | 失败或中断原因 |
| createdAt | Instant | 创建时间，ISO-8601 |
| finishedAt | Instant | 完成时间，未完成时为 null |
| steps | List<StepRecord> | 决策步骤，按产生顺序排列 |
| systemPrompt | String | 本次实际使用的完整 System Prompt |

### 5.4 StepRecord

| 字段 | 类型 | 说明 |
|---|---|---|
| type | String | 步骤类型 |
| content | String | 自然语言或 JSON 字符串 |
| toolName | String | 工具名，仅工具步骤使用 |
| toolArgs | String | 工具入参 JSON，仅工具调用步骤使用 |
| thinking | String | 模型 reasoning/thinking 内容，可能为空 |
| time | Instant | 步骤时间，ISO-8601 |

步骤类型：

| 类型 | 说明 |
|---|---|
| USER_INPUT | 提交的当前消息 |
| LLM_THINK | 模型中间输出 |
| TOOL_CALL | chat 的 RAG 工具调用 |
| TOOL_RESULT | RAG 工具返回结果 |
| IMAGE_FETCH | 历史图片下载或缓存命中记录 |
| FINAL_REPLY | 固定格式最终结果，内容为 JSON 字符串 |
| ERROR | 执行错误或中断记录 |

## 6. 中断任务

~~~http
POST /copilot/chat/cancel?uuid={uuid}
~~~

成功响应：

~~~json
{
  "code": 0,
  "msg": null,
  "data": {
    "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "result": "REQUESTED"
  }
}
~~~

说明：

- 接口路径保留 chat/cancel 兼容名称。
- Redis 会立即写入中断标记，并在快照追加中断请求步骤。
- chat 的 ReAct 循环在下一轮开始前检查标记，随后将任务置为 CANCELLED。
- 当前正在执行的模型 HTTP 请求无法被强制打断。
- summarize 和 generate 在单次模型调用前后检查中断标记。已经发出的模型请求正常返回后，任务才会标记为 CANCELLED。

错误：

| code | msg | 说明 |
|---:|---|---|
| 601 | 任务不存在或已过期 | uuid 不存在或快照已过期 |
| 602 | 任务已结束，无需中断 | 任务已经是 SUCCESS、FAILED 或 CANCELLED |

## 7. Agent 管理接口

基础路径：/manage/agent

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /manage/agent/availableModels | 获取配置中的可用模型 |
| GET | /manage/agent/list | Agent 分页列表 |
| GET | /manage/agent/get?id={id} | Agent 详情 |
| POST | /manage/agent/add | 新增 Agent |
| POST | /manage/agent/update | 更新 Agent |
| POST | /manage/agent/delete?id={id} | 删除 Agent 及其知识库 |

### 7.1 可用模型

~~~http
GET /manage/agent/availableModels
~~~

响应：

~~~json
{
  "code": 0,
  "msg": null,
  "data": [
    "doubao-seed-2.0-lite",
    "doubao-seed-2.0-pro",
    "qwen3.6-plus"
  ]
}
~~~

列表第一项是全局默认模型。Agent 的 modelName 为空时使用该模型。

### 7.2 Agent 列表

~~~http
GET /manage/agent/list?pageNum=1&pageSize=20&name=销售&status=1
~~~

支持的筛选字段：

| 参数 | 类型 | 说明 |
|---|---|---|
| pageNum | int | 页码，默认 1 |
| pageSize | int | 页大小，默认 20 |
| id | Long | 精确匹配 |
| name | String | 名称模糊匹配 |
| status | int | 1 启用，0 停用 |

### 7.3 Agent 字段

Agent 管理请求和响应使用以下字段：

| 字段 | 类型 | 新增/更新 | 说明 |
|---|---|---|---|
| id | Long | 更新必填 | 新增时由服务端生成 |
| name | String | 新增必填；更新后不能为空 | Agent 名称 |
| prePrompt | String | 新增必填；更新后不能为空 | chat 专用主 Prompt |
| compactPrompt | String | 可选 | 会话压缩 Prompt。IM 读取后组装 generate 请求，留空时使用默认压缩 Prompt |
| summarizeLanguage | String | 可选 | zh 或 zh_en，新增时默认 zh |
| otherParamKeys | String | 可选 | 逗号分隔的动态参数 key |
| modelName | String | 可选 | Agent 专用模型；为空使用全局默认模型 |
| maxSteps | int | 可选 | 1-20；新增时为空或非正数默认 6，主要用于 chat |
| status | int | 可选 | 1 启用，0 停用；新增时默认 1 |
| remark | String | 可选 | 备注 |
| createdAt | Instant | 只读 | 创建时间 |
| updatedAt | Instant | 只读 | 更新时间 |

虽然 prePrompt 只用于 chat，当前管理接口为保持 Agent 基础配置兼容，新增和更新时仍要求它非空。

### 7.4 Agent 新增示例

~~~http
POST /manage/agent/add
Content-Type: application/json
~~~

~~~json
{
  "name": "海外招商顾问",
  "prePrompt": "你是专业的海外招商顾问，负责引导合作商户。",
  "summarizeLanguage": "zh_en",
  "otherParamKeys": "userArea,customerType",
  "modelName": "qwen3.6-plus",
  "maxSteps": 6,
  "status": 1,
  "remark": "海外招商场景"
}
~~~

### 7.5 Agent 更新、删除

更新：

~~~http
POST /manage/agent/update
Content-Type: application/json
~~~

请求体至少包含：

~~~json
{
  "id": 836774729814085,
  "name": "海外招商顾问",
  "prePrompt": "你是专业的海外招商顾问，负责引导合作商户。",
  "status": 1
}
~~~

更新校验：

- id 必须存在。
- 最终生效的 name、prePrompt 不能为空。
- maxSteps 必须在 1-20。
- status 只能是 0 或 1。
- summarizeLanguage 只能是 zh 或 zh_en。

删除：

~~~http
POST /manage/agent/delete?id=836774729814085
~~~

AgentGroup 删除 Agent 时会删除其知识库记录，并重建该 Agent 的向量索引，避免遗留向量数据。AgentGroup 本身不感知 Carcolate-IM 的账号绑定关系；如果由 IM 代理删除，应先由 IM 做绑定引用检查。

## 8. 知识库管理接口

基础路径：/manage/rag

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /manage/rag/list | 知识库分页列表 |
| GET | /manage/rag/get?id={id} | 知识库详情 |
| POST | /manage/rag/add | 新增知识库文档 |
| POST | /manage/rag/update | 更新知识库文档 |
| POST | /manage/rag/delete?id={id} | 删除知识库文档 |
| POST | /manage/rag/rebuild?agentId={agentId} | 重建 Agent 向量索引 |

### 8.1 知识库字段

| 字段 | 类型 | 新增/更新 | 说明 |
|---|---|---|---|
| id | Long | 更新必填 | 新增时由服务端生成 |
| agentId | Long | 必填 | 所属 Agent |
| title | String | 必填 | 文档标题 |
| content | String | 通常必填 | Markdown 或纯文本；开启 URL 刷新时会被 URL 响应正文覆盖 |
| url | String | 可选 | 自动刷新来源 URL |
| autoRefresh | int | 可选 | 1 开启，0 关闭，默认 0 |
| refreshIntervalMinutes | int | 可选 | 刷新间隔，默认 60；0 表示每次 chat 请求都检查刷新 |
| lastRefreshTime | Instant | 只读 | 最近一次成功 URL 刷新时间 |
| summary | String | 可选 | engageType=1 时为空会自动生成目录摘要 |
| engageType | int | 可选 | 1 AI 自检索，2 前置全文注入，默认 1 |
| status | int | 可选 | 1 启用，0 停用，默认 1 |
| createdAt | Instant | 只读 | 创建时间 |
| updatedAt | Instant | 只读 | 更新时间 |

### 8.2 知识库列表

~~~http
GET /manage/rag/list?pageNum=1&pageSize=20&agentId=836774729814085&title=价格&status=1
~~~

支持的筛选字段：

| 参数 | 类型 | 说明 |
|---|---|---|
| pageNum | int | 页码，默认 1 |
| pageSize | int | 页大小，默认 20 |
| id | Long | 精确匹配 |
| agentId | Long | 精确匹配所属 Agent |
| title | String | 标题模糊匹配 |
| status | int | 1 启用，0 停用 |

### 8.3 新增或更新示例

~~~http
POST /manage/rag/add
Content-Type: application/json
~~~

~~~json
{
  "agentId": 836774729814085,
  "title": "招商合作流程",
  "content": "# 合作流程\n第一步：提交合作信息。",
  "url": "https://example.com/copilot/partner-guide.md",
  "autoRefresh": 1,
  "refreshIntervalMinutes": 30,
  "engageType": 1,
  "status": 1
}
~~~

更新使用相同字段，路径为 POST /manage/rag/update，且请求必须包含 id。未传字段会按原记录合并。

### 8.4 URL 自动刷新

保存时：

1. 当 autoRefresh=1 且 url 非空，管理保存会同步 GET 该 URL。
2. URL 必须返回 2xx，且响应正文不能为空。
3. 响应正文会覆盖 content，并更新 lastRefreshTime。
4. URL 拉取失败时保存失败，返回 500 和 URL 刷新失败：...，不会保存本次记录。

运行时：

1. 仅 chat 任务会在异步任务内部检查 URL 刷新。
2. 只检查启用的知识库文档。
3. refreshIntervalMinutes=0 或没有成功刷新记录时立即刷新。
4. 其他正数表示距 lastRefreshTime 达到对应分钟后刷新。
5. 运行时 URL 刷新失败会记录日志并继续使用上一次成功保存的正文，不会直接让 Copilot 任务失败。
6. 刷新正文后会更新摘要（engageType=1）并重建该 Agent 的向量索引。

### 8.5 参与方式

engageType=1：AI 自检索。

- 启用文档摘要会放入 System Prompt 的知识库目录。
- chat 可以调用 search_knowledge_base 按语义检索。
- 文档内容会进入内存向量索引。

engageType=2：前置知识库。

- 启用文档全文直接放入 System Prompt。
- 不进入向量索引。
- 仅 chat 可见。

知识库文档由 chat 加载。

### 8.6 Copilot 可用的知识库工具

工具只会提供给 chat。

search_knowledge_base：

~~~json
{
  "query": "合作流程和佣金政策",
  "topK": 3
}
~~~

- query 必填。
- topK 可选，默认使用 copilot.rag.default-top-k，默认值为 3。

get_knowledge_document：

~~~json
{
  "ragId": 123456789
}
~~~

只允许读取当前 Agent 下的启用文档。

### 8.7 删除和重建

删除：

~~~http
POST /manage/rag/delete?id=123456789
~~~

删除成功后会按原所属 Agent 重建向量索引。

手动重建：

~~~http
POST /manage/rag/rebuild?agentId=836774729814085
~~~

重建只纳入启用且 engageType=1（或历史数据中为空）的文档。

## 9. Copilot 调用历史管理

基础路径：/manage/copilot/history

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /manage/copilot/history/list | 调用历史分页查询 |
| GET | /manage/copilot/history/detail?uuid={uuid} | 查询 Redis 中的任务快照和轨迹 |

### 9.1 历史列表

~~~http
GET /manage/copilot/history/list?pageNum=1&pageSize=20&agentId=1&status=SUCCESS&userId=u-10086
~~~

支持筛选：

| 参数 | 类型 | 说明 |
|---|---|---|
| pageNum | int | 页码，默认 1 |
| pageSize | int | 页大小，默认 20，最大 200 |
| id | Long | 精确匹配 |
| uuid | String | 精确匹配 |
| agentId | Long | 精确匹配 |
| userId | String | 精确匹配 |
| status | String | SUCCESS、FAILED 或 CANCELLED |
| userMessage | String | 当前消息模糊匹配 |

历史记录字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 历史记录 ID |
| uuid | String | 任务 UUID |
| agentId | Long | Agent ID |
| agentName | String | Agent 名称快照 |
| userId | String | 请求中的调用方用户标识 |
| modelName | String | 实际调用模型 |
| userMessage | String | 仅当前消息 |
| finalReply | String | 固定格式 JSON 字符串 |
| status | String | SUCCESS、FAILED 或 CANCELLED |
| errorMsg | String | 失败原因 |
| stepCount | int | 步骤数量 |
| llmRound | int | ReAct LLM 轮数 |
| costMs | long | 总耗时，毫秒 |
| createdAt | Instant | 开始时间 |
| finishedAt | Instant | 完成时间 |

## 10. 错误码

### 10.1 通用和运行时错误

| code | msg | 场景 |
|---:|---|---|
| 0 | null | 成功 |
| 400 | 参数错误 | 参数缺失或格式校验失败 |
| 500 | 服务器状态不佳，请稍后再试 | 通用服务端错误 |
| 500004 | 业务异常：{详情} | Copilot 提交阶段的其他异常；异步运行异常请查看任务的 errorMsg |
| 500201 | 数据不存在 | 管理查询目标不存在 |
| 500203 | 自定义消息 | 业务中断 |
| 600 | Agent不存在或已禁用 | 运行任务使用了无效或停用 Agent |
| 601 | 任务不存在或已过期 | uuid 无效或 Redis 快照已过期 |
| 602 | 任务已结束，无需中断 | 取消已结束任务 |
| 700 | 大模型调用异常 | 大模型调用异常场景使用 |

### 10.2 管理写操作

| 操作 | 失败情况 |
|---|---|
| Agent 新增 | 参数错误、添加失败 |
| Agent 更新 | 参数错误、数据不存在、更新失败 |
| Agent 删除 | 数据不存在、删除失败 |
| 知识库新增 | 参数错误、URL 刷新失败、摘要或保存失败 |
| 知识库更新 | 参数错误、数据不存在、URL 刷新失败、更新失败 |
| 知识库删除 | 数据不存在、删除失败 |

## 11. 推荐轮询流程

~~~text
1. POST /copilot/chat（或其他任务接口）
2. 从 data.uuid 取得任务 UUID
3. 每 800ms 至 1s 调用 GET /copilot/result?uuid=...
4. status=RUNNING 时继续轮询
5. status=SUCCESS 时解析 finalReply 中的 JSON 字符串
6. status=FAILED 或 CANCELLED 时读取 errorMsg
~~~

chat 可能包含多轮 LLM_THINK、TOOL_CALL、TOOL_RESULT。summarize 和 generate 使用单次模型调用，成功时记录一次模型步骤和一次 FINAL_REPLY。
