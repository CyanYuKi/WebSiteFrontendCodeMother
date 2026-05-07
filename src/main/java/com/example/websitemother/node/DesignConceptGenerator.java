package com.example.websitemother.node;

import com.example.websitemother.prompt.PromptTemplates;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.state.ProjectState;
import com.example.websitemother.controller.SseEmitterStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * Node: 设计概念生成器
 * 根据用户需求和素材资源，生成设计概念方案（配色、字体、间距、布局方向）
 * 输出结构化 JSON，供 HtmlGenerator 使用
 */
@Slf4j
@Component
public class DesignConceptGenerator implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        sendStage(SseEmitterStore.get(state.sessionId()), "design_concept");

        String requirement = buildRequirement(state);
        String assetsJson = state.assetsJson();

        log.info("[DesignConceptGenerator] 生成设计概念, requirement长度={}", requirement.length());

        String response = chatModelService.chat(
                PromptTemplates.DESIGN_CONCEPT_SYSTEM,
                PromptTemplates.designConceptUser(requirement, assetsJson),
                ChatModelService.ModelType.SMART
        );

        // 清理可能的 markdown 代码块标记
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length());
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        // 解析 JSON 提取设计概念和 CSS 变量
        String designConcept = cleaned;
        String designTokens = extractDesignTokens(cleaned);

        log.info("[DesignConceptGenerator] 设计概念生成完成, tokens长度={}", designTokens.length());

        return Map.of(
                ProjectState.DESIGN_CONCEPT, designConcept,
                ProjectState.DESIGN_TOKENS, designTokens
        );
    }

    /**
     * 从需求构建完整的设计需求描述
     */
    private String buildRequirement(ProjectState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("原始需求：").append(state.currentInput()).append("\n");

        Map<String, String> answers = state.userAnswers();
        if (answers != null && !answers.isEmpty()) {
            sb.append("补充信息：\n");
            answers.forEach((key, value) -> sb.append("- ").append(key).append("：").append(value).append("\n"));
        }
        return sb.toString();
    }

    /**
     * 从设计概念 JSON 中提取 CSS 变量定义
     */
    private void sendStage(SseEmitter emitter, String stage) {
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("stage").data(stage));
            } catch (Exception ignored) {
            }
        }
    }

    private String extractDesignTokens(String designConceptJson) {
        try {
            JsonNode root = objectMapper.readTree(designConceptJson);
            StringBuilder tokens = new StringBuilder();
            tokens.append(":root {\n");

            // 提取配色
            JsonNode colorPalette = root.path("colorPalette");
            if (!colorPalette.isMissingNode()) {
                colorPalette.fields().forEachRemaining(entry -> {
                    tokens.append("  --color-").append(entry.getKey())
                          .append(": ").append(entry.getValue().asText()).append(";\n");
                });
            }

            // 提取字体
            JsonNode typography = root.path("typography");
            if (!typography.isMissingNode()) {
                if (typography.has("headingFont")) {
                    tokens.append("  --font-heading: ").append(typography.get("headingFont").asText()).append(";\n");
                }
                if (typography.has("bodyFont")) {
                    tokens.append("  --font-body: ").append(typography.get("bodyFont").asText()).append(";\n");
                }
            }

            // 提取间距
            JsonNode spacing = root.path("spacing");
            if (!spacing.isMissingNode()) {
                if (spacing.has("unit")) {
                    tokens.append("  --spacing-unit: ").append(spacing.get("unit").asText()).append(";\n");
                }
            }

            tokens.append("}\n");
            return tokens.toString();
        } catch (Exception e) {
            log.warn("[DesignConceptGenerator] 解析设计概念JSON失败，使用默认tokens", e);
            return ":root {\n  --color-primary: #D97757;\n  --color-background: #FAFAF9;\n  --color-text: #1C1917;\n  --font-heading: Georgia, serif;\n  --font-body: system-ui, sans-serif;\n  --spacing-unit: 1rem;\n}\n";
        }
    }
}
