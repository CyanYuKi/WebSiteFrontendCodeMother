# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

WebsiteMother is an AI-powered website generator. User describes a website in natural language, the backend runs a LangGraph4j workflow to collect requirements, gather assets, generate design concepts, and produce complete self-contained HTML files. The frontend is a Vue 3 chat-style UI with real-time SSE streaming.

## Build & Run

```bash
# Backend (Spring Boot, requires Java 21)
./mvnw spring-boot:run              # starts on :8080, active profile: local

# Frontend (Vite dev server)
cd frontend && npm run dev           # starts on :5173, proxies /api to :8080

# Frontend production build
cd frontend && npm run build
```

API keys are configured in `src/main/resources/application-local.yml` (gitignored). Required: `DASHSCOPE_API_KEY`, `DEEPSEEK_API_KEY`, `PEXELS_API_KEY`.

## Architecture

### LangGraph4j Workflow (Two-Phase)

The core is a state machine orchestrated by [LangGraph4j](https://github.com/bsorrentino/langgraph4j). `GraphConfig.java` defines two compiled graphs sharing `ProjectState` (extends `AgentState`):

**Phase 1 — `startGraph`**: `START → IntentAnalyzer → [IntentRouter] → ChecklistBuilder → END`
- `IntentRouter` branches: `chat` intent goes straight to END (returns a chat reply); `create` intent proceeds to checklist generation.

**Phase 2 — `resumeGraph`**: `START → AssetCollector → DesignConceptGenerator → HtmlGenerator → CodeReviewer → [ReviewRouter]`
- `ReviewRouter` branches: PASS → `SubPageGenerator → END`; FAIL with remaining retries → back to `HtmlGenerator`; max retries (2) reached → END.

Each node is a Spring `@Component` implementing `NodeAction<ProjectState>`, reading/writing fields on the shared state map. Edge routers implement `EdgeAction<ProjectState>`.

### Key State Fields (see `ProjectState.java`)

| Field | Set by | Purpose |
|-------|--------|---------|
| `currentInput` | Controller | Original user input |
| `intentType` / `chatReply` | IntentAnalyzer | chat vs create routing |
| `checklist` | ChecklistBuilder | JSON array of design questions |
| `userAnswers` | Controller | User's checklist responses |
| `assetsJson` | AssetCollector | Pexels images + AI logo URLs |
| `designConcept` / `designTokens` | DesignConceptGenerator | Color palette, typography, spacing |
| `htmlCode` | HtmlGenerator | The generated index.html |
| `reviewPassed` / `reviewFeedback` | CodeReviewer | Structural validation result |
| `pages` | SubPageGenerator | Map of filename → content for all pages |

### Model Strategy (`ChatModelService.java`)

Two model tiers:
- **FAST** (`qwen-turbo`): used for intent analysis only
- **SMART** (user-selectable, default `qwen3.6-plus`): used for all generation tasks. Supports `qwen3.6-max`, `deepseek-v4-pro`, `deepseek-v4-flush` via OpenAI-compatible API.

All prompts live in `PromptTemplates.java` as static constants/methods — no prompt text scattered in node classes.

### Frontend (`App.vue`)

Single-file Vue 3 SFC with Tailwind CSS 4. No router — the SPA manages state via a `step` ref (`chatting | checklist | generating | result`). SSE events from `/api/generate/resume-stream` drive:
- `stage` → updates the execution flow indicator
- `html_token` → streams index.html tokens into a live preview
- `page_token` → streams sub-page tokens (tagged with page name)
- `page_list` / `page_status` → multi-page generation progress
- `complete` → final result with preview URL, design concept, review status

The frontend proxies `/api` to `localhost:8080` via Vite config.

### API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/generate/start` | Phase 1: returns intent + checklist |
| POST | `/api/generate/resume-stream` | Phase 2: SSE streaming generation |
| POST | `/api/generate/resume` | Phase 2: sync (backward compat) |
| POST | `/api/generate/cancel` | Cancel in-progress generation |
| GET | `/api/preview/{projectId}/{file}` | Serve generated static files |
| GET | `/api/projects` | List saved projects |
| GET | `/api/projects/{id}/download` | Download generated HTML |

### Key External Services

- **Playwright**: headless browser for capturing project screenshots (`ScreenshotService.java`)
- **Pexels**: stock image search (`PexelsImageService.java`)
- **DashScope**: Qwen model access + logo generation via Tongyi Wanxiang (`LogoGenerationService.java`)
- **jsoup**: HTML parsing for code review structural validation (`CodeReviewer.java`)

## Important Patterns

- SubPageGenerator extracts nav links and `PAGES_PLAN` JSON comments from generated index.html to discover and generate sub-pages in parallel (up to 3 concurrent threads).
- CodeReviewer performs dual validation: fast regex-based structure checks (`<!DOCTYPE>`, tag balance, truncation detection) + jsoup HTML parser linting. Auto-fix attempts simple issues (missing closing tags, markdown artifacts).
- `SseEmitterStore` holds SSE emitters keyed by session ID, checked by nodes to push real-time events back to the frontend.
- Session state is in-memory (`ConcurrentHashMap` in GenerateController). No Redis/persistence for sessions — suitable for demo, not production.
