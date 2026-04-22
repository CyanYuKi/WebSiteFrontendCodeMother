package com.example.websitemother.service;

import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * LangGraph 工作流执行服务
 * 封装 startGraph 和 resumeGraph 的执行逻辑
 */
@Slf4j
@Service
public class GraphWorkflowService {

    @Resource
    private CompiledGraph<ProjectState> startGraph;

    @Resource
    private CompiledGraph<ProjectState> resumeGraph;

    /**
     * 执行第一阶段：意图分析 + 清单生成
     *
     * @param input 用户初始输入
     * @return 执行后的状态
     */
    public ProjectState start(String input) {
        try {
            log.info("[GraphWorkflowService] 执行 startGraph, input={}", input);
            Map<String, Object> initState = Map.of(ProjectState.CURRENT_INPUT, input);
            var result = startGraph.invoke(initState);
            return result.map(r -> new ProjectState(r.data())).orElse(new ProjectState(Map.of()));
        } catch (Exception e) {
            log.error("[GraphWorkflowService] startGraph 执行失败", e);
            throw new RuntimeException("工作流启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行第二阶段：素材收集 + Vue生成 + 代码审查循环
     *
     * @param state 包含用户答案的完整状态
     * @return 执行后的最终状态
     */
    public ProjectState resume(ProjectState state) {
        try {
            log.info("[GraphWorkflowService] 执行 resumeGraph");
            var result = resumeGraph.invoke(state.data());
            return result.map(r -> new ProjectState(r.data())).orElse(state);
        } catch (Exception e) {
            log.error("[GraphWorkflowService] resumeGraph 执行失败", e);
            throw new RuntimeException("工作流恢复失败: " + e.getMessage(), e);
        }
    }
}
