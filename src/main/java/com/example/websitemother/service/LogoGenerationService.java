package com.example.websitemother.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 通义万相 Logo 生成服务
 * 基于阿里云 DashScope 的 wanx 模型生成网站 Logo
 */
@Slf4j
@Service
public class LogoGenerationService {

    private static final String DASHSCOPE_IMAGE_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
    private static final String DASHSCOPE_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/";
    private static final int MAX_POLL_SECONDS = 30;
    private static final int POLL_INTERVAL_MS = 2000;

    @Value("${langchain4j.community.dashscope.chat-model.api-key:}")
    private String dashscopeApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 为网站生成 Logo
     *
     * @param brandName 品牌名称
     * @param theme     主题/行业描述
     * @param style     风格偏好（如国潮、极简、科技）
     * @return Logo 图片 URL，生成失败返回 null
     */
    public String generateLogo(String brandName, String theme, String style) {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            log.warn("[LogoGenerationService] 未配置 DashScope API Key");
            return null;
        }

        String prompt = buildLogoPrompt(brandName, theme, style);
        return doGenerateLogo(brandName, prompt);
    }

    /**
     * 使用自定义 Prompt 生成 Logo
     */
    public String generateLogoWithPrompt(String brandName, String customPrompt) {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            log.warn("[LogoGenerationService] 未配置 DashScope API Key");
            return null;
        }
        return doGenerateLogo(brandName, customPrompt);
    }

    private String doGenerateLogo(String brandName, String prompt) {
        log.info("[LogoGenerationService] 开始生成Logo, brand={}, prompt={}", brandName, prompt);

        String taskId = submitLogoTask(prompt);
        if (taskId == null) {
            log.warn("[LogoGenerationService] 提交Logo生成任务失败");
            return null;
        }

        String imageUrl = pollTaskResult(taskId);
        if (imageUrl != null) {
            log.info("[LogoGenerationService] Logo生成成功: {}", imageUrl);
        } else {
            log.warn("[LogoGenerationService] Logo生成超时或失败");
        }
        return imageUrl;
    }

    private String buildLogoPrompt(String brandName, String theme, String style) {
        StringBuilder sb = new StringBuilder();
        sb.append("为品牌 \"").append(brandName).append("\" 设计一个专业Logo。");
        sb.append("行业：").append(theme).append("。");
        if (style != null && !style.isBlank()) {
            sb.append("风格：").append(style).append("。");
        }
        sb.append("要求：简洁现代，适合网站使用，白色或透明背景，");
        sb.append("纯图形标志设计，不要包含任何文字、字母或数字，高辨识度。");
        return sb.toString();
    }

    private String submitLogoTask(String prompt) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + dashscopeApiKey);
            headers.set("X-DashScope-Async", "enable");

            Map<String, Object> input = new HashMap<>();
            input.put("prompt", prompt);

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("size", "1024*1024");
            parameters.put("n", 1);

            Map<String, Object> body = new HashMap<>();
            body.put("model", "wanx-v1");
            body.put("input", input);
            body.put("parameters", parameters);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    DASHSCOPE_IMAGE_URL, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode output = root.path("output");
                return output.path("task_id").asText(null);
            }
        } catch (Exception e) {
            log.error("[LogoGenerationService] 提交任务异常: {}", e.getMessage(), e);
        }
        return null;
    }

    private String pollTaskResult(String taskId) {
        String queryUrl = DASHSCOPE_TASK_URL + taskId;
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + dashscopeApiKey);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < MAX_POLL_SECONDS * 1000L) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        queryUrl, HttpMethod.GET, entity, String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    JsonNode output = root.path("output");
                    String taskStatus = output.path("task_status").asText("");

                    if ("SUCCEEDED".equals(taskStatus)) {
                        JsonNode results = output.path("results");
                        if (results.isArray() && results.size() > 0) {
                            return results.get(0).path("url").asText(null);
                        }
                        JsonNode result = output.path("result");
                        if (!result.isMissingNode()) {
                            return result.path("url").asText(null);
                        }
                        return null;
                    } else if ("FAILED".equals(taskStatus)) {
                        log.warn("[LogoGenerationService] 任务执行失败: {}", taskId);
                        return null;
                    }
                }

                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.error("[LogoGenerationService] 轮询异常: {}", e.getMessage());
                return null;
            }
        }

        log.warn("[LogoGenerationService] 轮询超时: {}", taskId);
        return null;
    }
}
