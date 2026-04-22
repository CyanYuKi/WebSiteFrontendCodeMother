package com.example.websitemother.node;

import com.example.websitemother.prompt.PromptTemplates;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * Node 2: 生成需求清单
 * 根据用户建站需求，利用大模型生成需要用户补充信息的表单字段
 */
@Slf4j
@Component
public class ChecklistBuilder implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        String currentInput = state.currentInput();
        log.info("[ChecklistBuilder] 根据需求生成清单: {}", currentInput);

        String response = chatModelService.chat(
                PromptTemplates.CHECKLIST_BUILDER_SYSTEM,
                PromptTemplates.checklistBuilderUser(currentInput)
        );

        // 清理可能的 markdown 代码块标记
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length());
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length());
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length());
        }
        cleaned = cleaned.trim();

        log.info("[ChecklistBuilder] 生成清单完成");

        return Map.of(ProjectState.CHECKLIST, cleaned);
    }
}
