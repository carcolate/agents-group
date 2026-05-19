# AgentGroup — Copilot Agent Management Platform

A copilot agent management platform built with Spring Boot 4 + MyBatis-Plus, featuring agent management, RAG knowledge base, and ReAct conversation engine.

---

## Tech Stack

| Category | Technology |
|----------|-----------|
| Framework | Spring Boot 4.0.6, Java 21 |
| ORM | MyBatis-Plus |
| Database | MySQL 8 + Redis |
| Connection Pool | Druid |
| Frontend | Vanilla HTML/CSS/JS |
| LLM | LangChain4j |

---

## Features

| Feature | Description |
|---------|-------------|
| **Agent Management** | CRUD and toggle enable/disable for multiple agents |
| **Copilot Chat** | Submit user messages, async ReAct loop (LLM think → tool call → result → final reply), frontend polling for decision trace visualization |
| **RAG Knowledge Base** | Document CRUD, auto chunking & vectorization, vector rebuild, drag-and-drop Markdown file upload |
| **Playground** | Real-time ReAct trace display (reverse order, auto-scroll) |

---

## Quick Start

```bash
# 1. Create database and run init script
mysql -u root -p < src/main/resources/db/init.sql

# 2. Configure local environment
# Edit src/main/resources/application-local.properties
# Fill in MySQL / Redis / LLM API credentials

# 3. Start
./mvnw spring-boot:run -Dspring.profiles.active=local

# 4. Open admin panel
# http://localhost:7892/manage-web/
```

---

## Project Structure

```
src/main/java/com/carcolate/agents/
├── config/          # Configuration (Redis, CORS, MyBatis-Plus, LangChain4j)
├── controller/      # API entry points
│   ├── manage/      # Admin backend API
│   └── CopilotController.java  # Chat submission & trace query
├── service/         # Business logic
│   ├── impl/
│   └── CopilotRunner.java      # ReAct orchestration engine
├── mapper/          # MyBatis Mapper
├── domain/          # Entities
├── dto/             # Data transfer objects
├── response/        # Unified response wrapper
├── tools/           # Tools (RAG search, etc.)
└── cfg/             # Enum utilities

src/main/resources/
├── static/manage-web/   # Frontend pages
├── mapper/              # MyBatis XML mappings
└── db/init.sql          # Database init script
```

---

## API Docs

See [docs/copilot-api.md](docs/copilot-api.md)
