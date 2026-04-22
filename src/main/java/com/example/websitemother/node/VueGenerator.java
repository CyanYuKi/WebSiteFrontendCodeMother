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
 * Node 4: Vue 代码生成
 * 将需求与素材传给大模型，生成完整的单文件 Vue 3 代码
 */
@Slf4j
@Component
public class VueGenerator implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        String currentInput = state.currentInput();
        String assetsJson = state.assetsJson();
        String reviewFeedback = state.reviewFeedback();

        log.info("[VueGenerator] 开始生成Vue代码, retryCount={}, hasFeedback={}",
                state.retryCount(), !reviewFeedback.isEmpty());

        // 组装完整需求描述
        StringBuilder requirement = new StringBuilder();
        requirement.append("原始需求：").append(currentInput).append("\n");
        requirement.append("用户补充信息：\n");
        Map<String, String> answers = state.userAnswers();
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            requirement.append("- ").append(entry.getKey()).append("：").append(entry.getValue()).append("\n");
        }

        String response = chatModelService.chat(
                PromptTemplates.VUE_GENERATOR_SYSTEM,
                PromptTemplates.vueGeneratorUser(requirement.toString(), assetsJson, reviewFeedback)
        );

        // 清理可能的 markdown 代码块标记
        String cleaned = response.trim();
        if (cleaned.startsWith("```vue")) {
            cleaned = cleaned.substring("```vue".length());
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length());
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length());
        }
        cleaned = cleaned.trim();

        log.info("[VueGenerator] Vue代码生成完成, 长度={}", cleaned.length());

        return Map.of(ProjectState.VUE_CODE, cleaned);
    }
}
