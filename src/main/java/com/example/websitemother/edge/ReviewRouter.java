package com.example.websitemother.edge;

import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

/**
 * 代码审查路由条件边
 * reviewPassed=true -> END
 * reviewPassed=false && retryCount < 3 -> vue_generator (循环)
 * retryCount >= 3 -> END (失败)
 */
@Slf4j
@Component
public class ReviewRouter implements EdgeAction<ProjectState> {

    public static final String TARGET_END = "__end__";
    public static final String TARGET_RETRY = "html_generator";
    public static final int MAX_RETRY = 3;

    @Override
    public String apply(ProjectState state) throws Exception {
        boolean reviewPassed = state.reviewPassed();
        int retryCount = state.retryCount();
        String reviewFeedback = state.reviewFeedback();

        log.info("[ReviewRouter] 路由判断: reviewPassed={}, retryCount={}", reviewPassed, retryCount);

        if (reviewPassed) {
            log.info("[ReviewRouter] 代码审查通过，结束工作流");
            return TARGET_END;
        }

        if (retryCount < MAX_RETRY) {
            log.warn("[ReviewRouter] 审查未通过，返回HtmlGenerator重试 (retryCount={}, feedback={})",
                    retryCount, reviewFeedback);
            return TARGET_RETRY;
        }

        log.warn("[ReviewRouter] 已达最大重试次数({})，强制结束。未通过原因: {}", MAX_RETRY, reviewFeedback);
        return TARGET_END;
    }
}
