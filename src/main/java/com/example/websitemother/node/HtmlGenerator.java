package com.example.websitemother.node;

import com.example.websitemother.controller.SseEmitterStore;
import com.example.websitemother.prompt.PromptTemplates;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.service.CodeQualityScorer;
import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node: HTML 代码生成器
 * 将设计概念、素材和需求传给大模型，生成完整的自包含 HTML 单文件
 * 支持分块增量修改：重试时只让 LLM 修改一个代码区域（head/body_structure/body_script）
 */
@Slf4j
@Component
public class HtmlGenerator implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Resource
    private CodeQualityScorer qualityScorer;

    private static final Pattern BLOCK_PATTERN = Pattern.compile("BLOCK:\\s*(head|body_structure|body_script)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_BLOCK = Pattern.compile("(?s)<head>.*?</head>");
    private static final Pattern BODY_BLOCK = Pattern.compile("(?s)<body>.*?</body>");

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        SseEmitter emitter = SseEmitterStore.get(state.sessionId());
        sendStage(emitter, "html_generator");

        boolean isRetry = state.retryCount() > 0;
        log.info("[HtmlGenerator] 开始生成首页 index.html, retryCount={}, isRetry={}", state.retryCount(), isRetry);

        // 组装完整需求描述
        StringBuilder requirement = new StringBuilder();
        requirement.append("原始需求：").append(state.currentInput()).append("\n");
        requirement.append("用户补充信息：\n");
        Map<String, String> answers = state.userAnswers();
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            requirement.append("- ").append(entry.getKey()).append("：").append(entry.getValue()).append("\n");
        }

        String modelName = state.data().getOrDefault(ProjectState.MODEL, "qwen3.6-plus").toString();

        // 只生成 index.html，子页面由 SubPageGenerator 独立生成
        String indexCode = generateIndexPage(state, requirement.toString(), modelName, emitter);

        // 代码质量评分（对主页面 index.html）
        CodeQualityScorer.QualityReport report = qualityScorer.score(indexCode);
        log.info("[HtmlGenerator] 首页生成完成, 长度={}, 质量评分={}/100, {}",
                indexCode.length(), report.score(), report.details());

        return Map.of(ProjectState.HTML_CODE, indexCode);
    }

    /**
     * 生成 index.html（首页），包含完整设计系统
     * 如果存在 reviewFeedback（重试场景），将问题反馈给 LLM 让其重新生成完整代码
     */
    private String generateIndexPage(ProjectState state, String requirement, String modelName, SseEmitter emitter) {
        boolean isRetry = state.retryCount() > 0;
        String reviewFeedback = isRetry ? state.reviewFeedback() : null;

        if (isRetry && reviewFeedback != null) {
            log.info("[HtmlGenerator] 第 {} 次重试，审查反馈: {}", state.retryCount(), reviewFeedback);
        }

        String userPrompt = PromptTemplates.htmlGeneratorUser(
                requirement, state.designConcept(), state.designTokens(),
                state.assetsJson(), reviewFeedback, null);

        String response = callLLM(PromptTemplates.HTML_GENERATOR_SYSTEM, userPrompt, modelName, emitter, "index.html");
        String finalCode = stripMarkdown(handleBlockFormat(response.trim()));
        finalCode = injectLinkTargets(finalCode);
        finalCode = fixBrokenKeyframes(finalCode);
        return finalCode;
    }



    /**
     * 统一调用 LLM（流式或同步）
     */
    private String callLLM(String systemPrompt, String userPrompt, String modelName, SseEmitter emitter, String pageLabel) {
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
            log.info("[HtmlGenerator] {} 流式生成完成, 长度={}, model={}", pageLabel, response.length(), modelName);
            return response;
        } else {
            return chatModelService.chat(systemPrompt, userPrompt, modelName);
        }
    }

    /**
     * 处理 BLOCK: 修复格式
     */
    private String handleBlockFormat(String cleaned) {
        if (cleaned.startsWith("BLOCK:")) {
            int codeIdx = cleaned.indexOf("CODE:");
            if (codeIdx >= 0) {
                String blockName = cleaned.substring(6, codeIdx).trim();
                cleaned = cleaned.substring(codeIdx + 5).trim();
                log.info("[HtmlGenerator] 检测到 BLOCK:{} 修复格式, 提取 CODE 后内容长度={}", blockName, cleaned.length());
            }
        }
        return cleaned;
    }

    /**
     * 尝试从 LLM 响应中解析 BLOCK 和 CODE，执行分块替换
     * @return 替换成功返回新代码，失败返回 null
     */
    private String tryPatchBlock(String originalCode, String llmResponse) {
        // 1. 解析 BLOCK 类型
        Matcher blockMatcher = BLOCK_PATTERN.matcher(llmResponse);
        if (!blockMatcher.find()) {
            log.debug("[HtmlGenerator] 未解析到 BLOCK 标记");
            return null;
        }
        String blockType = blockMatcher.group(1).toLowerCase();

        // 2. 提取 CODE 内容（BLOCK 行之后的所有内容）
        int codeStart = llmResponse.indexOf("CODE:");
        if (codeStart < 0) {
            codeStart = blockMatcher.end();
        } else {
            codeStart = codeStart + "CODE:".length();
        }
        String newBlockCode = llmResponse.substring(codeStart).trim();

        // 清理 CODE 内容可能的 markdown 标记
        newBlockCode = stripMarkdown(newBlockCode);

        if (newBlockCode.isBlank()) {
            log.debug("[HtmlGenerator] CODE 内容为空");
            return null;
        }

        // 3. 在原代码中查找并替换对应区域
        Pattern targetPattern;
        switch (blockType) {
            case "head" -> targetPattern = HEAD_BLOCK;
            case "body_structure", "body_script" -> targetPattern = BODY_BLOCK;
            default -> {
                log.debug("[HtmlGenerator] 未知的 BLOCK 类型: {}", blockType);
                return null;
            }
        }

        Matcher targetMatcher = targetPattern.matcher(originalCode);
        if (!targetMatcher.find()) {
            log.debug("[HtmlGenerator] 原代码中未找到 {} 区域", blockType);
            return null;
        }

        String patched = targetMatcher.replaceFirst(Matcher.quoteReplacement(newBlockCode));
        log.info("[HtmlGenerator] 成功替换 {} 区域", blockType);
        return patched;
    }

    private void sendStage(SseEmitter emitter, String stage) {
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("stage").data(stage));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 解析 LLM 多文件响应，按 --- FILE: filename.html --- 分隔符提取各页面代码
     */
    private Map<String, String> parseMultiFiles(String response) {
        Map<String, String> pages = new LinkedHashMap<>();
        Pattern filePattern = Pattern.compile("---\\s*FILE:\\s*([\\w\\-\\.]+)\\s*---");
        Matcher matcher = filePattern.matcher(response);

        int lastEnd = 0;
        String lastFileName = null;

        while (matcher.find()) {
            if (lastFileName != null) {
                String content = response.substring(lastEnd, matcher.start()).trim();
                pages.put(lastFileName, content);
            }
            lastFileName = matcher.group(1);
            lastEnd = matcher.end();
        }

        if (lastFileName != null) {
            String content = response.substring(lastEnd).trim();
            pages.put(lastFileName, content);
        }

        return pages;
    }

    /**
     * 为所有没有 target 属性的外部 <a> 标签注入 target="_blank"，防止 iframe 内点击链接跳出父页面。
     * 锚点链接、相对路径链接（如 about.html）和 javascript: 链接保持原样。
     */
    /**
     * 修复 LLM 常见的 CSS @keyframes 语法错误：缺少闭合大括号。
     * 例如：@keyframes spin { to { transform: rotate(360deg); }
     * 会导致后续所有 CSS 规则失效。
     */
    private String fixBrokenKeyframes(String html) {
        Pattern pattern = Pattern.compile("@keyframes\\s+\\w+\\s*\\{[^{}]*\\{[^{}]*\\}(?!\\s*\\})");
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(0) + "}");
            log.warn("[fixBrokenKeyframes] 修复了损坏的 @keyframes 语法: {}", matcher.group(0).substring(0, Math.min(50, matcher.group(0).length())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String injectLinkTargets(String html) {
        Pattern pattern = Pattern.compile("<a\\b([^>]*)>");
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String attrs = matcher.group(1);
            // 已有 target 属性则跳过
            if (attrs.contains("target=")) {
                matcher.appendReplacement(sb, matcher.group(0));
                continue;
            }
            String attrsLower = attrs.toLowerCase();
            // 跳过锚点、javascript、以及所有非 http 开头的相对路径链接
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

    /**
     * 清理 markdown 代码块标记
     */
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
}
