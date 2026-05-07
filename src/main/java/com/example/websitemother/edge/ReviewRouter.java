package com.example.websitemother.edge;

import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

/**
 * 代码审查路由条件边
 * reviewPassed=true -> sub_page_generator
 * reviewPassed=false && retryCount < MAX_RETRY -> html_generator (重试)
 * retryCount >= MAX_RETRY -> END (失败)
 */
@Slf4j
@Component
public class ReviewRouter implements EdgeAction<ProjectState> {

    public static final String TARGET_END = "__end__";
    public static final String TARGET_SUB_PAGE = "sub_page_generator";
    public static final String TARGET_RETRY = "html_generator";
    public static final int MAX_RETRY = 2;

    @Override
    public String apply(ProjectState state) throws Exception {
        boolean reviewPassed = state.reviewPassed();
        String reviewFeedback = state.reviewFeedback();
        int retryCount = state.retryCount();

        log.info("[ReviewRouter] 路由判断: reviewPassed={}, retryCount={}", reviewPassed, retryCount);

        if (reviewPassed) {
            log.info("[ReviewRouter] 主页代码审查通过，进入子页面生成");
            return TARGET_SUB_PAGE;
        }

        if (retryCount < MAX_RETRY) {
            log.warn("[ReviewRouter] 主页审查未通过，进入第 {} 次重试。原因: {}", retryCount + 1, reviewFeedback);
            return TARGET_RETRY;
        }

        log.warn("[ReviewRouter] 主页审查未通过，已达最大重试次数 {}，结束工作流。原因: {}", MAX_RETRY, reviewFeedback);
        return TARGET_END;
    }
}
