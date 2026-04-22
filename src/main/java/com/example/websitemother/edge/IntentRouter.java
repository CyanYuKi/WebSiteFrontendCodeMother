package com.example.websitemother.edge;

import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

/**
 * 意图路由条件边
 * chat -> END
 * create -> checklist_builder
 */
@Slf4j
@Component
public class IntentRouter implements EdgeAction<ProjectState> {

    public static final String TARGET_CHAT = "__end__";
    public static final String TARGET_CREATE = "checklist_builder";

    @Override
    public String apply(ProjectState state) throws Exception {
        String intentType = state.intentType();
        log.info("[IntentRouter] 路由判断: intentType={}", intentType);

        if ("chat".equals(intentType)) {
            return TARGET_CHAT;
        }
        return TARGET_CREATE;
    }
}
