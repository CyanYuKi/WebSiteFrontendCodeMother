package com.example.websitemother.controller;

import com.example.websitemother.entity.User;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.service.GraphWorkflowService;
import com.example.websitemother.service.ProjectStorageService;
import com.example.websitemother.service.ScreenshotService;
import com.example.websitemother.state.ProjectState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    @Resource
    private ProjectStorageService projectStorageService;

    @Resource
    private ScreenshotService screenshotService;

    @Resource
    private ChatModelService chatModelService;

    @Resource(name = "workflowExecutor")
    private ExecutorService executor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 内存级会话状态存储（演示级，生产环境应使用Redis） */
    private final ConcurrentHashMap<String, ProjectState> sessionStore = new ConcurrentHashMap<>();

    /**
     * 启动生成流程
     */
    @PostMapping("/start")
    public StartResponse start(@RequestBody StartRequest request) {
        String input = request.getInput();
        String model = request.getModel();
        log.info("[GenerateController] /start 收到请求: input={}, model={}", input, model);

        if (model != null && !model.isBlank()) {
            chatModelService.setDefaultSmartModelName(model);
        }

        ProjectState state = graphWorkflowService.start(input, model);

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
     * 继续生成流程（SSE 流式版本）
     * 推送 stage / html_token / complete / error 事件
     */
    @PostMapping(value = "/resume-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeStream(@RequestBody ResumeRequest request,
                                   @RequestAttribute("currentUser") User currentUser) {
        String sessionId = request.getSessionId();
        log.info("[GenerateController] /resume-stream 收到请求: sessionId={}", sessionId);

        SseEmitter emitter = new SseEmitter(0L);
        SseEmitterStore.put(sessionId, emitter);

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat-" + sessionId);
            t.setDaemon(true);
            return t;
        });

        executor.execute(() -> {
            try {
                // 每 15 秒发送一次心跳注释，防止代理/浏览器断开空闲 SSE 连接
                heartbeat.scheduleAtFixedRate(() -> {
                    try {
                        emitter.send(SseEmitter.event().comment("keepalive"));
                    } catch (Exception e) {
                        heartbeat.shutdown();
                    }
                }, 15, 15, TimeUnit.SECONDS);

                if (sessionId == null || sessionId.isBlank()) {
                    emitter.send(SseEmitter.event().name("error").data("sessionId 不能为空"));
                    emitter.complete();
                    return;
                }

                ProjectState state = sessionStore.get(sessionId);
                if (state == null) {
                    emitter.send(SseEmitter.event().name("error").data("会话不存在或已过期: " + sessionId));
                    emitter.complete();
                    return;
                }

                Map<String, Object> mutableData = new HashMap<>(state.data());
                mutableData.put(ProjectState.USER_ANSWERS, normalizeAnswers(request.getAnswers()));
                mutableData.put(ProjectState.SESSION_ID, sessionId);
                ProjectState updatedState = new ProjectState(mutableData);

                ProjectState finalState = graphWorkflowService.resume(updatedState);
                sessionStore.put(sessionId, finalState);

                String projectId = projectStorageService.saveProject(finalState, currentUser.getId());
                // 异步生成截图（不阻塞 SSE 响应）
                executor.execute(() -> {
                    try {
                        Path projectDir = Paths.get("generated-projects").resolve(projectId).toAbsolutePath();
                        screenshotService.captureProject(projectDir, projectId);
                    } catch (Exception e) {
                        log.warn("[GenerateController] 异步截图失败, projectId={}", projectId, e);
                    }
                });

                ResumeResponse response = new ResumeResponse();
                response.setProjectId(projectId);
                response.setHtmlCode(finalState.htmlCode());
                response.setDesignConcept(finalState.designConcept());
                response.setDesignTokens(finalState.designTokens());
                response.setReviewPassed(finalState.reviewPassed());
                response.setReviewFeedback(finalState.reviewFeedback());
                response.setRetryCount(finalState.retryCount());
                response.setPreviewUrl("/api/preview/" + projectId + "/");
                response.setPages(extractPageList(finalState));
                log.info("[GenerateController] /resume-stream 完成, projectId={}, previewUrl={}, pages={}", projectId, response.getPreviewUrl(), response.getPages());

                String json = objectMapper.writeValueAsString(response);
                try {
                    emitter.send(SseEmitter.event().name("complete").data(json));
                    emitter.complete();
                } catch (IllegalStateException ex) {
                    // emitter 已被前端取消关闭，无需发送完成事件
                    log.info("[GenerateController] /resume-stream emitter 已关闭, 跳过 complete 事件, sessionId={}", sessionId);
                }
            } catch (Exception e) {
                log.error("[GenerateController] /resume-stream 执行失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {
                }
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            } finally {
                heartbeat.shutdown();
                SseEmitterStore.remove(sessionId);
            }
        });

        return emitter;
    }

    /**
     * 取消正在进行的生成任务
     */
    @PostMapping("/cancel")
    public void cancel(@RequestBody CancelRequest request) {
        String sessionId = request.getSessionId();
        log.info("[GenerateController] /cancel 收到请求: sessionId={}", sessionId);

        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        // 关闭 SSE emitter，使后续发送失败从而终止工作流
        SseEmitter emitter = SseEmitterStore.get(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            SseEmitterStore.remove(sessionId);
        }

        // 注意：保留 sessionStore，让用户可以基于同一套 checklist 重新提交生成
        log.info("[GenerateController] /cancel 已关闭 emitter, 保留 sessionId={}", sessionId);
    }

    /**
     * 继续生成流程（提交清单答案后）- 同步版本，保留向后兼容
     */
    @PostMapping("/resume")
    public ResumeResponse resume(@RequestBody ResumeRequest request,
                                 @RequestAttribute("currentUser") User currentUser) {
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
        mutableData.put(ProjectState.USER_ANSWERS, normalizeAnswers(request.getAnswers()));
        ProjectState updatedState = new ProjectState(mutableData);

        // 执行第二阶段工作流
        ProjectState finalState = graphWorkflowService.resume(updatedState);

        // 更新存储
        sessionStore.put(sessionId, finalState);

        // 持久化保存生成的 HTML 项目
        String projectId = projectStorageService.saveProject(finalState, currentUser.getId());

        ResumeResponse response = new ResumeResponse();
        response.setProjectId(projectId);
        response.setHtmlCode(finalState.htmlCode());
        response.setDesignConcept(finalState.designConcept());
        response.setDesignTokens(finalState.designTokens());
        response.setReviewPassed(finalState.reviewPassed());
        response.setReviewFeedback(finalState.reviewFeedback());
        response.setRetryCount(finalState.retryCount());
        response.setPreviewUrl("/api/preview/" + projectId + "/");
        response.setPages(extractPageList(finalState));

        log.info("[GenerateController] /resume 响应: reviewPassed={}, retryCount={}, projectId={}, pages={}",
                finalState.reviewPassed(), finalState.retryCount(), projectId, response.getPages());
        return response;
    }

    /**
     * 将用户答案中的数组值（如 multi-select）转换为逗号分隔字符串，
     * 确保下游工作流统一按 String 处理
     */
    private static Map<String, String> normalizeAnswers(Map<String, Object> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        raw.forEach((key, value) -> {
            if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) value;
                result.put(key, list.stream().collect(Collectors.joining(",")));
            } else {
                result.put(key, value != null ? value.toString() : "");
            }
        });
        return result;
    }

    // ==================== Request / Response DTOs ====================

    @Data
    public static class StartRequest {
        private String input;
        private String model;
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
        private Map<String, Object> answers;
    }

    @Data
    public static class CancelRequest {
        private String sessionId;
    }

    @Data
    public static class ResumeResponse {
        private String projectId;
        private String htmlCode;
        private String designConcept;
        private String designTokens;
        private boolean reviewPassed;
        private String reviewFeedback;
        private int retryCount;
        private String previewUrl;
        private List<String> pages;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractPageList(ProjectState state) {
        Object pagesObj = state.data().get(ProjectState.PAGES);
        if (pagesObj instanceof Map<?, ?> pages) {
            List<String> list = new ArrayList<>();
            for (Object key : pages.keySet()) {
                list.add(key.toString());
            }
            return list;
        }
        return List.of("index.html");
    }
}
