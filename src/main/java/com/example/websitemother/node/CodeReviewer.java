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

        String response = chatModelService.chat(
                PromptTemplates.CODE_REVIEWER_SYSTEM,
                PromptTemplates.codeReviewerUser(vueCode)
        );

        boolean reviewPassed = false;
        String reviewFeedback = "";

        // 解析审查结果
        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.startsWith("RESULT:")) {
                String value = line.substring("RESULT:".length()).trim().toUpperCase();
                reviewPassed = value.contains("PASS");
            } else if (line.startsWith("FEEDBACK:")) {
                reviewFeedback = line.substring("FEEDBACK:".length()).trim();
            }
        }

        int newRetryCount = retryCount + 1;

        log.info("[CodeReviewer] 审查结果: passed={}, newRetryCount={}", reviewPassed, newRetryCount);

        return Map.of(
                ProjectState.REVIEW_PASSED, reviewPassed,
                ProjectState.REVIEW_FEEDBACK, reviewFeedback,
                ProjectState.RETRY_COUNT, newRetryCount
        );
    }
}
