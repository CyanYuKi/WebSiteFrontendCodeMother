package com.example.websitemother.node;

import com.example.websitemother.controller.SseEmitterStore;
import com.example.websitemother.prompt.PromptTemplates;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node: HTML 代码修改器
 * 接收现有 HTML 代码和用户的修改需求，调用 LLM 精确修改指定页面的代码。
 * 支持重试：审查不通过时根据反馈重新修改。
 */
@Slf4j
@Component
public class HtmlModifier implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        SseEmitter emitter = SseEmitterStore.get(state.sessionId());
        sendStage(emitter, "html_modifier");

        String userRequest = state.currentInput();
        String targetPage = state.targetPage();
        boolean isRetry = state.retryCount() > 0;
        String reviewFeedback = isRetry ? state.reviewFeedback() : null;
        String plan = state.modifyPlan();
        String modelName = state.model();

        // 从 PAGES 映射中取出目标页面的 HTML
        @SuppressWarnings("unchecked")
        Map<String, String> existingPages = (Map<String, String>) state.data()
                .getOrDefault(ProjectState.PAGES, Map.of("index.html", state.htmlCode()));
        String targetHtml = existingPages.getOrDefault(targetPage, state.htmlCode());

        if (isRetry) {
            log.info("[HtmlModifier] 第 {} 次重试修改页面 {}, 审查反馈: {}",
                    state.retryCount(), targetPage, reviewFeedback);
        }
        log.info("[HtmlModifier] 开始修改页面: {}, 代码长度={}, 需求长度={}, 有计划={}",
                targetPage, targetHtml.length(), userRequest.length(), !plan.isBlank());

        String userPrompt = PromptTemplates.htmlModifierUser(targetHtml, userRequest, reviewFeedback, plan, targetPage);
        String response = callLLM(PromptTemplates.HTML_MODIFIER_SYSTEM, userPrompt, modelName, emitter);

        String finalCode = stripMarkdown(response.trim());
        finalCode = injectLinkTargets(finalCode);
        finalCode = fixBrokenKeyframes(finalCode);

        // 构建更新后的 PAGES 映射
        Map<String, String> updatedPages = new LinkedHashMap<>(existingPages);
        updatedPages.put(targetPage, finalCode);

        log.info("[HtmlModifier] 修改完成, 目标页={}, 输出长度={}", targetPage, finalCode.length());

        Map<String, Object> result = new HashMap<>();
        if ("index.html".equals(targetPage)) {
            result.put(ProjectState.HTML_CODE, finalCode);
        }
        result.put(ProjectState.PAGES, updatedPages);
        return result;
    }

    private String callLLM(String systemPrompt, String userPrompt, String modelName, SseEmitter emitter) {
        if (emitter != null) {
            String response = chatModelService.streamChat(
                    systemPrompt, userPrompt, modelName,
                    token -> {
                        try {
                            emitter.send(SseEmitter.event().name("html_token").data(token));
                        } catch (Exception ignored) {
                        }
                    }
            );
            log.info("[HtmlModifier] 流式修改完成, 长度={}, model={}", response.length(), modelName);
            return response;
        } else {
            return chatModelService.chat(systemPrompt, userPrompt, modelName);
        }
    }

    private void sendStage(SseEmitter emitter, String stage) {
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("stage").data(stage));
            } catch (Exception ignored) {
            }
        }
    }

    private String stripMarkdown(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```html")) {
            cleaned = cleaned.substring("```html".length());
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length());
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length());
        }
        return cleaned.trim();
    }

    private String injectLinkTargets(String html) {
        Pattern pattern = Pattern.compile("<a\\b([^>]*)>");
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String attrs = matcher.group(1);
            if (attrs.contains("target=")) {
                matcher.appendReplacement(sb, matcher.group(0));
                continue;
            }
            String attrsLower = attrs.toLowerCase();
            if (attrsLower.contains("href=\"#") || attrsLower.contains("href='#") ||
                attrsLower.contains("href=\"javascript:") || attrsLower.contains("href='javascript:") ||
                attrsLower.contains("href=\"/") || attrsLower.contains("href='/") ||
                (!attrsLower.contains("href=\"http") && !attrsLower.contains("href='http"))) {
                matcher.appendReplacement(sb, matcher.group(0));
                continue;
            }
            matcher.appendReplacement(sb, "<a$1 target=\"_blank\" rel=\"noopener noreferrer\">");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String fixBrokenKeyframes(String html) {
        Pattern pattern = Pattern.compile("@keyframes\\s+\\w+\\s*\\{[^{}]*\\{[^{}]*\\}(?!\\s*\\})");
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(0) + "}");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
