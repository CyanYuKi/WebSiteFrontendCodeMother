package com.example.websitemother.config;

import com.example.websitemother.edge.IntentRouter;
import com.example.websitemother.edge.ReviewRouter;
import com.example.websitemother.node.AssetCollector;
import com.example.websitemother.node.ChecklistBuilder;
import com.example.websitemother.node.CodeReviewer;
import com.example.websitemother.node.DesignConceptGenerator;
import com.example.websitemother.node.HtmlGenerator;
import com.example.websitemother.node.IntentAnalyzer;
import com.example.websitemother.node.SubPageGenerator;
import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Resource;
import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * LangGraph4j 状态图配置
 * 组装两个工作流图：startGraph（意图分析+清单生成）和 resumeGraph（素材收集+代码生成+审查循环）
 */
@Slf4j
@Configuration
public class GraphConfig {

    @Resource
    private IntentAnalyzer intentAnalyzer;
    @Resource
    private ChecklistBuilder checklistBuilder;
    @Resource
    private AssetCollector assetCollector;
    @Resource
    private DesignConceptGenerator designConceptGenerator;
    @Resource
    private HtmlGenerator htmlGenerator;
    @Resource
    private SubPageGenerator subPageGenerator;
    @Resource
    private CodeReviewer codeReviewer;
    @Resource
    private IntentRouter intentRouter;
    @Resource
    private ReviewRouter reviewRouter;

    /**
     * 第一阶段图：意图分析 -> 清单生成
     * Human-in-the-loop 暂停点：清单生成后返回给前端
     */
    @Bean
    public CompiledGraph<ProjectState> startGraph() throws GraphStateException {
        log.info("[GraphConfig] 构建 startGraph");

        StateGraph<ProjectState> graph = new StateGraph<>(ProjectState::new)
                .addNode("intent_analyzer", node_async(intentAnalyzer))
                .addNode("checklist_builder", node_async(checklistBuilder))
                .addEdge(StateGraph.START, "intent_analyzer")
                .addConditionalEdges(
                        "intent_analyzer",
                        edge_async(intentRouter),
                        Map.of(
                                IntentRouter.TARGET_CHAT, StateGraph.END,
                                IntentRouter.TARGET_CREATE, "checklist_builder"
                        )
                )
                .addEdge("checklist_builder", StateGraph.END);

        return graph.compile();
    }

    /**
     * 第二阶段图：素材收集 -> 设计概念生成 -> HTML首页生成 -> 代码审查 -> (条件循环) -> 子页面生成 -> END
     * 主页审查通过后才进入子页面生成，避免子页面基于有缺陷的主页生成
     */
    @Bean
    public CompiledGraph<ProjectState> resumeGraph() throws GraphStateException {
        log.info("[GraphConfig] 构建 resumeGraph");

        StateGraph<ProjectState> graph = new StateGraph<>(ProjectState::new)
                .addNode("asset_collector", node_async(assetCollector))
                .addNode("design_concept_generator", node_async(designConceptGenerator))
                .addNode("html_generator", node_async(htmlGenerator))
                .addNode("sub_page_generator", node_async(subPageGenerator))
                .addNode("code_reviewer", node_async(codeReviewer))
                .addEdge(StateGraph.START, "asset_collector")
                .addEdge("asset_collector", "design_concept_generator")
                .addEdge("design_concept_generator", "html_generator")
                .addEdge("html_generator", "code_reviewer")
                .addConditionalEdges(
                        "code_reviewer",
                        edge_async(reviewRouter),
                        Map.of(
                                ReviewRouter.TARGET_END, StateGraph.END,
                                ReviewRouter.TARGET_SUB_PAGE, "sub_page_generator",
                                ReviewRouter.TARGET_RETRY, "html_generator"
                        )
                )
                .addEdge("sub_page_generator", StateGraph.END);

        return graph.compile();
    }
}
