package com.example.websitemother.service;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 交互底层服务
 * 支持三模型策略：
 * - FAST (qwen-turbo)：低成本，用于意图分析
 * - SMART (qwen-plus)：平衡性能，用于清单生成
 * - MAX (qwen-max)：最强性能，用于核心代码生成
 */
@Slf4j
@Service
public class ChatModelService {

    @Resource
    private QwenChatModel qwenChatModel;

    @Value("${langchain4j.community.dashscope.chat-model.api-key:}")
    private String apiKey;

    private QwenChatModel fastModel;
    private QwenChatModel maxModel;

    public enum ModelType {
        FAST,   // qwen-turbo  低成本
        SMART,  // qwen-plus   平衡（Spring Boot 默认配置）
        MAX     // qwen-max    最强性能
    }

    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("DASHSCOPE_API_KEY");
        }
        if (apiKey != null && !apiKey.isBlank()) {
            this.fastModel = QwenChatModel.builder()
                    .apiKey(apiKey)
                    .modelName("qwen-turbo")
                    .build();
            this.maxModel = QwenChatModel.builder()
                    .apiKey(apiKey)
                    .modelName("qwen-max")
                    .build();
            log.info("[ChatModelService] 三模型初始化完成: FAST=qwen-turbo, SMART=qwen-plus, MAX=qwen-max");
        } else {
            log.warn("[ChatModelService] 未配置 API Key，额外模型不可用");
        }
    }

    /**
     * 单轮对话：SystemPrompt + UserPrompt，使用默认 SMART 模型
     */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, ModelType.SMART);
    }

    /**
     * 单轮对话：支持模型选择
     */
    public String chat(String systemPrompt, String userPrompt, ModelType modelType) {
        try {
            QwenChatModel model = resolveModel(modelType);
            String modelName = resolveModelName(modelType);

            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(SystemMessage.from(systemPrompt));
            }
            messages.add(UserMessage.from(userPrompt));

            int inputTokens = systemPrompt != null ? systemPrompt.length() + userPrompt.length() : userPrompt.length();
            log.info("[ChatModelService] 调用模型={}, userPrompt长度={}, 输入约{} tokens", modelName, userPrompt.length(), inputTokens / 4);

            ChatResponse response = model.chat(messages);
            String text = response.aiMessage().text();
            log.info("[ChatModelService] 模型={} 调用完成, 输出长度={}, 输出约{} tokens", modelName, text.length(), text.length() / 4);
            return text;
        } catch (Exception e) {
            log.error("LLM调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI服务调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * 简化的单userPrompt调用（无systemPrompt，默认 SMART 模型）
     */
    public String chat(String userPrompt) {
        return chat(null, userPrompt, ModelType.SMART);
    }

    /**
     * 简化的单userPrompt调用，支持模型选择
     */
    public String chat(String userPrompt, ModelType modelType) {
        return chat(null, userPrompt, modelType);
    }

    private QwenChatModel resolveModel(ModelType modelType) {
        return switch (modelType) {
            case FAST -> fastModel != null ? fastModel : qwenChatModel;
            case MAX -> maxModel != null ? maxModel : qwenChatModel;
            case SMART -> qwenChatModel;
        };
    }

    private String resolveModelName(ModelType modelType) {
        return switch (modelType) {
            case FAST -> "qwen-turbo";
            case MAX -> "qwen-max";
            case SMART -> "qwen-plus";
        };
    }
}
