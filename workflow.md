# WebsiteMother 完整工作流

## 总览：前端作为编排器

每次用户输入都从同一个入口开始，前端根据后端返回的 `intentType` 决定下一步。

```mermaid
flowchart TD
    U(["用户输入"]) --> FE

    subgraph FE["前端 App.vue"]
        direction TB
        START["handleStart()"]
        DECIDE{"intentType ?"}
        CHAT["追加回复到对话<br/>流程结束"]
        QUERY["追加回复到对话<br/>流程结束"]
        CK["展示需求清单<br/>用户填写后点确认"]
        MOD["追加确认消息<br/>自动调用 handleModify()"]

        START -->|"POST /api/generate/start"| DECIDE
        DECIDE -->|"chat"| CHAT
        DECIDE -->|"query"| QUERY
        DECIDE -->|"create"| CK
        DECIDE -->|"modify"| MOD
    end

    subgraph BE1["后端 startGraph"]
        direction TB
        S0(["START"]) --> IA["intent_analyzer<br/>LLM: qwen-turbo<br/>识别意图"]
        IA --> IR{"IntentRouter"}
        IR -->|"chat / modify"| S_E1(["END<br/>返回 intentType + reply"])
        IR -->|"query"| AQ["app_query_responder<br/>LLM: SMART<br/>读项目上下文回答"]
        IR -->|"create"| CB["checklist_builder<br/>LLM: SMART<br/>生成设计问卷"]
        AQ --> S_E2(["END"])
        CB --> S_E3(["END"])
    end

    subgraph BE2["后端 resumeGraph<br/>POST /resume-stream (SSE)"]
        direction TB
        R0(["START"]) --> AC["asset_collector<br/>Pexels搜图 + Logo生成"]
        AC --> DC["design_concept_generator<br/>LLM: SMART<br/>配色/字体/布局"]
        DC --> HG["html_generator<br/>LLM: SMART<br/>生成 index.html"]
        HG --> CR1["code_reviewer<br/>正则 + jsoup 检查"]
        CR1 --> RR1{"ReviewRouter"}
        RR1 -->|"fail, retry<2"| HG
        RR1 -->|"retry>=2"| R_E1(["END"])
        RR1 -->|"pass"| SP1["sub_page_generator<br/>LLM: SMART<br/>生成所有子页面"]
        SP1 --> R_E2(["END"])
    end

    subgraph BE3["后端 modifyGraph<br/>POST /modify-stream (SSE)"]
        direction TB
        M0(["START"]) --> MP["modify_planner<br/>LLM: SMART<br/>结构摘要→修改计划"]
        MP --> HM["html_modifier<br/>LLM: SMART<br/>定向修改目标页面"]
        HM --> CR2["code_reviewer<br/>正则 + jsoup"]
        CR2 --> MR{"ModifyReviewRouter"}
        MR -->|"fail, retry<2"| HM
        MR -->|"retry>=2"| M_E1(["END"])
        MR -->|"pass"| NPD["new_page_detector<br/>无LLM<br/>导航链接 vs PAGES"]
        NPD --> NPR{"NewPageRouter"}
        NPR -->|"无新页面"| M_E2(["END"])
        NPR -->|"有新页面"| SP2["sub_page_generator<br/>LLM: SMART<br/>仅生成缺失页面"]
        SP2 --> M_E3(["END"])
    end

    DECIDE -.->|"create: 前端调 /resume-stream"| BE2
    MOD -.->|"modify: 前端调 /modify-stream"| BE3
```

## 三步决策链

```
用户输入 "帮我做一个电商网站"
  │
  ▼
① POST /api/generate/start  →  startGraph
  │  IntentAnalyzer 识别 → intentType = "create"
  │  返回: sessionId + checklist
  ▼
② 前端展示问卷，用户填写
  │
  ▼
③ POST /api/generate/resume-stream  →  resumeGraph
  │  AssetCollector → DesignConcept → HtmlGenerator
  │  → CodeReviewer ⇄ (重试) → SubPageGenerator
  │  SSE 流式返回 html_token / page_token / complete
  ▼
  网站生成完成
```

```
用户输入 "把背景色改成蓝色"（已有项目上下文）
  │
  ▼
① POST /api/generate/start  →  startGraph
  │  IntentAnalyzer 识别 → intentType = "modify"
  │  返回: sessionId + reply = "好的，我来修改..."
  ▼
② 前端自动调用 POST /api/generate/modify-stream  →  modifyGraph
  │  ModifyPlanner → HtmlModifier → CodeReviewer ⇄ (重试)
  │  → NewPageDetector → [SubPageGenerator]
  │  SSE 流式返回 modify_plan / html_token / complete
  ▼
  修改完成
```

```
用户输入 "这个网站有哪些页面？"（已有项目上下文）
  │
  ▼
① POST /api/generate/start  →  startGraph
  │  IntentAnalyzer 识别 → intentType = "query"
  │  → AppQueryResponder 读取项目上下文生成回答
  │  返回: sessionId + chatReply
  ▼
  前端直接显示回答，流程结束
```

## 三个后端图的职责

| 图 | 触发 API | 触发条件 | 核心能力 |
|---|---|---|---|
| **startGraph** | `POST /start` | 每次用户输入 | 意图识别 + 闲聊/查询/问卷 |
| **resumeGraph** | `POST /resume-stream` | intentType=create | 完整建站：素材→设计→首页→审查⇄重试→子页面 |
| **modifyGraph** | `POST /modify-stream` | intentType=modify | 定向修改：规划→改代码→审查⇄重试→检测新页面→生成 |
