package com.example.websitemother.node;

import com.example.websitemother.prompt.PromptTemplates;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.service.CodeQualityScorer;
import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node 4: Vue 代码生成
 * 将需求与素材传给大模型，生成完整的单文件 Vue 3 代码
 * 支持分块增量修改：重试时只让 LLM 修改一个代码块（template/script/style）
 */
@Slf4j
@Component
public class VueGenerator implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Resource
    private CodeQualityScorer qualityScorer;

    private static final Pattern BLOCK_PATTERN = Pattern.compile("BLOCK:\\s*(template|script|style)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPLATE_BLOCK = Pattern.compile("(?s)<template>.*?</template>");
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?s)<script.*?>.*?</script>");
    private static final Pattern STYLE_BLOCK = Pattern.compile("(?s)<style.*?>.*?</style>");

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        String currentInput = state.currentInput();
        String assetsJson = state.assetsJson();
        String reviewFeedback = state.reviewFeedback();
        String previousVueCode = state.vueCode();

        boolean isRetry = state.retryCount() > 0;
        log.info("[VueGenerator] 开始生成Vue代码, retryCount={}, isRetry={}", state.retryCount(), isRetry);

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
                PromptTemplates.vueGeneratorUser(requirement.toString(), assetsJson, reviewFeedback, previousVueCode),
                ChatModelService.ModelType.MAX
        );

        String cleaned = response.trim();

        // 重试场景：尝试分块增量替换
        String finalCode;
        if (isRetry && previousVueCode != null && !previousVueCode.isBlank()) {
            String patched = tryPatchBlock(previousVueCode, cleaned);
            if (patched != null) {
                log.info("[VueGenerator] 分块增量修改成功");
                finalCode = patched;
            } else {
                log.warn("[VueGenerator] 分块增量修改失败，回退到完整输出");
                finalCode = stripMarkdown(cleaned);
            }
        } else {
            finalCode = stripMarkdown(cleaned);
        }

        // 自动修复保留字问题
        finalCode = fixReservedWords(finalCode);

        // 代码质量评分
        CodeQualityScorer.QualityReport report = qualityScorer.score(finalCode);
        log.info("[VueGenerator] Vue代码生成完成, 长度={}, 质量评分={}/100, {}",
                finalCode.length(), report.score(), report.details());

        return Map.of(ProjectState.VUE_CODE, finalCode);
    }

    /**
     * 尝试从 LLM 响应中解析 BLOCK 和 CODE，执行分块替换
     * @return 替换成功返回新代码，失败返回 null
     */
    private String tryPatchBlock(String originalCode, String llmResponse) {
        // 1. 解析 BLOCK 类型
        Matcher blockMatcher = BLOCK_PATTERN.matcher(llmResponse);
        if (!blockMatcher.find()) {
            log.debug("[VueGenerator] 未解析到 BLOCK 标记");
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
            log.debug("[VueGenerator] CODE 内容为空");
            return null;
        }

        // 3. 在原代码中查找并替换对应块
        Pattern targetPattern;
        switch (blockType) {
            case "template" -> targetPattern = TEMPLATE_BLOCK;
            case "script" -> targetPattern = SCRIPT_BLOCK;
            case "style" -> targetPattern = STYLE_BLOCK;
            default -> {
                log.debug("[VueGenerator] 未知的 BLOCK 类型: {}", blockType);
                return null;
            }
        }

        Matcher targetMatcher = targetPattern.matcher(originalCode);
        if (!targetMatcher.find()) {
            log.debug("[VueGenerator] 原代码中未找到 {} 块", blockType);
            return null;
        }

        String patched = targetMatcher.replaceFirst(Matcher.quoteReplacement(newBlockCode));
        log.info("[VueGenerator] 成功替换 {} 块", blockType);
        return patched;
    }

    /**
     * 清理 markdown 代码块标记
     */
    private String stripMarkdown(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```vue")) {
            cleaned = cleaned.substring("```vue".length());
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length());
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length());
        }
        return cleaned.trim();
    }

    /**
     * 自动修复 JavaScript 保留字作为变量名的问题
     * LLM 经常把 function/class 等保留字用作 v-for 迭代变量，导致编译失败
     */
    private String fixReservedWords(String code) {
        String fixed = code;
        // 修复 function 作为 v-for 迭代变量
        if (fixed.contains("v-for=\"(function,") || fixed.contains("v-for='(function,'")) {
            fixed = fixed.replace("v-for=\"(function,", "v-for=\"(func,");
            fixed = fixed.replace("v-for='(function,'", "v-for='(func,'");
            // 同时替换模板中该变量的引用（只在插值表达式和属性绑定中）
            fixed = fixed.replace("{{ function.", "{{ func.");
            fixed = fixed.replace(":src=\"function.", ":src=\"func.");
            fixed = fixed.replace("v-bind:src=\"function.", "v-bind:src=\"func.");
            log.info("[VueGenerator] 自动修复：将保留字 'function' 替换为 'func'");
        }
        return fixed;
    }
}
