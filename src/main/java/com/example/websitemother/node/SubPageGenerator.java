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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node: 子页面生成器（子 Agent）
 * 从首页 htmlCode 中提取子页面导航链接和设计系统，为每个子页面独立调用 LLM 生成代码。
 * 职责分离：HtmlGenerator 只负责首页，SubPageGenerator 专门负责子页面，避免单节点 token 竞争。
 */
@Slf4j
@Component
public class SubPageGenerator implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    private static final Pattern BLOCK_PATTERN = Pattern.compile("BLOCK:\\s*(head|body_structure|body_script)", Pattern.CASE_INSENSITIVE);

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        SseEmitter emitter = SseEmitterStore.get(state.sessionId());
        sendStage(emitter, "sub_page_generator");

        String indexCode = state.htmlCode();
        if (indexCode == null || indexCode.isBlank()) {
            log.warn("[SubPageGenerator] 首页代码为空，跳过子页面生成");
            return Map.of(ProjectState.PAGES, Map.of("index.html", ""));
        }

        String modelName = state.model();

        // 检查是否为修改模式（NEW_PAGES_DETECTED 非空）
        java.util.List<String> newPagesRequested = state.newPagesDetected();
        boolean isModifyMode = !newPagesRequested.isEmpty();

        // 组装完整需求描述（修改模式下不污染 currentInput）
        StringBuilder requirement = new StringBuilder();
        if (isModifyMode) {
            // 修改模式下 currentInput 是修改指令，不适合作为子页面需求
            requirement.append("根据首页设计风格生成配套子页面");
        } else {
            requirement.append("原始需求：").append(state.currentInput()).append("\n");
        }
        requirement.append("用户补充信息：\n");
        Map<String, String> answers = state.userAnswers();
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            requirement.append("- ").append(entry.getKey()).append("：").append(entry.getValue()).append("\n");
        }

        // 修改模式下以现有 PAGES 为基础；创建模式下新建
        @SuppressWarnings("unchecked")
        Map<String, String> existingPages = (Map<String, String>) state.data()
                .getOrDefault(ProjectState.PAGES, new LinkedHashMap<>());
        Map<String, String> pages;
        if (isModifyMode) {
            pages = new LinkedHashMap<>(existingPages);
            if (!pages.containsKey("index.html")) {
                pages.put("index.html", indexCode);
            }
        } else {
            pages = new LinkedHashMap<>();
            pages.put("index.html", indexCode);
        }

        // 从首页导航栏提取子页面链接
        Set<String> subPages = extractSubPagesFromNav(indexCode);
        if (subPages.isEmpty()) {
            log.info("[SubPageGenerator] 首页没有子页面链接，直接结束");
            return Map.of(ProjectState.PAGES, pages);
        }

        log.info("[SubPageGenerator] 从导航栏检测到 {} 个子页面: {}", subPages.size(), subPages);

        // 提取设计系统、导航栏参考和页面规划
        String designSystem = extractDesignSystem(indexCode);
        String navHtml = extractNavHtml(indexCode);
        List<PagePlan> pagePlans = extractPagePlan(indexCode);

        // 如果提取不到规划，fallback 到从导航栏提取
        if (pagePlans.isEmpty()) {
            for (String pageName : subPages) {
                pagePlans.add(new PagePlan(pageName, pageName.replace(".html", ""), "", ""));
            }
        }

        // 发送页面清单事件（前端展示用）
        sendPageList(emitter, pagePlans);

        // 构建并行任务（修改模式下跳过已存在的页面）
        List<Callable<Map.Entry<String, String>>> tasks = new ArrayList<>();
        for (PagePlan plan : pagePlans) {
            if (!subPages.contains(plan.name)) {
                log.warn("[SubPageGenerator] 规划中的 {} 不在导航栏链接中，跳过", plan.name);
                continue;
            }
            if (isModifyMode && pages.containsKey(plan.name)) {
                log.info("[SubPageGenerator] {} 已存在，跳过", plan.name);
                continue;
            }
            tasks.add(() -> {
                sendPageStatus(emitter, plan.name, "generating");
                String pageCode = generateSubPage(state, plan, designSystem, navHtml,
                        requirement.toString(), modelName, emitter);

                // 子页面独立结构审查（最多重试2次）
                String checkResult = fastStructureCheck(pageCode);
                int subRetry = 0;
                while (checkResult != null && subRetry < 2) {
                    log.warn("[SubPageGenerator] {} 结构检查未通过: {}, 重试第{}次",
                            plan.name, checkResult, subRetry + 1);
                    sendPageStatus(emitter, plan.name, "retrying");
                    pageCode = generateSubPage(state, plan, designSystem, navHtml,
                            requirement.toString(), modelName, emitter);
                    checkResult = fastStructureCheck(pageCode);
                    subRetry++;
                }

                if (checkResult != null) {
                    log.error("[SubPageGenerator] {} 重试后仍失败: {}, 保留最佳尝试", plan.name, checkResult);
                }

                sendPageStatus(emitter, plan.name, "completed");
                log.info("[SubPageGenerator] {} 生成完成, 长度={}, 重试次数={}",
                        plan.name, pageCode.length(), subRetry);
                return Map.entry(plan.name, pageCode);
            });
        }

        // 线程池并行生成（最多3个并发）
        if (!tasks.isEmpty()) {
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(tasks.size(), 3));
            try {
                List<Future<Map.Entry<String, String>>> futures = executor.invokeAll(tasks);
                for (Future<Map.Entry<String, String>> f : futures) {
                    Map.Entry<String, String> entry = f.get();
                    pages.put(entry.getKey(), entry.getValue());
                }
                log.info("[SubPageGenerator] 所有子页面并行生成完成");
            } catch (Exception e) {
                log.error("[SubPageGenerator] 并行生成子页面失败", e);
                throw new RuntimeException("子页面生成失败: " + e.getMessage(), e);
            } finally {
                executor.shutdown();
            }
        }

        // 兜底修复：确保导航链接指向的文件名与实际生成的文件名一致
        pages = fixNavLinks(pages);

        log.info("[SubPageGenerator] 子页面生成完成, 总页面数={}", pages.size());
        return Map.of(ProjectState.PAGES, pages);
    }

    /**
     * 页面规划数据对象
     */
    private static class PagePlan {
        final String name;
        final String title;
        final String overview;
        final String layout;

        PagePlan(String name, String title, String overview, String layout) {
            this.name = name;
            this.title = title;
            this.overview = overview;
            this.layout = layout;
        }
    }

    /**
     * 从 index.html 中提取 PAGES_PLAN JSON 注释
     */
    private java.util.List<PagePlan> extractPagePlan(String indexHtml) {
        java.util.List<PagePlan> plans = new java.util.ArrayList<>();
        Pattern pattern = Pattern.compile("<!--\\s*PAGES_PLAN:\\s*(\\[.*?\\])\\s*-->");
        Matcher matcher = pattern.matcher(indexHtml);
        if (matcher.find()) {
            String json = matcher.group(1);
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
                for (com.fasterxml.jackson.databind.JsonNode node : root) {
                    String name = node.has("name") ? node.get("name").asText() : "";
                    String title = node.has("title") ? node.get("title").asText() : "";
                    String overview = node.has("overview") ? node.get("overview").asText() : "";
                    String layout = node.has("layout") ? node.get("layout").asText() : "";
                    if (!name.isBlank()) {
                        plans.add(new PagePlan(name, title, overview, layout));
                    }
                }
                log.info("[SubPageGenerator] 从 PAGES_PLAN 提取到 {} 个子页面规划", plans.size());
            } catch (Exception e) {
                log.warn("[SubPageGenerator] 解析 PAGES_PLAN 失败: {}", e.getMessage());
            }
        }
        return plans;
    }

    /**
     * 生成单个子页面
     */
    private String generateSubPage(ProjectState state, PagePlan plan, String designSystem, String navHtml,
                                    String requirement, String modelName, SseEmitter emitter) {
        String userPrompt = PromptTemplates.htmlSubPageUser(
                plan.name, designSystem, navHtml, requirement,
                state.designConcept(), state.designTokens(), state.assetsJson(),
                plan.overview, plan.layout);

        String response = callLLM(PromptTemplates.HTML_SUBPAGE_SYSTEM, userPrompt, modelName, emitter, plan.name);
        String cleaned = handleBlockFormat(response.trim());
        String finalCode = stripMarkdown(cleaned);
        finalCode = injectLinkTargets(finalCode);
        finalCode = fixBrokenKeyframes(finalCode);
        return finalCode;
    }

    /**
     * 统一调用 LLM（流式或同步）
     * 子页面的 token 通过 page_token 事件发送，带页面标识
     */
    private String callLLM(String systemPrompt, String userPrompt, String modelName, SseEmitter emitter, String pageLabel) {
        if (emitter != null) {
            String response = chatModelService.streamChat(
                    systemPrompt, userPrompt, modelName,
                    token -> sendPageToken(emitter, pageLabel, token)
            );
            log.info("[SubPageGenerator] {} 流式生成完成, 长度={}, model={}", pageLabel, response.length(), modelName);
            return response;
        } else {
            return chatModelService.chat(systemPrompt, userPrompt, modelName);
        }
    }

    // ==================== SSE 事件发送（线程安全）====================

    private synchronized void sendPageToken(SseEmitter emitter, String page, String token) {
        if (emitter != null) {
            try {
                String json = String.format("{\"page\":\"%s\",\"token\":\"%s\"}", escapeJson(page), escapeJson(token));
                emitter.send(SseEmitter.event().name("page_token").data(json));
            } catch (Exception ignored) {
            }
        }
    }

    private synchronized void sendPageStatus(SseEmitter emitter, String page, String status) {
        if (emitter != null) {
            try {
                String json = String.format("{\"page\":\"%s\",\"status\":\"%s\"}", escapeJson(page), status);
                emitter.send(SseEmitter.event().name("page_status").data(json));
            } catch (Exception ignored) {
            }
        }
    }

    private synchronized void sendPageList(SseEmitter emitter, List<PagePlan> plans) {
        if (emitter == null || plans.isEmpty()) return;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, String>> list = new ArrayList<>();
            for (PagePlan p : plans) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", p.name);
                m.put("title", p.title);
                m.put("overview", p.overview);
                list.add(m);
            }
            String json = mapper.writeValueAsString(Map.of("pages", list));
            emitter.send(SseEmitter.event().name("page_list").data(json));
        } catch (Exception e) {
            log.warn("[SubPageGenerator] 发送 page_list 失败", e);
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ==================== 工具方法（与 HtmlGenerator 共享逻辑）====================

    /**
     * 从 index.html 导航栏中提取所有子页面链接（.html 结尾且非 index.html）
     */
    private Set<String> extractSubPagesFromNav(String indexHtml) {
        Set<String> pages = new java.util.LinkedHashSet<>();
        Pattern navPattern = Pattern.compile("<a\\b[^>]*href=[\"']([^\"']+\\.html)[\"'][^>]*>");
        Matcher matcher = navPattern.matcher(indexHtml);
        while (matcher.find()) {
            String href = matcher.group(1);
            if (!"index.html".equals(href) && !href.startsWith("http") && !href.startsWith("#") && !href.startsWith("/")) {
                pages.add(href);
            }
        }
        return pages;
    }

    /**
     * 从 index.html 中提取设计系统 CSS
     * 包括 :root 变量、@font-face、全局 reset、通用标签样式
     */
    private String extractDesignSystem(String html) {
        Pattern stylePattern = Pattern.compile("(?s)<style[^>]*>(.*?)</style>");
        Matcher styleMatcher = stylePattern.matcher(html);
        if (styleMatcher.find()) {
            String css = styleMatcher.group(1);
            StringBuilder sb = new StringBuilder();
    
            // 提取 :root 块
            Pattern rootPattern = Pattern.compile("(?s):root\\s*\\{.*?\\}");
            Matcher rootMatcher = rootPattern.matcher(css);
            if (rootMatcher.find()) {
                sb.append(rootMatcher.group()).append("\n");
            }

            // 提取 @font-face
            Pattern fontPattern = Pattern.compile("(?s)@font-face\\s*\\{.*?\\}");
            Matcher fontMatcher = fontPattern.matcher(css);
            while (fontMatcher.find()) {
                sb.append(fontMatcher.group()).append("\n");
            }

            // 提取全局 reset
            Pattern resetPattern = Pattern.compile("(?s)\\*\\s*,?\\s*\\*::before\\s*,?\\s*\\*::after\\s*\\{.*?\\}");
            Matcher resetMatcher = resetPattern.matcher(css);
            if (resetMatcher.find()) {
                sb.append(resetMatcher.group()).append("\n");
            }

            // 提取 html, body, img, a 等基础标签样式
            Pattern basePattern = Pattern.compile("(?s)(?:html|body|img|a)\\s*\\{.*?\\}");
            Matcher baseMatcher = basePattern.matcher(css);
            while (baseMatcher.find()) {
                sb.append(baseMatcher.group()).append("\n");
            }

            // 提取通用布局类（.wrapper）
            Pattern wrapperPattern = Pattern.compile("(?s)\\.wrapper\\s*\\{.*?\\}");
            Matcher wrapperMatcher = wrapperPattern.matcher(css);
            if (wrapperMatcher.find()) {
                sb.append(wrapperMatcher.group()).append("\n");
            }
    
            return sb.toString().trim();
        }
        return "";
    }

    /**
     * 提取首页导航栏的 HTML 结构，供子页面复制使用
     */
    private String extractNavHtml(String indexHtml) {
        // 优先匹配 <nav>...</nav>
        Pattern navPattern = Pattern.compile("(?s)<nav\\b.*?</nav>");
        Matcher navMatcher = navPattern.matcher(indexHtml);
        if (navMatcher.find()) {
            return navMatcher.group();
        }
        //  fallback：匹配 <header>...</header>
        Pattern headerPattern = Pattern.compile("(?s)<header\\b.*?</header>");
        Matcher headerMatcher = headerPattern.matcher(indexHtml);
        if (headerMatcher.find()) {
            return headerMatcher.group();
        }
        return "";
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
                log.info("[SubPageGenerator] 检测到 BLOCK:{} 修复格式, 提取 CODE 后内容长度={}", blockName, cleaned.length());
            }
        }
        return cleaned;
    }

    /**
     * 修复 LLM 常见的 CSS @keyframes 语法错误：缺少闭合大括号。
     */
    private String fixBrokenKeyframes(String html) {
        Pattern pattern = Pattern.compile("@keyframes\\s+\\w+\\s*\\{[^{}]*\\{[^{}]*\\}(?!\\s*\\})");
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(0) + "}");
            log.warn("[SubPageGenerator][fixBrokenKeyframes] 修复了损坏的 @keyframes 语法: {}",
                    matcher.group(0).substring(0, Math.min(50, matcher.group(0).length())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 为所有没有 target 属性的外部 <a> 标签注入 target="_blank"
     */
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

    /**
     * 兜底修复：扫描所有页面中的内部导航链接，将指向不存在文件的链接修正为实际存在的文件名。
     */
    private Map<String, String> fixNavLinks(Map<String, String> pages) {
        if (pages == null || pages.size() <= 1) {
            return pages;
        }
        Set<String> existingFiles = new java.util.HashSet<>(pages.keySet());
        Map<String, String> fixed = new LinkedHashMap<>();
        int fixCount = 0;

        for (Map.Entry<String, String> entry : pages.entrySet()) {
            String currentFile = entry.getKey();
            String content = entry.getValue();

            Pattern pattern = Pattern.compile(
                    "(?i)(<a\\s+[^>]*?href=[\"'])([^\"']+\\.html)([\"'][^>]*>)"
            );
            Matcher matcher = pattern.matcher(content);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String href = matcher.group(2);
                if (!existingFiles.contains(href)) {
                    String replacement = existingFiles.stream()
                            .filter(f -> !f.equals(currentFile))
                            .findFirst()
                            .orElse("index.html");
                    matcher.appendReplacement(sb,
                            Matcher.quoteReplacement(matcher.group(1) + replacement + matcher.group(3)));
                    fixCount++;
                    log.warn("[SubPageGenerator] fixNavLinks: 修复 {} 中不存在的链接 {} -> {}",
                            currentFile, href, replacement);
                } else {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                }
            }
            matcher.appendTail(sb);
            fixed.put(currentFile, sb.toString());
        }

        if (fixCount > 0) {
            log.info("[SubPageGenerator] fixNavLinks: 共修复 {} 处不存在的导航链接", fixCount);
        }
        return fixed;
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

    private void sendStage(SseEmitter emitter, String stage) {
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("stage").data(stage));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 子页面快速结构检查（简化版，内联在 SubPageGenerator 中避免依赖）
     * @return null 表示通过；否则返回失败原因
     */
    private String fastStructureCheck(String htmlCode) {
        if (htmlCode == null || htmlCode.isBlank()) {
            return "代码为空";
        }

        String trimmed = htmlCode.trim();
        String lower = trimmed.toLowerCase();

        // 1. 基本结构
        if (!lower.contains("<!doctype html>")) return "缺少 <!DOCTYPE html>";
        if (!lower.contains("<html")) return "缺少 <html>";
        if (!lower.contains("<head")) return "缺少 <head>";
        if (!lower.contains("<body")) return "缺少 <body>";
        if (!lower.contains("</html>")) return "<html> 未闭合";
        if (!lower.contains("</head>")) return "<head> 未闭合";
        if (!lower.contains("</body>")) return "<body> 未闭合";

        // 2. 末尾截断检测
        char lastChar = trimmed.charAt(trimmed.length() - 1);
        if (lastChar == '<' || lastChar == '=' || lastChar == '(' || lastChar == '[' || lastChar == '{') {
            return "代码在末尾处被截断（未完成标签）";
        }
        String tail = trimmed.substring(Math.max(0, trimmed.length() - 200));
        if (tail.lastIndexOf('<') > tail.lastIndexOf('>')) {
            String unclosed = tail.substring(tail.lastIndexOf('<'));
            if (unclosed.length() < 50 && !unclosed.contains(">")) {
                return "代码在末尾标签处被截断";
            }
        }

        // 3. body 内容过少检测
        int bodyStart = lower.indexOf("<body>");
        if (bodyStart < 0) bodyStart = lower.indexOf("<body ");
        if (bodyStart >= 0) {
            int contentStart = trimmed.indexOf(">", bodyStart) + 1;
            int bodyEnd = lower.lastIndexOf("</body>");
            if (bodyEnd > contentStart) {
                String bodyContent = trimmed.substring(contentStart, bodyEnd);
                String stripped = bodyContent.replaceAll("\\s+", "");
                if (stripped.length() < 200) {
                    return "body 内容被截断，实质内容过少（" + stripped.length() + "字符）";
                }
            }
        }

        return null;
    }
}
