package com.example.websitemother.edge;

import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

/**
 * Edge: 修改流程专用的代码审查路由
 * 与 ReviewRouter 不同，pass 时路由到 new_page_detector 而非直接结束。
 * 这样可以检测修改后的 index.html 是否新增了导航链接。
 */
@Slf4j
@Component
public class ModifyReviewRouter implements EdgeAction<ProjectState> {

    public static final String TARGET_END = "__end__";
    public static final String TARGET_NEW_PAGE_CHECK = "new_page_detector";
    public static final String TARGET_RETRY = "html_modifier";
    private static final int MAX_RETRY = 2;

    @Override
    public String apply(ProjectState state) {
        boolean reviewPassed = state.reviewPassed();
        int retryCount = state.retryCount();

        if (reviewPassed) {
            log.info("[ModifyReviewRouter] 审查通过，进入新页面检测");
            return TARGET_NEW_PAGE_CHECK;
        }
        if (retryCount < MAX_RETRY) {
            log.warn("[ModifyReviewRouter] 审查未通过，重试第 {} 次", retryCount + 1);
            return TARGET_RETRY;
        }
        log.warn("[ModifyReviewRouter] 已达最大重试次数({})，结束修改", MAX_RETRY);
        return TARGET_END;
    }
}
