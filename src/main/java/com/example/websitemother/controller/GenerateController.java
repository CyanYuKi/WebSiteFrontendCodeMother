package com.example.websitemother.controller;

import com.example.websitemother.service.GraphWorkflowService;
import com.example.websitemother.state.ProjectState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 网站生成核心 API
 * - POST /api/generate/start : 启动工作流，返回意图或清单
 * - POST /api/generate/resume: 提交清单答案，继续执行后续生成和审查
 */
@Slf4j
@RestController
@RequestMapping("/api/generate")
public class GenerateController {

    @Resource
    private GraphWorkflowService graphWorkflowService;

    /** 内存级会话状态存储（演示级，生产环境应使用Redis） */
    private final ConcurrentHashMap<String, ProjectState> sessionStore = new ConcurrentHashMap<>();

    /**
     * 启动生成流程
     */
    @PostMapping("/start")
    public StartResponse start(@RequestBody StartRequest request) {
        String input = request.getInput();
        log.info("[GenerateController] /start 收到请求: input={}", input);

        ProjectState state = graphWorkflowService.start(input);

        String sessionId = UUID.randomUUID().toString();
        sessionStore.put(sessionId, state);

        StartResponse response = new StartResponse();
        response.setSessionId(sessionId);
        response.setIntentType(state.intentType());
        response.setChatReply(state.chatReply());
        response.setChecklist(state.checklist());

        log.info("[GenerateController] /start 响应: intentType={}, sessionId={}", state.intentType(), sessionId);
        return response;
    }

    /**
     * 继续生成流程（提交清单答案后）
     */
    @PostMapping("/resume")
    public ResumeResponse resume(@RequestBody ResumeRequest request) {
        String sessionId = request.getSessionId();
        log.info("[GenerateController] /resume 收到请求: sessionId={}", sessionId);

        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        ProjectState state = sessionStore.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("会话不存在或已过期: " + sessionId);
        }

        // 填充用户答案（data() 返回不可变 Map，需复制）
        Map<String, Object> mutableData = new HashMap<>(state.data());
        mutableData.put(ProjectState.USER_ANSWERS, request.getAnswers());
        ProjectState updatedState = new ProjectState(mutableData);

        // 执行第二阶段工作流
        ProjectState finalState = graphWorkflowService.resume(updatedState);

        // 更新存储
        sessionStore.put(sessionId, finalState);

        ResumeResponse response = new ResumeResponse();
        response.setVueCode(finalState.vueCode());
        response.setReviewPassed(finalState.reviewPassed());
        response.setReviewFeedback(finalState.reviewFeedback());
        response.setRetryCount(finalState.retryCount());

        log.info("[GenerateController] /resume 响应: reviewPassed={}, retryCount={}",
                finalState.reviewPassed(), finalState.retryCount());
        return response;
    }

    // ==================== Request / Response DTOs ====================

    @Data
    public static class StartRequest {
        private String input;
    }

    @Data
    public static class StartResponse {
        private String sessionId;
        private String intentType;
        private String chatReply;
        private String checklist;
    }

    @Data
    public static class ResumeRequest {
        private String sessionId;
        private Map<String, String> answers;
    }

    @Data
    public static class ResumeResponse {
        private String vueCode;
        private boolean reviewPassed;
        private String reviewFeedback;
        private int retryCount;
    }
}
