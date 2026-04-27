package com.example.websitemother.node;

import com.example.websitemother.prompt.PromptTemplates;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.state.ProjectState;
import com.example.websitemother.controller.SseEmitterStore;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node: 代码审查
 * 检查 htmlCode 是否完整、是否有严重结构错误
 */
@Slf4j
@Component
public class CodeReviewer implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        sendStage(SseEmitterStore.get(state.sessionId()), "code_reviewer");

        String htmlCode = state.htmlCode();
        int retryCount = state.retryCount();

        log.info("[CodeReviewer] 开始审查HTML代码, currentRetry={}", retryCount);

        // 1. 快速结构检查
        String fastCheckResult = fastStructureCheck(htmlCode);
        if (fastCheckResult == null) {
            log.info("[CodeReviewer] 快速检查通过");
            return Map.of(
                    ProjectState.REVIEW_PASSED, true,
                    ProjectState.REVIEW_FEEDBACK, "HTML结构完整，通过自动化审查。",
                    ProjectState.RETRY_COUNT, retryCount + 1
            );
        }

        // 2. 尝试自动修复
        String fixedCode = autoFixStructure(htmlCode);
        if (!fixedCode.equals(htmlCode)) {
            String reCheck = fastStructureCheck(fixedCode);
            if (reCheck == null) {
                log.info("[CodeReviewer] 自动修复成功: {}", fastCheckResult);
                return Map.of(
                        ProjectState.REVIEW_PASSED, true,
                        ProjectState.REVIEW_FEEDBACK, "自动修复: " + fastCheckResult,
                        ProjectState.RETRY_COUNT, retryCount + 1,
                        ProjectState.HTML_CODE, fixedCode
                );
            }
        }

        // 3. 自动修复不了，返回失败
        int newRetryCount = retryCount + 1;
        log.warn("[CodeReviewer] 结构检查未通过且自动修复失败: {}", fastCheckResult);
        return Map.of(
                ProjectState.REVIEW_PASSED, false,
                ProjectState.REVIEW_FEEDBACK, "结构检查未通过: " + fastCheckResult + "。请稍后重试或手动调整。",
                ProjectState.RETRY_COUNT, newRetryCount
        );
    }

    /**
     * 快速结构检查：基于纯文本分析捕获 HTML 的致命结构缺陷
     * 只检查会导致页面无法渲染的客观问题
     * @return null 表示检查通过；否则返回失败原因
     */
    private String fastStructureCheck(String htmlCode) {
        if (htmlCode == null || htmlCode.isBlank()) {
            return "代码为空";
        }

        String lower = htmlCode.toLowerCase();

        // 1. 基本 HTML 结构存在性
        if (!lower.contains("<!doctype html>")) return "缺少 <!DOCTYPE html> 声明";
        if (!lower.contains("<html")) return "缺少 <html> 标签";
        if (!lower.contains("<head")) return "缺少 <head> 标签";
        if (!lower.contains("<body")) return "缺少 <body> 标签";

        // 2. 标签闭合检查
        if (!lower.contains("</html>")) return "<html> 标签未闭合";
        if (!lower.contains("</head>")) return "<head> 标签未闭合";
        if (!lower.contains("</body>")) return "<body> 标签未闭合";

        // 3. 标签开启/闭合数量匹配（使用正则避免 <header> 等标签误判为 <head>）
        if (countTagOpen(lower, HTML_OPEN) != countTagClose(lower, HTML_CLOSE)) {
            return "<html> 标签开启/闭合数量不匹配";
        }
        if (countTagOpen(lower, HEAD_OPEN) != countTagClose(lower, HEAD_CLOSE)) {
            return "<head> 标签开启/闭合数量不匹配";
        }
        if (countTagOpen(lower, BODY_OPEN) != countTagClose(lower, BODY_CLOSE)) {
            return "<body> 标签开启/闭合数量不匹配";
        }

        // 4. CSS 变量设计系统检查
        if (!lower.contains(":root")) {
            return "缺少 :root CSS 变量设计系统";
        }

        // 5. 残留的 markdown 代码块标记
        if (htmlCode.contains("```html") || htmlCode.contains("```")) {
            return "代码中包含残留的 markdown 标记 ```";
        }

        // 6. body 内引号匹配检查
        String bodyContent = extractBodyBlock(htmlCode);
        if (bodyContent != null) {
            long doubleQuotes = bodyContent.chars().filter(c -> c == '"').count();
            if (doubleQuotes % 2 != 0) {
                return "body 中存在未闭合的双引号";
            }
        }

        return null;
    }

    private void sendStage(SseEmitter emitter, String stage) {
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("stage").data(stage));
            } catch (Exception ignored) {
            }
        }
    }

    private static final Pattern HEAD_OPEN = Pattern.compile("<head\\b");
    private static final Pattern HEAD_CLOSE = Pattern.compile("</head>");
    private static final Pattern HTML_OPEN = Pattern.compile("<html\\b");
    private static final Pattern HTML_CLOSE = Pattern.compile("</html>");
    private static final Pattern BODY_OPEN = Pattern.compile("<body\\b");
    private static final Pattern BODY_CLOSE = Pattern.compile("</body>");

    private long countOccurrences(String str, String sub) {
        return str.split(sub, -1).length - 1;
    }

    private long countTagOpen(String str, Pattern openPattern) {
        Matcher m = openPattern.matcher(str);
        long count = 0;
        while (m.find()) count++;
        return count;
    }

    private long countTagClose(String str, Pattern closePattern) {
        Matcher m = closePattern.matcher(str);
        long count = 0;
        while (m.find()) count++;
        return count;
    }

    /**
     * 提取 body 块的内容（不包括 <body> 和 </body> 标签本身）
     */
    private String extractBodyBlock(String htmlCode) {
        if (htmlCode == null) return null;
        int start = htmlCode.toLowerCase().indexOf("<body>");
        if (start < 0) {
            start = htmlCode.toLowerCase().indexOf("<body ");
        }
        if (start < 0) return null;
        int contentStart = htmlCode.indexOf(">", start) + 1;
        int end = htmlCode.toLowerCase().lastIndexOf("</body>");
        if (end < 0 || end <= contentStart) return null;
        return htmlCode.substring(contentStart, end);
    }

    /**
     * 自动修复常见的简单结构问题
     * @return 修复后的代码（如果无法修复则返回原代码）
     */
    private String autoFixStructure(String htmlCode) {
        String fixed = htmlCode;
        String lower = htmlCode.toLowerCase();
        boolean modified = false;

        // 1. 自动补全 DOCTYPE
        if (!lower.contains("<!doctype html>")) {
            fixed = "<!DOCTYPE html>\n" + fixed;
            modified = true;
        }

        // 2. 自动补全未闭合的 html 标签
        if (lower.contains("<html") && !lower.contains("</html>")) {
            fixed = fixed + "\n</html>\n";
            modified = true;
        }

        // 3. 自动补全未闭合的 head 标签
        if (lower.contains("<head") && !lower.contains("</head>")) {
            int bodyIdx = lower.indexOf("<body");
            if (bodyIdx > 0) {
                fixed = fixed.substring(0, bodyIdx) + "\n</head>\n" + fixed.substring(bodyIdx);
            } else {
                fixed = fixed + "\n</head>\n";
            }
            modified = true;
        }

        // 4. 自动补全未闭合的 body 标签
        if (lower.contains("<body") && !lower.contains("</body>")) {
            fixed = fixed + "\n</body>\n";
            modified = true;
        }

        // 5. 清理残留的 markdown 代码块标记
        if (fixed.contains("```html")) {
            fixed = fixed.replace("```html", "").trim();
            modified = true;
        }
        if (fixed.contains("```")) {
            fixed = fixed.replace("```", "").trim();
            modified = true;
        }

        if (modified) {
            log.info("[CodeReviewer] 执行自动修复");
        }
        return fixed;
    }
}
