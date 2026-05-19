# AgentGroup — Copilot Agent 管理平台

> 基于 Spring Boot 4 + MyBatis-Plus 的智能客服 Agent 管理后台，提供 Agent 管理、RAG 知识库与 ReAct 对话引擎。
> A copilot agent management platform built with Spring Boot 4 + MyBatis-Plus, featuring agent management, RAG knowledge base, and ReAct conversation engine.

---

## 技术栈 / Tech Stack

| Category | Technology |
|----------|-----------|
| 框架 / Framework | Spring Boot 4.0.6, Java 21 |
| ORM | MyBatis-Plus |
| 数据库 / Database | MySQL 8 + Redis |
| 连接池 / Pool | Druid |
| 前端 / Frontend | 原生 HTML/CSS/JS |
| LLM | LangChain4j |

---

## 功能模块 / Features

| 功能 | 说明 |
|------|------|
| **Agent 管理** | 多 Agent 的增删改查与启用/禁用 — CRUD and toggle enable/disable for multiple agents |
| **Copilot 对话** | 提交用户消息，异步 ReAct 循环（LLM 思考 → 工具调用 → 结果 → 最终回复），前端轮询展示决策轨迹 — Submit user messages, async ReAct loop with frontend polling for decision trace visualization |
| **RAG 知识库** | 文档增删改查、自动切片向量化、向量重建，支持 Markdown 文件拖拽上传 — Document CRUD, auto chunking & vectorization, vector rebuild, drag-and-drop Markdown upload |
| **Playground** | 实时查看 ReAct 决策轨迹（倒叙自动滚动）— Real-time ReAct trace display (reverse order, auto-scroll) |

---

## 快速启动 / Quick Start

```bash
# 1. 创建数据库并执行初始化脚本
mysql -u root -p < src/main/resources/db/init.sql

# 2. 配置本地环境
# 编辑 src/main/resources/application-local.properties
# 填写 MySQL / Redis / LLM API 地址与密钥
# Edit application-local.properties with MySQL/Redis/LLM API credentials

# 3. 启动 / Start
./mvnw spring-boot:run -Dspring.profiles.active=local

# 4. 访问后台 / Open admin panel
# http://localhost:7892/manage-web/
```

---

## 项目结构 / Project Structure

```
src/main/java/com/carcolate/agents/
├── config/          # 配置层 (Redis, CORS, MyBatis-Plus, LangChain4j / Configuration)
├── controller/      # API 入口 (API entry points)
│   ├── manage/      # 管理后台 API (Admin backend API)
│   └── CopilotController.java  # 对话提交与轨迹查询
├── service/         # 业务逻辑 (Business logic)
│   ├── impl/
│   └── CopilotRunner.java      # ReAct 编排引擎 (ReAct orchestration engine)
├── mapper/          # MyBatis Mapper
├── domain/          # 实体类 (Entities)
├── dto/             # 数据传输对象 (DTOs)
├── response/        # 统一响应封装 (Unified response wrapper)
├── tools/           # 工具 (Tools, e.g. RAG search)
└── cfg/             # 枚举工具类 (Enum utilities)

src/main/resources/
├── static/manage-web/   # 前端页面 (Frontend pages)
├── mapper/              # MyBatis XML 映射 (XML mappings)
└── db/init.sql          # 数据库初始化脚本 (DB init script)
```

---

## API 文档 / API Docs

详见 [docs/copilot-api.md](docs/copilot-api.md)
