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
 * Node 5: 代码审查
 * 让大模型检查 vueCode 是否完整、是否有严重语法错误
 */
@Slf4j
@Component
public class CodeReviewer implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        String vueCode = state.vueCode();
        int retryCount = state.retryCount();

        log.info("[CodeReviewer] 开始审查代码, currentRetry={}", retryCount);

        // 1. 快速结构检查
        String fastCheckResult = fastStructureCheck(vueCode);
        if (fastCheckResult == null) {
            log.info("[CodeReviewer] 快速检查通过");
            return Map.of(
                    ProjectState.REVIEW_PASSED, true,
                    ProjectState.REVIEW_FEEDBACK, "代码结构完整，通过自动化审查。",
                    ProjectState.RETRY_COUNT, retryCount + 1
            );
        }

        // 2. 尝试自动修复
        String fixedCode = autoFixStructure(vueCode);
        if (!fixedCode.equals(vueCode)) {
            String reCheck = fastStructureCheck(fixedCode);
            if (reCheck == null) {
                log.info("[CodeReviewer] 自动修复成功: {}", fastCheckResult);
                return Map.of(
                        ProjectState.REVIEW_PASSED, true,
                        ProjectState.REVIEW_FEEDBACK, "自动修复: " + fastCheckResult,
                        ProjectState.RETRY_COUNT, retryCount + 1,
                        ProjectState.VUE_CODE, fixedCode
                );
            }
        }

        // 3. 自动修复不了，返回失败（不再调用 LLM 审查，避免误判）
        int newRetryCount = retryCount + 1;
        log.warn("[CodeReviewer] 结构检查未通过且自动修复失败: {}", fastCheckResult);
        return Map.of(
                ProjectState.REVIEW_PASSED, false,
                ProjectState.REVIEW_FEEDBACK, "结构检查未通过: " + fastCheckResult + "。请稍后重试或手动调整。",
                ProjectState.RETRY_COUNT, newRetryCount
        );
    }

    /**
     * 快速结构检查：基于纯文本分析捕获 Vue SFC 的致命结构缺陷
     * 只检查会导致编译失败的客观问题，避免 LLM 的主观误判
     * @return null 表示检查通过；否则返回失败原因
     */
    private String fastStructureCheck(String vueCode) {
        if (vueCode == null || vueCode.isBlank()) {
            return "代码为空";
        }

        String lower = vueCode.toLowerCase();

        // 1. 基本标签存在性
        if (!lower.contains("<template")) return "缺少 <template> 标签";
        if (!lower.contains("<script"))  return "缺少 <script> 标签";
        if (!lower.contains("<style"))   return "缺少 <style> 标签";

        // 2. 标签闭合检查
        if (!lower.contains("</template>")) return "<template> 标签未闭合";
        if (!lower.contains("</script>"))   return "<script> 标签未闭合";
        if (!lower.contains("</style>"))    return "<style> 标签未闭合";

        // 3. 标签开启/闭合数量匹配（防止嵌套错乱）
        if (countOccurrences(lower, "<template") != countOccurrences(lower, "</template>")) {
            return "<template> 标签开启/闭合数量不匹配";
        }
        if (countOccurrences(lower, "<script") != countOccurrences(lower, "</script>")) {
            return "<script> 标签开启/闭合数量不匹配";
        }
        if (countOccurrences(lower, "<style") != countOccurrences(lower, "</style>")) {
            return "<style> 标签开启/闭合数量不匹配";
        }

        // 4. script setup 语法（Vue 3 项目要求）
        if (!lower.contains("<script setup") && !lower.contains("setup")) {
            return "未使用 <script setup> 语法";
        }

        // 5. 残留的 markdown 代码块标记
        if (vueCode.contains("```vue") || vueCode.contains("```")) {
            return "代码中包含残留的 markdown 标记 ```";
        }

        // 6. 提取 template 块内容，只对 template 内部做引号和尖括号检查
        // （script 块中的 JS 比较运算符如 > < 会干扰全文件统计）
        String templateContent = extractTemplateBlock(vueCode);
        if (templateContent != null) {
            // 6a. template 块内双引号匹配
            long doubleQuotes = templateContent.chars().filter(c -> c == '"').count();
            if (doubleQuotes % 2 != 0) {
                return "template 中存在未闭合的双引号";
            }
            // 6b. template 块内尖括号匹配（HTML 标签完整性）
            long lt = templateContent.chars().filter(c -> c == '<').count();
            long gt = templateContent.chars().filter(c -> c == '>').count();
            if (lt != gt) {
                return "template 中 HTML 尖括号数量不匹配，可能存在未闭合标签";
            }
        }

        return null;
    }

    private long countOccurrences(String str, String sub) {
        return str.split(sub, -1).length - 1;
    }

    /**
     * 提取 template 块的内容（不包括 <template> 和 </template> 标签本身）
     */
    private String extractTemplateBlock(String vueCode) {
        if (vueCode == null) return null;
        int start = vueCode.toLowerCase().indexOf("<template>");
        if (start < 0) {
            start = vueCode.toLowerCase().indexOf("<template ");
        }
        if (start < 0) return null;
        // 找到开始标签的结束位置
        int contentStart = vueCode.indexOf(">", start) + 1;
        int end = vueCode.toLowerCase().lastIndexOf("</template>");
        if (end < 0 || end <= contentStart) return null;
        return vueCode.substring(contentStart, end);
    }

    /**
     * 自动修复常见的简单结构问题
     * @return 修复后的代码（如果无法修复则返回原代码）
     */
    private String autoFixStructure(String vueCode) {
        String fixed = vueCode;
        String lower = vueCode.toLowerCase();
        boolean modified = false;

        // 1. 自动补全未闭合的 template 标签
        if (lower.contains("<template") && !lower.contains("</template>")) {
            fixed = fixed + "\n</template>\n";
            modified = true;
        }

        // 2. 自动补全未闭合的 script 标签
        if (lower.contains("<script") && !lower.contains("</script>")) {
            int styleIdx = lower.indexOf("<style");
            if (styleIdx > 0) {
                fixed = fixed.substring(0, styleIdx) + "\n</script>\n" + fixed.substring(styleIdx);
            } else {
                fixed = fixed + "\n</script>\n";
            }
            modified = true;
        }

        // 3. 自动补全未闭合的 style 标签
        if (lower.contains("<style") && !lower.contains("</style>")) {
            fixed = fixed + "\n</style>\n";
            modified = true;
        }

        // 4. 清理残留的 markdown 代码块标记
        if (fixed.contains("```vue")) {
            fixed = fixed.replace("```vue", "").trim();
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
