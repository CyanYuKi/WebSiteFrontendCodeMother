package com.example.websitemother.service;

import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.HashMap;
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

    @Resource
    private CompiledGraph<ProjectState> modifyGraph;

    /**
     * 执行第一阶段：意图分析 + 清单生成
     *
     * @param input 用户初始输入
     * @param model 模型名称
     * @param extraContext 额外上下文（如已有项目的 HTML / PAGES / designConcept），在 graph 执行前注入
     * @return 执行后的状态
     */
    public ProjectState start(String input, String model, Map<String, Object> extraContext) {
        try {
            log.info("[GraphWorkflowService] 执行 startGraph, input={}, model={}, extraKeys={}",
                    input, model, extraContext != null ? extraContext.keySet() : "none");
            Map<String, Object> initState = new HashMap<>();
            initState.put(ProjectState.CURRENT_INPUT, input);
            if (model != null && !model.isBlank()) {
                initState.put(ProjectState.MODEL, model);
            }
            if (extraContext != null) {
                initState.putAll(extraContext);
            }
            var result = startGraph.invoke(initState);
            return result.map(r -> new ProjectState(r.data())).orElse(new ProjectState(Map.of()));
        } catch (Exception e) {
            log.error("[GraphWorkflowService] startGraph 执行失败", e);
            throw new RuntimeException("工作流启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行修改阶段：HTML 修改 + 代码审查循环
     *
     * @param state 包含现有 HTML 和修改需求的完整状态
     * @return 执行后的最终状态
     */
    public ProjectState modify(ProjectState state) {
        try {
            log.info("[GraphWorkflowService] 执行 modifyGraph");
            var result = modifyGraph.invoke(state.data());
            return result.map(r -> {
                Map<String, Object> finalData = r.data();
                log.info("[GraphWorkflowService] modifyGraph 完成, state keys={}", finalData.keySet());
                return new ProjectState(finalData);
            }).orElse(state);
        } catch (Exception e) {
            log.error("[GraphWorkflowService] modifyGraph 执行失败", e);
            throw new RuntimeException("修改工作流失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行第二阶段：素材收集 + 设计概念生成 + HTML生成 + 代码审查循环
     *
     * @param state 包含用户答案的完整状态
     * @return 执行后的最终状态
     */
    public ProjectState resume(ProjectState state) {
        try {
            log.info("[GraphWorkflowService] 执行 resumeGraph");
            var result = resumeGraph.invoke(state.data());
            return result.map(r -> {
                Map<String, Object> finalData = r.data();
                log.info("[GraphWorkflowService] resumeGraph 完成, state keys={}", finalData.keySet());
                return new ProjectState(finalData);
            }).orElse(state);
        } catch (Exception e) {
            log.error("[GraphWorkflowService] resumeGraph 执行失败", e);
            throw new RuntimeException("工作流恢复失败: " + e.getMessage(), e);
        }
    }
}
