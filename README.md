# AgentGroup — Copilot Agent 管理平台

基于 Spring Boot 4 + MyBatis-Plus 的智能客服 Agent 管理后台，提供 Agent 管理、RAG 知识库与 ReAct 对话引擎。

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 4.0.6, Java 21 |
| ORM | MyBatis-Plus |
| 数据库 | MySQL 8 + Redis |
| 连接池 | Druid |
| 前端 | 原生 HTML/CSS/JS |
| LLM | LangChain4j |

## 功能模块

- **Agent 管理** — 多 Agent 的增删改查与启用/禁用
- **Copilot 对话** — 提交用户消息，异步 ReAct 循环（LLM 思考 → 工具调用 → 结果 → 最终回复），前端轮询展示决策轨迹
- **RAG 知识库** — 文档增删改查、自动切片向量化、向量重建，支持 Markdown 文件拖拽上传
- **在线对话 Playground** — 实时查看 ReAct 决策轨迹（倒叙自动滚动）

## 快速启动

```bash
# 1. 创建数据库并执行初始化脚本
mysql -u root -p < src/main/resources/db/init.sql

# 2. 配置本地环境
# 编辑 src/main/resources/application-local.properties
# 填写 MySQL / Redis / LLM API 地址与密钥

# 3. 启动
./mvnw spring-boot:run -Dspring.profiles.active=local

# 4. 访问后台
# http://localhost:7892/manage-web/
```

## 项目结构

```
src/main/java/com/carcolate/agents/
├── config/          # 配置层（Redis、CORS、MyBatis-Plus、LangChain4j）
├── controller/      # API 入口
│   ├── manage/      # 管理后台 API
│   └── CopilotController.java  # 对话提交与轨迹查询
├── service/         # 业务逻辑
│   ├── impl/
│   └── CopilotRunner.java      # ReAct 编排引擎
├── mapper/          # MyBatis Mapper
├── domain/          # 实体类
├── dto/             # 数据传输对象
├── response/        # 统一响应封装
├── tools/           # 工具（RAG 搜索等）
└── cfg/             # 枚举工具类

src/main/resources/
├── static/manage-web/   # 前端页面
├── mapper/              # MyBatis XML 映射
└── db/init.sql          # 数据库初始化脚本
```

## API 文档

详见 [docs/copilot-api.md](docs/copilot-api.md)
