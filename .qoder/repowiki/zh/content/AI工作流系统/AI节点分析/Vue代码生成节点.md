# Vue代码生成节点

<cite>
**本文档引用的文件**
- [VueGenerator.java](file://src/main/java/com/example/websitemother/node/VueGenerator.java)
- [CodeQualityScorer.java](file://src/main/java/com/example/websitemother/service/CodeQualityScorer.java)
- [AssetCollector.java](file://src/main/java/com/example/websitemother/node/AssetCollector.java)
- [IntentAnalyzer.java](file://src/main/java/com/example/websitemother/node/IntentAnalyzer.java)
- [CodeReviewer.java](file://src/main/java/com/example/websitemother/node/CodeReviewer.java)
- [ChatModelService.java](file://src/main/java/com/example/websitemother/service/ChatModelService.java)
- [PromptTemplates.java](file://src/main/java/com/example/websitemother/prompt/PromptTemplates.java)
- [ProjectState.java](file://src/main/java/com/example/websitemother/state/ProjectState.java)
- [GraphWorkflowService.java](file://src/main/java/com/example/websitemother/service/GraphWorkflowService.java)
- [GraphConfig.java](file://src/main/java/com/example/websitemother/config/GraphConfig.java)
- [ReviewRouter.java](file://src/main/java/com/example/websitemother/edge/ReviewRouter.java)
- [GenerateController.java](file://src/main/java/com/example/websitemother/controller/GenerateController.java)
- [App.vue](file://frontend/src/App.vue)
- [application.yml](file://src/main/resources/application.yml)
</cite>

## 更新摘要
**变更内容**
- 新增智能重试机制和分块增量修改支持
- 添加JavaScript保留字自动修复功能
- 集成代码质量评分系统
- 更新VueGenerator核心算法实现
- 增强代码审查和质量保证机制

## 目录
1. [简介](#简介)
2. [项目结构](#项目结构)
3. [核心组件](#核心组件)
4. [架构概览](#架构概览)
5. [详细组件分析](#详细组件分析)
6. [依赖关系分析](#依赖关系分析)
7. [性能考虑](#性能考虑)
8. [故障排除指南](#故障排除指南)
9. [结论](#结论)
10. [附录](#附录)

## 简介
本文件为VueGenerator Vue代码生成节点的全面技术文档。该节点位于LangGraph工作流的第四阶段，负责将用户需求与收集到的素材整合后，通过大语言模型生成完整的单文件Vue 3组件代码。**最新版本**增强了智能重试机制、块级增量修改支持、JavaScript保留字修复和代码质量评分系统，显著提升了代码生成的准确性和可靠性。

## 项目结构
系统采用前后端分离架构，后端使用Spring Boot + LangGraph4j构建状态图工作流，前端使用Vue 3 + Vite提供交互界面。核心流程分为两个阶段：
- 第一阶段：意图分析 → 清单生成（Human-in-the-loop暂停）
- 第二阶段：素材收集 → Vue代码生成 → 代码审查（条件循环）

```mermaid
graph TB
subgraph "前端"
FE_App["App.vue<br/>用户交互界面"]
end
subgraph "后端"
API["GenerateController<br/>REST接口"]
WF["GraphWorkflowService<br/>工作流执行"]
CFG["GraphConfig<br/>状态图配置"]
subgraph "工作流节点"
IA["IntentAnalyzer<br/>意图分析"]
CB["ChecklistBuilder<br/>清单生成"]
AC["AssetCollector<br/>素材收集"]
VG["VueGenerator<br/>Vue代码生成<br/>智能重试+增量修改"]
CR["CodeReviewer<br/>代码审查<br/>快速结构检查"]
subgraph "质量保证"
CQS["CodeQualityScorer<br/>代码质量评分"]
end
end
subgraph "支持服务"
CSM["ChatModelService<br/>LLM封装"]
PT["PromptTemplates<br/>提示词模板"]
PS["ProjectState<br/>状态管理"]
RR["ReviewRouter<br/>审查路由<br/>最多3次重试"]
end
end
FE_App --> API
API --> WF
WF --> CFG
CFG --> IA
CFG --> CB
CFG --> AC
CFG --> VG
CFG --> CR
CFG --> RR
IA --> CSM
CB --> CSM
AC --> CSM
VG --> CSM
VG --> CQS
CR --> CSM
CR --> PS
IA --> PT
CB --> PT
VG --> PT
CR --> PT
IA --> PS
CB --> PS
AC --> PS
VG --> PS
RR --> PS
```

**图表来源**
- [GraphConfig.java:52-96](file://src/main/java/com/example/websitemother/config/GraphConfig.java#L52-L96)
- [GenerateController.java:33-84](file://src/main/java/com/example/websitemother/controller/GenerateController.java#L33-L84)
- [ChatModelService.java:33-49](file://src/main/java/com/example/websitemother/service/ChatModelService.java#L33-L49)

**章节来源**
- [GraphConfig.java:52-96](file://src/main/java/com/example/websitemother/config/GraphConfig.java#L52-L96)
- [GenerateController.java:33-84](file://src/main/java/com/example/websitemother/controller/GenerateController.java#L33-L84)

## 核心组件
VueGenerator作为工作流的第四节点，承担着将用户需求和素材转化为完整Vue代码的关键职责。**最新版本**具备以下增强功能：

### 主要职责
- 整合用户原始需求和补充信息
- 调用大语言模型生成Vue代码
- **智能重试机制**：支持分块增量修改修复
- **JavaScript保留字修复**：自动修正常见语法错误
- **代码质量评分**：提供量化质量评估
- 处理Markdown代码块标记清理
- 返回标准化的代码结果

### 关键特性
- **需求整合**：将currentInput和userAnswers合并为完整的业务需求描述
- **LLM集成**：通过ChatModelService调用DashScope Qwen模型
- **智能重试**：当代码审查失败时，支持精确的块级修改
- **代码清理**：自动移除```vue等代码块包装标记
- **保留字修复**：自动修正function等JavaScript保留字问题
- **质量评分**：提供结构完整性、视觉丰富度等多维度评分
- **状态管理**：通过ProjectState传递和接收数据

**章节来源**
- [VueGenerator.java:19-87](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L19-L87)
- [ProjectState.java:15-24](file://src/main/java/com/example/websitemother/state/ProjectState.java#L15-L24)

## 架构概览
VueGenerator在整个系统架构中处于核心位置，连接着前端交互、工作流控制和LLM服务层。**新增的质量保证体系**确保代码生成的可靠性和准确性。

```mermaid
sequenceDiagram
participant FE as "前端App.vue"
participant API as "GenerateController"
participant WF as "GraphWorkflowService"
participant CFG as "GraphConfig"
participant VG as "VueGenerator<br/>智能重试+增量修改"
participant CSM as "ChatModelService"
participant LLM as "DashScope Qwen"
participant CQS as "CodeQualityScorer"
participant CR as "CodeReviewer<br/>快速结构检查"
FE->>API : POST /api/generate/start
API->>WF : start(input)
WF->>CFG : 执行startGraph
CFG-->>FE : 返回意图/清单
FE->>API : POST /api/generate/resume
API->>WF : resume(state)
WF->>CFG : 执行resumeGraph
CFG->>VG : 执行VueGenerator
VG->>CSM : chat(systemPrompt, userPrompt)
CSM->>LLM : 发送消息
LLM-->>CSM : 返回响应
CSM-->>VG : 清理后的代码
VG->>CQS : score(finalCode)
CQS-->>VG : 质量评分报告
VG-->>CFG : 返回VUE_CODE + 质量信息
CFG->>CR : 执行CodeReviewer
CR->>CR : 快速结构检查
CR-->>CFG : 返回审查结果
CFG-->>FE : 返回最终状态
```

**图表来源**
- [GenerateController.java:33-84](file://src/main/java/com/example/websitemother/controller/GenerateController.java#L33-L84)
- [GraphWorkflowService.java:31-57](file://src/main/java/com/example/websitemother/service/GraphWorkflowService.java#L31-L57)
- [GraphConfig.java:78-96](file://src/main/java/com/example/websitemother/config/GraphConfig.java#L78-L96)
- [VueGenerator.java:42-87](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L42-L87)
- [CodeReviewer.java:25-65](file://src/main/java/com/example/websitemother/node/CodeReviewer.java#L25-L65)

## 详细组件分析

### VueGenerator组件分析
VueGenerator实现了NodeAction接口，是工作流的核心节点。**最新版本**引入了多项重大增强功能。

#### 类结构设计
```mermaid
classDiagram
class VueGenerator {
-ChatModelService chatModelService
-CodeQualityScorer qualityScorer
-private static final Pattern BLOCK_PATTERN
-private static final Pattern TEMPLATE_BLOCK
-private static final Pattern SCRIPT_BLOCK
-private static final Pattern STYLE_BLOCK
+apply(state) Map~String,Object~
-private tryPatchBlock(originalCode, llmResponse) String
-private stripMarkdown(text) String
-private fixReservedWords(code) String
}
class NodeAction {
<<interface>>
+apply(state) Map~String,Object~
}
class ProjectState {
+String currentInput()
+String assetsJson()
+String reviewFeedback()
+String vueCode()
+Map~String,String~ userAnswers()
+int retryCount()
}
class ChatModelService {
+String chat(systemPrompt, userPrompt)
+String chat(userPrompt)
}
class CodeQualityScorer {
+score(vueCode) QualityReport
}
VueGenerator ..|> NodeAction
VueGenerator --> ChatModelService : "依赖"
VueGenerator --> CodeQualityScorer : "依赖"
VueGenerator --> ProjectState : "读取状态"
```

**图表来源**
- [VueGenerator.java:19-177](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L19-L177)
- [ProjectState.java:30-76](file://src/main/java/com/example/websitemother/state/ProjectState.java#L30-L76)
- [ChatModelService.java:21-57](file://src/main/java/com/example/websitemother/service/ChatModelService.java#L21-L57)
- [CodeQualityScorer.java:16-101](file://src/main/java/com/example/websitemother/service/CodeQualityScorer.java#L16-L101)

#### 核心处理流程
```mermaid
flowchart TD
Start([进入VueGenerator.apply]) --> ReadState["读取ProjectState状态"]
ReadState --> BuildReq["构建完整需求描述"]
BuildReq --> CallLLM["调用ChatModelService.chat"]
CallLLM --> CleanCode["清理Markdown代码块标记"]
CleanCode --> CheckRetry{"是否重试?"}
CheckRetry --> |是| TryPatch["尝试分块增量修改"]
TryPatch --> PatchSuccess{"增量修改成功?"}
PatchSuccess --> |是| UsePatched["使用增量修改结果"]
PatchSuccess --> |否| Fallback["回退到完整输出"]
CheckRetry --> |否| UseFull["使用完整输出"]
UsePatched --> FixReserved["自动修复JavaScript保留字"]
UsePatched --> QualityScore["代码质量评分"]
Fallback --> FixReserved
UseFull --> FixReserved
FixReserved --> QualityScore
QualityScore --> ReturnResult["返回VUE_CODE + 质量信息"]
subgraph "需求构建过程"
BuildReq --> AddOriginal["添加原始需求"]
BuildReq --> AddAnswers["遍历userAnswers"]
AddAnswers --> FormatAnswer["格式化问答对"]
end
subgraph "代码清理过程"
CleanCode --> CheckStart["检查起始标记"]
CheckStart --> RemoveStart["移除
```vue/```"]
RemoveStart --> CheckEnd["检查结束标记"]
CheckEnd --> RemoveEnd["移除```"]
RemoveEnd --> TrimCode["去除首尾空白"]
end
subgraph "智能重试机制"
TryPatch --> ParseBlock["解析BLOCK标记"]
ParseBlock --> ExtractCode["提取CODE内容"]
ExtractCode --> ReplaceBlock["替换对应代码块"]
ReplaceBlock --> LogPatch["记录增量修改"]
end
```

**图表来源**
- [VueGenerator.java:36-87](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L36-L87)

#### 关键实现细节
1. **智能重试机制**：检测重试场景，支持分块增量修改而非完整重生成
2. **块级增量修改**：通过BLOCK和CODE标记实现精确的代码块替换
3. **JavaScript保留字修复**：自动修正function等保留字作为变量名的问题
4. **代码质量评分**：提供结构完整性、视觉丰富度等多维度质量评估
5. **需求整合策略**：将原始需求和用户补充信息组合成结构化文本
6. **LLM调用参数**：使用预定义的系统提示词和用户提示词模板
7. **代码清理机制**：智能识别并移除Markdown代码块包装
8. **错误处理**：通过日志记录和异常传播确保流程稳定性

**章节来源**
- [VueGenerator.java:19-177](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L19-L177)
- [PromptTemplates.java:80-111](file://src/main/java/com/example/websitemother/prompt/PromptTemplates.java#L80-L111)

### 代码质量评分系统
**新增功能**：CodeQualityScorer提供全面的代码质量评估，基于规则化算法而非LLM调用。

#### 质量评分维度
```mermaid
graph TB
subgraph "质量评分体系"
Structure["结构完整性<br/>25分"] --> Template["template标签"]
Structure --> Script["script标签"]
Structure --> Style["style标签"]
Structure --> TemplateClose["template闭合"]
Structure --> ScriptClose["script闭合"]
Structure --> StyleClose["style闭合"]
Sections["页面区块丰富度<br/>30分"] --> Header["头部导航"]
Sections --> Hero["Hero区域"]
Sections --> Features["功能特性"]
Sections --> About["关于我们"]
Sections --> Stats["统计数据"]
Sections --> Gallery["作品展示"]
Sections --> Testimonials["客户评价"]
Sections --> Contact["联系方式"]
Sections --> Footer["页脚"]
Sections --> Team["团队信息"]
Visual["视觉丰富度<br/>20分"] --> Gradients["渐变背景"]
Visual --> Shadows["阴影效果"]
Visual --> Rounded["圆角设计"]
Visual --> Objects["对象覆盖"]
Visual --> TextSizes["大字体标题"]
Visual --> BoldText["粗体文字"]
Interaction["交互与动画<br/>15分"] --> Hover["悬停效果"]
Interaction --> Transitions["过渡动画"]
Interaction --> Transforms["变换效果"]
Responsive["响应式设计<br/>10分"] --> MD["中等屏幕"]
Responsive --> LG["大屏幕"]
Responsive --> SM["小屏幕"]
Complexity["代码长度与复杂度<br/>10分"] --> Length["代码规模"]
end
```

**图表来源**
- [CodeQualityScorer.java:18-90](file://src/main/java/com/example/websitemother/service/CodeQualityScorer.java#L18-L90)

#### 评分算法实现
```mermaid
flowchart TD
StartQC([开始质量评分]) --> CheckEmpty{"代码为空?"}
CheckEmpty --> |是| ReturnZero["返回0分"]
CheckEmpty --> |否| Lower["转换为小写"]
Lower --> Structure["结构完整性评分<br/>25分"]
Structure --> Template["template标签+5分"]
Structure --> Script["script标签+5分"]
Structure --> Style["style标签+5分"]
Structure --> TemplateClose["闭合标签+5分"]
Structure --> ScriptClose["闭合标签+5分"]
Structure --> StyleClose["闭合标签+5分"]
Structure --> Section["页面区块丰富度<br/>30分"]
Section --> HeroCheck["Hero区域检测"]
Section --> FeatureCheck["功能特性检测"]
Section --> OtherChecks["其他区块检测"]
Section --> Visual["视觉丰富度<br/>20分"]
Visual --> Gradient["渐变背景+5分"]
Visual --> Shadow["阴影效果+3分"]
Visual --> Rounded["圆角设计+3分"]
Visual --> ObjectCover["对象覆盖+3分"]
Visual --> TextSize["大字体+3分"]
Visual --> Bold["粗体文字+3分"]
Visual --> Interaction["交互与动画<br/>15分"]
Interaction --> Hover["悬停效果+5分"]
Interaction --> Transition["过渡动画+5分"]
Interaction --> Transform["变换效果+5分"]
Interaction --> Responsive["响应式设计<br/>10分"]
Responsive --> MD["中等屏幕+4分"]
Responsive --> LG["大屏幕+3分"]
Responsive --> SM["小屏幕+3分"]
Responsive --> Complexity["代码长度与复杂度<br/>10分"]
Complexity --> Length["代码规模评分"]
Length --> Long[">8000字符-2分"]
Length --> Medium[">6000字符-3分"]
Length --> Short[">4500字符-3分"]
Length --> VeryLong[">3000字符-2分"]
Complexity --> Level["等级计算"]
Level --> Excellent[">=85分 优秀"]
Level --> Good[">=65分 良好"]
Level --> Average[">=45分 一般"]
Level --> Improve["<45分 需改进"]
ReturnResult["返回总分和详细报告"]
```

**图表来源**
- [CodeQualityScorer.java:18-90](file://src/main/java/com/example/websitemother/service/CodeQualityScorer.java#L18-L90)

**章节来源**
- [CodeQualityScorer.java:16-101](file://src/main/java/com/example/websitemother/service/CodeQualityScorer.java#L16-L101)

### 智能重试机制与块级增量修改
**新增功能**：当代码审查失败时，VueGenerator支持精确的块级修改而非完整重生成。

#### 增量修改流程
```mermaid
flowchart TD
StartRetry([代码审查失败]) --> CheckRetry{"是否重试?"}
CheckRetry --> |是| ParseBlock["解析BLOCK标记"]
ParseBlock --> BlockFound{"找到BLOCK标记?"}
BlockFound --> |是| ExtractCode["提取CODE内容"]
BlockFound --> |否| Fallback["回退到完整输出"]
ExtractCode --> StripMarkdown["清理Markdown标记"]
StripMarkdown --> FindBlock["查找原代码中的对应块"]
FindBlock --> BlockFound2{"找到目标块?"}
BlockFound2 --> |是| ReplaceBlock["替换代码块"]
BlockFound2 --> |否| Fallback
ReplaceBlock --> Success["增量修改成功"]
Success --> FixReserved["自动修复JavaScript保留字"]
FixReserved --> QualityScore["代码质量评分"]
QualityScore --> ReturnResult["返回更新后的代码"]
Fallback --> ReturnFull["返回完整输出"]
```

**图表来源**
- [VueGenerator.java:63-140](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L63-L140)

#### 增量修改算法
```mermaid
sequenceDiagram
participant VG as "VueGenerator"
participant LLM as "LLM响应"
participant Parser as "BLOCK解析器"
participant Replacer as "代码块替换器"
VG->>LLM : 请求修复特定代码块
LLM-->>VG : 返回"BLOCK : template|script|style\nCODE : \n[新代码]"
VG->>Parser : 解析BLOCK标记
Parser-->>VG : 返回块类型和代码内容
VG->>Replacer : 查找原代码中的对应块
Replacer-->>VG : 返回替换结果
VG-->>VG : 记录增量修改日志
```

**图表来源**
- [VueGenerator.java:93-140](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L93-L140)

**章节来源**
- [VueGenerator.java:63-140](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L63-L140)
- [PromptTemplates.java:95-99](file://src/main/java/com/example/websitemother/prompt/PromptTemplates.java#L95-L99)

### JavaScript保留字修复
**新增功能**：自动修复JavaScript保留字作为变量名的常见问题。

#### 保留字修复机制
```mermaid
flowchart TD
StartFix([开始保留字修复]) --> CheckFunction{"检测function保留字"}
CheckFunction --> |发现| FixFunction["替换function为func"]
CheckFunction --> |未发现| CheckOther["检查其他保留字"]
FixFunction --> ReplaceRefs["替换模板引用"]
ReplaceRefs --> LogFix["记录修复日志"]
CheckOther --> CheckClass{"检测class保留字"}
CheckClass --> |发现| FixClass["替换class为cls"]
CheckClass --> |未发现| Complete["修复完成"]
FixClass --> ReplaceRefs2["替换模板引用"]
ReplaceRefs2 --> LogFix2["记录修复日志"]
Complete --> EndFix([修复完成])
```

**图表来源**
- [VueGenerator.java:162-175](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L162-L175)

#### 修复规则实现
```mermaid
graph TB
subgraph "function保留字修复"
FuncDetect["检测v-for=\"(function, 或 v-for='(function,'"]
FuncReplace["替换为v-for=\"(func,"]
FuncReplace2["替换为v-for='(func,'"]
FuncRefs["替换模板引用{{ function."]
FuncRefs2["替换属性绑定:function."]
FuncLog["记录修复日志"]
end
```

**图表来源**
- [VueGenerator.java:162-175](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L162-L175)

**章节来源**
- [VueGenerator.java:162-175](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L162-L175)

### 资源收集与处理
虽然VueGenerator不直接处理图片资源，但其上游节点AssetCollector负责生成占位图片URL，为代码生成提供素材支持。

#### 素材收集流程
```mermaid
flowchart TD
StartAC([进入AssetCollector]) --> ReadAnswers["读取userAnswers"]
ReadAnswers --> InitAssets["初始化assets映射"]
InitAssets --> LoopAnswers["遍历每个问答项"]
LoopAnswers --> CheckValue{"值是否为空?"}
CheckValue --> |是| NextItem["跳过该项"]
CheckValue --> |否| ExtractKey["提取关键词"]
ExtractKey --> BuildURL["构建Picsum URL"]
BuildURL --> AddAsset["添加到assets映射"]
AddAsset --> NextItem
NextItem --> MoreItems{"还有更多项?"}
MoreItems --> |是| LoopAnswers
MoreItems --> |否| EnsureHero["确保至少有一个hero"]
EnsureHero --> Serialize["序列化为JSON"]
Serialize --> ReturnAC["返回ASSETS_JSON"]
```

**图表来源**
- [AssetCollector.java:23-58](file://src/main/java/com/example/websitemother/node/AssetCollector.java#L23-L58)

**章节来源**
- [AssetCollector.java:23-89](file://src/main/java/com/example/websitemother/node/AssetCollector.java#L23-L89)

### LLM集成与提示词系统
系统通过ChatModelService封装了对DashScope Qwen模型的调用，PromptTemplates集中管理所有提示词模板。**新增了智能重试的提示词格式**。

#### 提示词模板结构
```mermaid
classDiagram
class PromptTemplates {
<<final>>
+String INTENT_ANALYZER_SYSTEM
+String CHECKLIST_BUILDER_SYSTEM
+String VUE_GENERATOR_SYSTEM
+String CODE_REVIEWER_SYSTEM
+intentAnalyzerUser(input) String
+checklistBuilderUser(input) String
+vueGeneratorUser(requirement, assets, feedback, previousVueCode) String
+codeReviewerUser(code) String
}
class ChatModelService {
-QwenChatModel qwenChatModel
+chat(systemPrompt, userPrompt) String
+chat(userPrompt) String
}
PromptTemplates --> ChatModelService : "提供参数"
ChatModelService --> PromptTemplates : "使用模板"
```

**图表来源**
- [PromptTemplates.java:7-92](file://src/main/java/com/example/websitemother/prompt/PromptTemplates.java#L7-L92)
- [ChatModelService.java:21-57](file://src/main/java/com/example/websitemother/service/ChatModelService.java#L21-L57)

#### LLM调用流程
```mermaid
sequenceDiagram
participant VG as "VueGenerator"
participant PT as "PromptTemplates"
participant CSM as "ChatModelService"
participant LLM as "Qwen模型"
VG->>PT : 获取VUE_GENERATOR_SYSTEM
VG->>PT : 生成vueGeneratorUser参数
PT-->>VG : 返回系统提示词和用户提示词
VG->>CSM : chat(systemPrompt, userPrompt)
CSM->>LLM : 发送SystemMessage + UserMessage
LLM-->>CSM : 返回AI响应
CSM-->>VG : 返回清理后的代码
VG->>VG : 进行代码块标记清理
VG->>VG : 检查是否需要智能重试
```

**图表来源**
- [VueGenerator.java:55-76](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L55-L76)
- [ChatModelService.java:33-49](file://src/main/java/com/example/websitemother/service/ChatModelService.java#L33-L49)

**章节来源**
- [PromptTemplates.java:80-111](file://src/main/java/com/example/websitemother/prompt/PromptTemplates.java#L80-L111)
- [ChatModelService.java:33-49](file://src/main/java/com/example/websitemother/service/ChatModelService.java#L33-L49)

### 代码审查与质量保证
**增强功能**：CodeReviewer节点现在集成了快速结构检查和自动修复能力，配合VueGenerator的质量评分系统。

#### 审查流程设计
```mermaid
flowchart TD
StartCR([进入CodeReviewer]) --> ReadCode["读取vueCode和retryCount"]
ReadCode --> FastCheck["快速结构检查"]
FastCheck --> CheckPass{"检查通过?"}
CheckPass --> |是| AutoFix["尝试自动修复"]
CheckPass --> |否| AutoFix
AutoFix --> ReCheck["重新检查"]
ReCheck --> CheckPass2{"修复后通过?"}
CheckPass2 --> |是| SetPassed["设置reviewPassed=true"]
CheckPass2 --> |否| IncRetry["增加retryCount"]
SetPassed --> ReturnCR["返回审查结果"]
IncRetry --> ReturnCR
```

**图表来源**
- [CodeReviewer.java:25-65](file://src/main/java/com/example/websitemother/node/CodeReviewer.java#L25-L65)

#### 快速结构检查算法
```mermaid
flowchart TD
StartFast([快速结构检查]) --> CheckEmpty{"代码为空?"}
CheckEmpty --> |是| ReturnEmpty["返回'代码为空'"]
CheckEmpty --> |否| Lower["转换为小写"]
Lower --> CheckTags["检查基本标签"]
CheckTags --> TagsOK{"标签完整?"}
TagsOK --> |否| ReturnTags["返回标签缺失错误"]
TagsOK --> CheckClosure["检查标签闭合"]
CheckClosure --> ClosureOK{"闭合正确?"}
ClosureOK --> |否| ReturnClosure["返回闭合错误"]
ClosureOK --> CheckCounts["检查标签数量匹配"]
CheckCounts --> CountsOK{"数量匹配?"}
ClosureOK --> |否| ReturnCounts["返回数量不匹配错误"]
CountsOK --> CheckSetup["检查script setup语法"]
CheckSetup --> SetupOK{"使用setup语法?"}
SetupOK --> |否| ReturnSetup["返回未使用setup语法错误"]
SetupOK --> CheckMarkdown["检查残留markdown标记"]
CheckMarkdown --> MarkdownOK{"无残留标记?"}
MarkdownOK --> |否| ReturnMarkdown["返回残留标记错误"]
MarkdownOK --> CheckTemplate["提取template内容"]
CheckTemplate --> TemplateOK{"template检查通过?"}
TemplateOK --> |否| ReturnTemplate["返回template错误"]
TemplateOK --> ReturnOK["返回null检查通过"]
```

**图表来源**
- [CodeReviewer.java:72-128](file://src/main/java/com/example/websitemother/node/CodeReviewer.java#L72-L128)

**章节来源**
- [CodeReviewer.java:25-199](file://src/main/java/com/example/websitemother/node/CodeReviewer.java#L25-L199)

## 依赖关系分析
系统采用松耦合设计，各组件通过接口和状态共享实现解耦。**新增的CodeQualityScorer**为VueGenerator提供了独立的质量评估能力。

```mermaid
graph TB
subgraph "核心依赖"
VG["VueGenerator<br/>智能重试+增量修改"] --> CSM["ChatModelService"]
VG --> PT["PromptTemplates"]
VG --> PS["ProjectState"]
VG --> CQS["CodeQualityScorer<br/>独立质量评估"]
AC["AssetCollector"] --> PS
CR["CodeReviewer<br/>快速结构检查"] --> PS
CR --> CSM
IA["IntentAnalyzer"] --> PS
CB["ChecklistBuilder"] --> PS
CSM --> LLM["DashScope Qwen"]
end
subgraph "工作流集成"
CFG["GraphConfig"] --> IA
CFG --> CB
CFG --> AC
CFG --> VG
CFG --> CR
CFG --> RR["ReviewRouter<br/>最多3次重试"]
WF["GraphWorkflowService"] --> CFG
API["GenerateController"] --> WF
end
subgraph "前端集成"
FE["App.vue"] --> API
API --> PS
end
```

**图表来源**
- [GraphConfig.java:32-45](file://src/main/java/com/example/websitemother/config/GraphConfig.java#L32-L45)
- [GraphWorkflowService.java:19-23](file://src/main/java/com/example/websitemother/service/GraphWorkflowService.java#L19-L23)
- [GenerateController.java:24-25](file://src/main/java/com/example/websitemother/controller/GenerateController.java#L24-L25)

### 组件耦合度分析
- **低耦合设计**：各节点通过ProjectState进行数据交换，减少直接依赖
- **接口隔离**：NodeAction接口统一了节点行为规范
- **服务封装**：ChatModelService封装了LLM调用细节
- **模板管理**：PromptTemplates集中管理提示词，便于维护和优化
- **独立质量评估**：CodeQualityScorer提供独立的服务，不依赖LLM调用

**章节来源**
- [GraphConfig.java:52-96](file://src/main/java/com/example/websitemother/config/GraphConfig.java#L52-L96)
- [ProjectState.java:13-77](file://src/main/java/com/example/websitemother/state/ProjectState.java#L13-L77)

## 性能考虑
系统在多个层面进行了性能优化设计，**新增的质量评分系统完全基于规则算法，无需额外的LLM调用**。

### 异步处理
- 使用node_async和edge_async实现异步节点和边处理
- 避免阻塞操作影响整体工作流性能

### 缓存策略
- 前端使用内存会话存储（演示用途）
- 生产环境建议使用Redis等分布式缓存

### 错误处理
- 统一日志记录，便于性能监控和问题定位
- 异常向上抛出，确保工作流完整性

### LLM调用优化
- 参数化提示词模板，减少重复计算
- 结果清理采用高效字符串处理
- **智能重试机制避免不必要的完整重生成**

### 质量评分优化
- **零额外token消耗**：完全基于规则算法的质量评估
- **快速执行**：字符串匹配和正则表达式操作
- **可扩展性**：易于添加新的评分维度

## 故障排除指南
针对VueGenerator相关的常见问题提供排查指导，**新增了智能重试和质量评分相关的故障排除**。

### LLM调用失败
**症状**：工作流执行异常，出现AI服务调用异常错误
**排查步骤**：
1. 检查application.yml中的API密钥配置
2. 验证网络连通性和防火墙设置
3. 查看ChatModelService的日志输出
4. 确认DashScope服务可用性

**章节来源**
- [application.yml:4-8](file://src/main/resources/application.yml#L4-L8)
- [ChatModelService.java:45-48](file://src/main/java/com/example/websitemother/service/ChatModelService.java#L45-L48)

### 智能重试机制问题
**症状**：代码审查失败但重试无效
**排查步骤**：
1. 检查LLM响应格式是否包含正确的BLOCK标记
2. 验证代码块解析器能否正确识别原代码中的对应块
3. 查看日志中关于增量修改的记录
4. 确认ProjectState中的retryCount状态

**章节来源**
- [VueGenerator.java:63-140](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L63-L140)
- [PromptTemplates.java:95-99](file://src/main/java/com/example/websitemother/prompt/PromptTemplates.java#L95-L99)

### JavaScript保留字修复问题
**症状**：保留字修复未生效或过度修复
**排查步骤**：
1. 检查保留字检测正则表达式的准确性
2. 验证修复规则的适用范围
3. 查看日志中的修复记录
4. 确认修复后的代码语法正确性

**章节来源**
- [VueGenerator.java:162-175](file://src/main/java/com/example/websitemother/node/VueGenerator.java#L162-L175)

### 代码质量评分异常
**症状**：质量评分结果不符合预期
**排查步骤**：
1. 检查CodeQualityScorer的评分逻辑
2. 验证各评分维度的权重分配
3. 查看评分详情中的具体扣分原因
4. 确认代码中是否包含预期的特征

**章节来源**
- [CodeQualityScorer.java:18-90](file://src/main/java/com/example/websitemother/service/CodeQualityScorer.java#L18-L90)

### 工作流执行问题
**症状**：工作流中断或状态异常
**排查步骤**：
1. 检查GraphConfig中的节点配置和边连接
2. 验证ReviewRouter的条件判断逻辑（最多3次重试）
3. 确认ProjectState的状态字段完整性
4. 查看GraphWorkflowService的执行日志

**章节来源**
- [GraphConfig.java:78-96](file://src/main/java/com/example/websitemother/config/GraphConfig.java#L78-L96)
- [ReviewRouter.java:22-43](file://src/main/java/com/example/websitemother/edge/ReviewRouter.java#L22-L43)

## 结论
VueGenerator节点通过**重大功能增强**实现了更智能、更可靠的Vue代码生成。新增的智能重试机制支持精确的块级增量修改，JavaScript保留字修复确保代码语法正确性，代码质量评分系统提供量化的质量评估。这些改进使得系统能够更好地处理复杂的代码生成场景，显著提升了用户体验和代码质量。

## 附录

### 最佳实践指南
1. **提示词优化**：定期更新PromptTemplates以提升代码质量
2. **状态管理**：合理使用ProjectState确保数据一致性
3. **错误处理**：完善异常捕获和日志记录机制
4. **性能监控**：建立工作流执行时间监控体系
5. **质量保证**：利用CodeQualityScorer进行持续的质量监控
6. **智能重试**：合理使用重试机制避免无限循环

### 模板定制指南
- 系统提示词应明确代码规范和约束条件
- 用户提示词需包含足够的上下文信息
- **智能重试提示词**应明确BLOCK和CODE的格式要求
- 审查提示词应覆盖常见的代码质量问题

### 调试技巧
- 使用详细的日志输出追踪工作流执行
- 通过单元测试验证各个节点的功能
- 利用前端界面观察状态变化和错误信息
- **质量评分调试**：查看CodeQualityScorer的详细评分报告
- **重试机制调试**：监控BLOCK标记解析和代码块替换过程

### 新功能使用指南
1. **智能重试机制**：当代码审查失败时，LLM响应中应包含BLOCK和CODE标记
2. **块级增量修改**：支持template、script、style三种代码块的精确替换
3. **JavaScript保留字修复**：自动修正function、class等保留字问题
4. **代码质量评分**：系统会自动计算并记录代码质量分数