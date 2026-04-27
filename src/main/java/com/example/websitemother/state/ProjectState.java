package com.example.websitemother.state;

import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * LangGraph 工作流全局状态实体
 * 继承 AgentState，在整个 StateGraph 中流转
 */
public class ProjectState extends AgentState {

    public static final String CURRENT_INPUT = "currentInput";
    public static final String INTENT_TYPE = "intentType";
    public static final String CHAT_REPLY = "chatReply";
    public static final String CHECKLIST = "checklist";
    public static final String USER_ANSWERS = "userAnswers";
    public static final String ASSETS_JSON = "assetsJson";
    public static final String HTML_CODE = "htmlCode";
    public static final String DESIGN_CONCEPT = "designConcept";
    public static final String DESIGN_TOKENS = "designTokens";
    public static final String REVIEW_PASSED = "reviewPassed";
    public static final String REVIEW_FEEDBACK = "reviewFeedback";
    public static final String RETRY_COUNT = "retryCount";
    public static final String SESSION_ID = "sessionId";
    public static final String MODEL = "model";
    public static final String PAGES = "pages";

    public ProjectState(Map<String, Object> initData) {
        super(initData);
    }

    public String currentInput() {
        return value(CURRENT_INPUT).map(Object::toString).orElse("");
    }

    public String intentType() {
        return value(INTENT_TYPE).map(Object::toString).orElse("");
    }

    public String chatReply() {
        return value(CHAT_REPLY).map(Object::toString).orElse("");
    }

    public String checklist() {
        return value(CHECKLIST).map(Object::toString).orElse("");
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> userAnswers() {
        return (Map<String, String>) value(USER_ANSWERS).orElse(new HashMap<>());
    }

    public String assetsJson() {
        return value(ASSETS_JSON).map(Object::toString).orElse("");
    }

    public String htmlCode() {
        return value(HTML_CODE).map(Object::toString).orElse("");
    }

    public String designConcept() {
        return value(DESIGN_CONCEPT).map(Object::toString).orElse("");
    }

    public String designTokens() {
        return value(DESIGN_TOKENS).map(Object::toString).orElse("");
    }

    public boolean reviewPassed() {
        return value(REVIEW_PASSED).map(v -> Boolean.parseBoolean(v.toString())).orElse(false);
    }

    public String reviewFeedback() {
        return value(REVIEW_FEEDBACK).map(Object::toString).orElse("");
    }

    public int retryCount() {
        return value(RETRY_COUNT).map(v -> {
            if (v instanceof Number) return ((Number) v).intValue();
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }).orElse(0);
    }

    public String sessionId() {
        return value(SESSION_ID).map(Object::toString).orElse("");
    }
}
