package com.example.websitemother.edge;

import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Edge: 新页面路由
 * 检测到新页面 → 触发 SubPageGenerator 生成
 * 无新页面 → 直接结束
 */
@Slf4j
@Component
public class NewPageRouter implements EdgeAction<ProjectState> {

    public static final String TARGET_SUB_PAGE = "sub_page_generator";
    public static final String TARGET_END = "__end__";

    @Override
    public String apply(ProjectState state) {
        List<String> newPages = state.newPagesDetected();
        if (!newPages.isEmpty()) {
            log.info("[NewPageRouter] 检测到 {} 个新页面需要生成: {}", newPages.size(), newPages);
            return TARGET_SUB_PAGE;
        }
        log.info("[NewPageRouter] 无新页面，修改完成");
        return TARGET_END;
    }
}
