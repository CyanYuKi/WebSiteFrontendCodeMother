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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node: 修改规划器
 * 分析项目结构摘要和用户修改需求，生成包含目标页面、DOM区域、新页面检测的结构化修改计划。
 */
@Slf4j
@Component
public class ModifyPlanner implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        SseEmitter emitter = SseEmitterStore.get(state.sessionId());
        sendStage(emitter, "modify_planner");

        String userRequest = state.currentInput();
        String currentPage = state.targetPage();
        String modelName = state.model();

        // 从 PAGES 映射构建项目结构摘要
        @SuppressWarnings("unchecked")
        Map<String, String> pagesMap = (Map<String, String>) state.data()
                .getOrDefault(ProjectState.PAGES, Map.of("index.html", state.htmlCode()));
        String structuralSummary = buildStructuralSummary(pagesMap);

        log.info("[ModifyPlanner] 开始分析修改需求, 页面数={}, 当前页={}, 需求={}",
                pagesMap.size(), currentPage, userRequest);

        String userPrompt = PromptTemplates.modifyPlannerUser(structuralSummary, userRequest, currentPage);
        String plan = callLLM(PromptTemplates.MODIFY_PLANNER_SYSTEM, userPrompt, modelName, emitter);

        log.info("[ModifyPlanner] 修改计划生成完成, 长度={}", plan.length());
        return Map.of(ProjectState.MODIFY_PLAN, plan.trim());
    }

    /**
     * 从 PAGES 映射构建项目结构摘要，供 LLM 分析
     * 每页提取：文件大小、导航链接、主要区块选择器、标题文本
     */
    String buildStructuralSummary(Map<String, String> pages) {
        if (pages == null || pages.isEmpty()) {
            return "(项目无页面)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【项目页面清单】\n");
        for (String name : pages.keySet()) {
            String content = pages.get(name);
            int kb = content != null ? content.length() / 1024 : 0;
            sb.append("- ").append(name).append(" (").append(kb).append(" KB)\n");
        }

        // 提取全局设计变量（从 index.html 或第一个页面）
        String indexHtml = pages.getOrDefault("index.html",
                pages.values().iterator().next());
        String cssVars = extractCssVariables(indexHtml);
        if (!cssVars.isEmpty()) {
            sb.append("\n【设计系统 CSS 变量】\n").append(cssVars).append("\n");
        }

        // 每个页面的结构摘要（限制总长度 4000 字符，每页 max 500 字符）
        int totalLimit = 4000;
        int perPageLimit = Math.min(500, totalLimit / Math.max(pages.size(), 1));
        int remaining = totalLimit;

        for (Map.Entry<String, String> entry : pages.entrySet()) {
            if (remaining <= 0) break;
            String name = entry.getKey();
            String code = entry.getValue();
            String pageSummary = buildPageSummary(name, code, Math.min(perPageLimit, remaining));
            sb.append(pageSummary);
            remaining -= pageSummary.length();
        }

        return sb.toString();
    }

    private String buildPageSummary(String name, String html, int maxLen) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n【").append(name).append(" 结构摘要】\n");

        // 导航链接
        String navLinks = extractNavLinks(html);
        if (!navLinks.isEmpty()) {
            sb.append("导航链接: ").append(navLinks).append("\n");
        }

        // 主要区块
        String sections = extractSections(html);
        if (!sections.isEmpty()) {
            sb.append("主要区块: ").append(sections).append("\n");
        }

        // 标题层级
        String headings = extractHeadings(html);
        if (!headings.isEmpty()) {
            sb.append("标题层级: ").append(headings).append("\n");
        }

        if (sb.length() > maxLen) {
            return sb.substring(0, maxLen) + "...\n";
        }
        return sb.toString();
    }

    private String extractNavLinks(String html) {
        StringBuilder sb = new StringBuilder();
        Pattern p = Pattern.compile("<a\\b[^>]*href=[\"']([^\"']+\\.html)[\"'][^>]*>([^<]*)</a>");
        Matcher m = p.matcher(html);
        int count = 0;
        while (m.find() && count < 10) {
            if (count > 0) sb.append(", ");
            String href = m.group(1);
            if (!href.startsWith("http")) {
                sb.append(href);
                count++;
            }
        }
        return sb.toString();
    }

    private String extractSections(String html) {
        StringBuilder sb = new StringBuilder();
        // 提取带 id 或 class 的 section/div 元素
        Pattern p = Pattern.compile("<(section|div|main|article|header|footer)\\b([^>]*?(?:id|class)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*)>");
        Matcher m = p.matcher(html);
        int count = 0;
        while (m.find() && count < 8) {
            if (count > 0) sb.append(", ");
            String tag = m.group(1);
            String idClass = m.group(3);
            // 提取第一个有意义的选择器
            String[] parts = idClass.split("\\s+");
            sb.append(tag);
            if (parts.length > 0 && !parts[0].isEmpty()) {
                sb.append(".").append(parts[0]);
            }
            count++;
        }
        return sb.toString();
    }

    private String extractHeadings(String html) {
        StringBuilder sb = new StringBuilder();
        Pattern p = Pattern.compile("<h([1-3])\\b[^>]*>([^<]*)</h\\1>");
        Matcher m = p.matcher(html);
        int count = 0;
        while (m.find() && count < 8) {
            if (count > 0) sb.append(" | ");
            sb.append("h").append(m.group(1)).append(" \"").append(m.group(2).trim()).append("\"");
            count++;
        }
        return sb.toString();
    }

    private String extractCssVariables(String html) {
        StringBuilder sb = new StringBuilder();
        Pattern rootPattern = Pattern.compile("(?s):root\\s*\\{([^}]*)\\}");
        Matcher m = rootPattern.matcher(html);
        if (m.find()) {
            String rootBlock = m.group(1);
            Pattern varPattern = Pattern.compile("(--[\\w-]+)\\s*:");
            Matcher varMatcher = varPattern.matcher(rootBlock);
            int count = 0;
            while (varMatcher.find() && count < 15) {
                if (count > 0) sb.append(", ");
                sb.append(varMatcher.group(1));
                count++;
            }
        }
        return sb.toString();
    }

    private String callLLM(String systemPrompt, String userPrompt, String modelName, SseEmitter emitter) {
        if (emitter != null) {
            String response = chatModelService.streamChat(
                    systemPrompt, userPrompt, modelName,
                    token -> {
                        try {
                            emitter.send(SseEmitter.event().name("modify_plan").data(token));
                        } catch (Exception ignored) {
                        }
                    }
            );
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
}
