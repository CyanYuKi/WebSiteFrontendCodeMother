package com.example.websitemother.node;

import com.example.websitemother.prompt.PromptTemplates;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.state.ProjectState;
import com.example.websitemother.controller.SseEmitterStore;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.ParseError;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.List;
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

        // 0. 末尾截断检测：如果文件以未完成的标签/字符串/注释结尾，说明 LLM 输出被截断
        String trimmed = htmlCode.trim();
        char lastChar = trimmed.charAt(trimmed.length() - 1);
        // 正常 HTML 应该以 > 或 } 或 ) 或 文本内容结尾
        // 如果以 < 或 = 或 ( 或 [ 或 { 结尾，说明截断了
        if (lastChar == '<' || lastChar == '=' || lastChar == '(' || lastChar == '[' || lastChar == '{') {
            return "代码在末尾处被截断（未完成标签/表达式）";
        }
        // 检查末尾 200 字符内是否有未闭合的标签开头
        String tail = trimmed.substring(Math.max(0, trimmed.length() - 200));
        if (tail.lastIndexOf('<') > tail.lastIndexOf('>')) {
            // 末尾存在未闭合的标签
            String unclosed = tail.substring(tail.lastIndexOf('<'));
            if (unclosed.length() < 50 && !unclosed.contains(">")) {
                return "代码在末尾标签处被截断: " + unclosed.substring(0, Math.min(20, unclosed.length()));
            }
        }
        // 检查末尾是否有未闭合的双引号或单引号
        int lastDquote = trimmed.lastIndexOf('"');
        int lastSquote = trimmed.lastIndexOf('\'');
        if (lastDquote > trimmed.lastIndexOf('>', lastDquote) ||
            lastSquote > trimmed.lastIndexOf('>', lastSquote)) {
            // 引号在最后一个 > 之后，可能是未闭合的属性值
            // 但需要更精确的判断：引号数量是否为奇数（在末尾区域内）
            String tail2 = trimmed.substring(Math.max(0, trimmed.length() - 500));
            long dquotes = tail2.chars().filter(c -> c == '"').count();
            long squotes = tail2.chars().filter(c -> c == '\'').count();
            if (dquotes % 2 != 0 || squotes % 2 != 0) {
                return "代码在属性值引号处被截断";
            }
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

        // 7. body 内容截断检测：body 内实质内容过少说明生成被截断
        if (bodyContent != null) {
            String stripped = bodyContent.replaceAll("\\s+", "");
            if (stripped.length() < 200) {
                return "body 内容被截断，实质内容过少（" + stripped.length() + "字符）";
            }
        }

        // 8. jsoup HTML Linter 检查：使用真实 HTML 解析器验证语法
        String jsoupError = jsoupLintCheck(htmlCode);
        if (jsoupError != null) {
            return jsoupError;
        }

        return null;
    }

    /**
     * 使用 jsoup 解析 HTML 并检查语法错误
     * @return null 表示无错误；否则返回错误描述
     */
    private String jsoupLintCheck(String htmlCode) {
        try {
            Parser parser = Parser.htmlParser().setTrackErrors(10);
            Document doc = Jsoup.parse(htmlCode, "", parser);
            List<ParseError> errors = parser.getErrors();
            if (errors != null && !errors.isEmpty()) {
                StringBuilder sb = new StringBuilder("jsoup检测到HTML语法错误: ");
                int count = Math.min(errors.size(), 3);
                for (int i = 0; i < count; i++) {
                    ParseError err = errors.get(i);
                    sb.append("[").append(i + 1).append("]").append(err.toString());
                    if (i < count - 1) sb.append("; ");
                }
                if (errors.size() > 3) {
                    sb.append(" 等共").append(errors.size()).append("处错误");
                }
                String msg = sb.toString();
                log.warn("[CodeReviewer] {}", msg);
                return msg;
            }

            // 额外检查：解析后的 html/body/head 标签数量是否异常
            int htmlCount = doc.getElementsByTag("html").size();
            int bodyCount = doc.getElementsByTag("body").size();
            int headCount = doc.getElementsByTag("head").size();
            if (htmlCount != 1) return "jsoup解析后发现<html>标签数量为" + htmlCount + "（应为1）";
            if (headCount != 1) return "jsoup解析后发现<head>标签数量为" + headCount + "（应为1）";
            if (bodyCount != 1) return "jsoup解析后发现<body>标签数量为" + bodyCount + "（应为1）";

            return null;
        } catch (Exception e) {
            log.warn("[CodeReviewer] jsoup解析异常: {}", e.getMessage());
            return "jsoup解析异常: " + e.getMessage();
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

    private static final Pattern HEAD_OPEN = Pattern.compile("<head\\b");
    private static final Pattern HEAD_CLOSE = Pattern.compile("</head>");
    private static final Pattern HTML_OPEN = Pattern.compile("(?<![a-z])<html\\b");
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
        // 如果代码末尾存在截断特征，不进行自动修复（补全标签会掩盖截断问题）
        String trimmed = htmlCode.trim();
        char lastChar = trimmed.charAt(trimmed.length() - 1);
        if (lastChar == '<' || lastChar == '=' || lastChar == '(' || lastChar == '[' || lastChar == '{') {
            return htmlCode; // 截断，不修复
        }
        String tail = trimmed.substring(Math.max(0, trimmed.length() - 200));
        if (tail.lastIndexOf('<') > tail.lastIndexOf('>')) {
            String unclosed = tail.substring(tail.lastIndexOf('<'));
            if (unclosed.length() < 50 && !unclosed.contains(">")) {
                return htmlCode; // 截断，不修复
            }
        }

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
