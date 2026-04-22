package com.example.websitemother.config;

import com.example.websitemother.edge.IntentRouter;
import com.example.websitemother.edge.ReviewRouter;
import com.example.websitemother.node.AssetCollector;
import com.example.websitemother.node.ChecklistBuilder;
import com.example.websitemother.node.CodeReviewer;
import com.example.websitemother.node.IntentAnalyzer;
import com.example.websitemother.node.VueGenerator;
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
    private VueGenerator vueGenerator;
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
     * 第二阶段图：素材收集 -> Vue代码生成 -> 代码审查 -> (条件循环)
     * 接收前端补充的答案后继续执行
     */
    @Bean
    public CompiledGraph<ProjectState> resumeGraph() throws GraphStateException {
        log.info("[GraphConfig] 构建 resumeGraph");

        StateGraph<ProjectState> graph = new StateGraph<>(ProjectState::new)
                .addNode("asset_collector", node_async(assetCollector))
                .addNode("vue_generator", node_async(vueGenerator))
                .addNode("code_reviewer", node_async(codeReviewer))
                .addEdge(StateGraph.START, "asset_collector")
                .addEdge("asset_collector", "vue_generator")
                .addEdge("vue_generator", "code_reviewer")
                .addConditionalEdges(
                        "code_reviewer",
                        edge_async(reviewRouter),
                        Map.of(
                                ReviewRouter.TARGET_END, StateGraph.END,
                                ReviewRouter.TARGET_RETRY, "vue_generator"
                        )
                );

        return graph.compile();
    }
}
