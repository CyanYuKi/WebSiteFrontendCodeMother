package com.example.websitemother.edge;

import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

/**
 * 意图路由条件边
 * chat -> END
 * create -> checklist_builder
 * modify -> END (modify graph 在 /modify-stream 中单独处理)
 */
@Slf4j
@Component
public class IntentRouter implements EdgeAction<ProjectState> {

    public static final String TARGET_CHAT = "__end__";
    public static final String TARGET_CREATE = "checklist_builder";
    public static final String TARGET_QUERY = "app_query_responder";

    @Override
    public String apply(ProjectState state) throws Exception {
        String intentType = state.intentType();
        log.info("[IntentRouter] 路由判断: intentType={}", intentType);

        if ("query".equals(intentType)) {
            log.info("[IntentRouter] query 意图，路由到 AppQueryResponder");
            return TARGET_QUERY;
        }
        if ("chat".equals(intentType) || "modify".equals(intentType)) {
            log.info("[IntentRouter] {} 意图，直接结束 startGraph", intentType);
            return TARGET_CHAT;
        }
        return TARGET_CREATE;
    }
}
