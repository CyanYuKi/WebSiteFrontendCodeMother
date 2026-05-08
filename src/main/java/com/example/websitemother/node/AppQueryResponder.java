package com.example.websitemother.node;

import com.example.websitemother.prompt.PromptTemplates;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node: 应用查询响应器
 * 当用户询问当前项目的详情时（如"有哪些页面"、"设计风格是什么"等），
 * 读取项目状态构建上下文，调用 LLM 生成精准回答。
 */
@Slf4j
@Component
public class AppQueryResponder implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        String userQuestion = state.currentInput();
        String modelName = state.model();
        String chatReply = state.chatReply(); // IntentAnalyzer 的简短回应

        // 构建项目上下文
        String projectContext = buildProjectContext(state);

        log.info("[AppQueryResponder] 回答用户查询: question={}, contextSize={}",
                userQuestion, projectContext.length());

        String systemPrompt = "你是一个专业的网站项目助手。用户正在查看一个已生成的网站项目，" +
                "你需要根据提供的项目上下文信息，回答用户关于该项目的问题。\n\n" +
                "===== 回答规则 =====\n" +
                "1. 只回答与项目相关的问题，基于提供的上下文信息如实回答\n" +
                "2. 如果上下文信息不足以回答，坦诚告知用户而非编造\n" +
                "3. 回答应清晰、结构化，使用中文\n" +
                "4. 如果用户询问代码细节，可以引用具体的 CSS 变量、HTML 结构等信息\n" +
                "5. 保持友好和专业的语气\n" +
                "6. 回答尽量简洁（控制在200字以内），除非用户明确要求详细说明";

        String userPrompt = "【用户问题】\n" + userQuestion + "\n\n" +
                "【项目上下文信息】\n" + projectContext;

        String answer = chatModelService.chat(systemPrompt, userPrompt, modelName);
        log.info("[AppQueryResponder] 回答生成完成, 长度={}", answer.length());

        return Map.of(ProjectState.CHAT_REPLY, answer.trim());
    }

    /**
     * 从项目状态中提取上下文信息，供 LLM 回答问题时参考
     */
    private String buildProjectContext(ProjectState state) {
        StringBuilder sb = new StringBuilder();

        // 1. 页面清单
        @SuppressWarnings("unchecked")
        Map<String, String> pages = (Map<String, String>) state.data()
                .getOrDefault(ProjectState.PAGES, Map.of());
        String indexHtml = pages.getOrDefault("index.html", state.htmlCode());

        if (!pages.isEmpty()) {
            sb.append("【页面清单】\n");
            for (String name : pages.keySet()) {
                String content = pages.get(name);
                int kb = content != null ? content.length() / 1024 : 0;
                sb.append("- ").append(name).append(" (").append(kb).append(" KB)\n");
            }
            sb.append("\n");
        }

        // 2. 设计概念
        String designConcept = state.designConcept();
        if (designConcept != null && !designConcept.isBlank()) {
            sb.append("【设计概念】\n");
            // 尝试提取关键信息（截断过长的 JSON）
            if (designConcept.length() > 2000) {
                sb.append(designConcept.substring(0, 2000)).append("...\n\n");
            } else {
                sb.append(designConcept).append("\n\n");
            }
        }

        // 3. index.html 结构摘要
        if (!indexHtml.isBlank()) {
            sb.append("【首页结构摘要】\n");

            // CSS 变量
            String cssVars = extractCssVariables(indexHtml);
            if (!cssVars.isEmpty()) {
                sb.append("CSS 变量: ").append(cssVars).append("\n");
            }

            // 导航链接
            String navLinks = extractNavLinks(indexHtml);
            if (!navLinks.isEmpty()) {
                sb.append("导航链接: ").append(navLinks).append("\n");
            }

            // 主要区块
            String sections = extractSections(indexHtml);
            if (!sections.isEmpty()) {
                sb.append("主要区块: ").append(sections).append("\n");
            }

            // 标题层级
            String headings = extractHeadings(indexHtml);
            if (!headings.isEmpty()) {
                sb.append("标题: ").append(headings).append("\n");
            }

            // 首页前 3000 字符（供深度查询）
            int previewLen = Math.min(indexHtml.length(), 3000);
            sb.append("\n【首页代码前 ").append(previewLen).append(" 字符】\n");
            sb.append(indexHtml.substring(0, previewLen));
            if (indexHtml.length() > previewLen) {
                sb.append("\n... [代码过长，已截断]");
            }
            sb.append("\n");
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

    private String extractNavLinks(String html) {
        StringBuilder sb = new StringBuilder();
        Pattern p = Pattern.compile("<a\\b[^>]*href=[\"']([^\"']+\\.html)[\"'][^>]*>(?:[^<]*)</a>");
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
        Pattern p = Pattern.compile("<(section|div|main|article|header|footer)\\b[^>]*?(?:id|class)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");
        Matcher m = p.matcher(html);
        int count = 0;
        while (m.find() && count < 10) {
            if (count > 0) sb.append(", ");
            String tag = m.group(1);
            String idClass = m.group(2);
            String[] parts = idClass.split("\\s+");
            sb.append(tag).append(".").append(parts[0]);
            count++;
        }
        return sb.toString();
    }

    private String extractHeadings(String html) {
        StringBuilder sb = new StringBuilder();
        Pattern p = Pattern.compile("<h([1-3])\\b[^>]*>([^<]*)</h\\1>");
        Matcher m = p.matcher(html);
        int count = 0;
        while (m.find() && count < 6) {
            if (count > 0) sb.append(" | ");
            sb.append("h").append(m.group(1)).append(" \"").append(m.group(2).trim()).append("\"");
            count++;
        }
        return sb.toString();
    }
}
