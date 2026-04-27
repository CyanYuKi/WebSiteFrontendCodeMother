package com.example.websitemother.service;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LLM 交互底层服务
 * 支持多模型策略与动态模型切换
 */
@Slf4j
@Service
public class ChatModelService {

    @Resource
    private QwenChatModel qwenChatModel;

    @Value("${langchain4j.community.dashscope.chat-model.api-key:}")
    private String apiKey;

    private ChatModel fastModel;
    private ChatModel smartModel;
    private StreamingChatModel smartStreamingModel;

    /** 用户选择的默认核心模型名称，可被外部设置为 qwen3.6-plus / qwen3.6-max / deepseek 等 */
    private volatile String defaultSmartModelName = "qwen3.6-plus";

    // 动态模型缓存
    private final ConcurrentHashMap<String, ChatModel> modelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamingChatModel> streamingModelCache = new ConcurrentHashMap<>();

    public enum ModelType {
        FAST,   // qwen-turbo     低成本
        SMART   // 用户可选核心模型（默认 qwen3.6-plus）
    }

    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("DASHSCOPE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[ChatModelService] 未配置 API Key，仅 Spring Boot 默认模型可用");
            return;
        }
        this.fastModel = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-turbo")
                .build();
        this.smartModel = buildOpenAiChatModel("qwen3.6-plus", apiKey);
        this.smartStreamingModel = buildOpenAiStreamingModel("qwen3.6-plus", apiKey);
        log.info("[ChatModelService] 初始化完成: FAST=qwen-turbo, SMART=qwen3.6-plus");
    }

    // ==================== 公共 API ====================

    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, ModelType.SMART);
    }

    public String chat(String systemPrompt, String userPrompt, ModelType modelType) {
        ChatModel model = resolveModel(modelType);
        String modelName = resolveModelName(modelType);
        return executeChat(model, modelName, buildMessages(systemPrompt, userPrompt));
    }

    public String chat(String systemPrompt, String userPrompt, String modelName) {
        ChatModel model = resolveModel(modelName);
        return executeChat(model, modelName, buildMessages(systemPrompt, userPrompt));
    }

    public String streamChat(String systemPrompt, String userPrompt, ModelType modelType,
                             Consumer<String> onToken) {
        StreamingChatModel model = resolveStreamingModel(modelType);
        String modelName = resolveModelName(modelType);
        return executeStream(model, modelName, buildMessages(systemPrompt, userPrompt), onToken);
    }

    public String streamChat(String systemPrompt, String userPrompt, String modelName,
                             Consumer<String> onToken) {
        StreamingChatModel model = resolveStreamingModel(modelName);
        return executeStream(model, modelName, buildMessages(systemPrompt, userPrompt), onToken);
    }

    // ==================== 简化 API ====================

    public String chat(String userPrompt) {
        return chat(null, userPrompt, ModelType.SMART);
    }

    public String chat(String userPrompt, ModelType modelType) {
        return chat(null, userPrompt, modelType);
    }

    // ==================== 私有方法 ====================

    private List<ChatMessage> buildMessages(String systemPrompt, String userPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userPrompt));
        return messages;
    }

    private String executeChat(ChatModel model, String modelName, List<ChatMessage> messages) {
        long start = System.currentTimeMillis();
        String userPrompt = extractUserPrompt(messages);
        int inputTokens = estimateTokens(messages);
        log.info("[ChatModelService.chat] model={} | inputTokens≈{} | userPromptLength={}",
                modelName, inputTokens, userPrompt.length());
        logFullPrompt(messages);
        try {
            ChatResponse response = model.chat(messages);
            String text = response.aiMessage().text();
            long cost = System.currentTimeMillis() - start;
            log.info("[ChatModelService.chat] model={} | outputLength={} | outputTokens≈{} | cost={}ms | status=SUCCESS",
                    modelName, text.length(), text.length() / 4, cost);
            return text;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[ChatModelService.chat] model={} | cost={}ms | status=FAILED | error={}",
                    modelName, cost, e.getMessage(), e);
            throw new RuntimeException("AI服务调用异常 [" + modelName + "]: " + e.getMessage(), e);
        }
    }

    private String executeStream(StreamingChatModel model, String modelName,
                                 List<ChatMessage> messages, Consumer<String> onToken) {
        long start = System.currentTimeMillis();
        String userPrompt = extractUserPrompt(messages);
        int inputTokens = estimateTokens(messages);
        log.info("[ChatModelService.stream] model={} | inputTokens≈{} | userPromptLength={}",
                modelName, inputTokens, userPrompt.length());
        logFullPrompt(messages);

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder buffer = new StringBuilder();
        AtomicReference<String> lastPartial = new AtomicReference<>("");
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        model.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                String last = lastPartial.get();
                String delta = partialResponse;
                if (partialResponse.startsWith(last)) {
                    delta = partialResponse.substring(last.length());
                }
                lastPartial.set(partialResponse);
                buffer.append(delta);
                if (onToken != null) {
                    onToken.accept(delta);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                long cost = System.currentTimeMillis() - start;
                log.info("[ChatModelService.stream] model={} | outputLength={} | outputTokens≈{} | cost={}ms | status=SUCCESS",
                        modelName, buffer.length(), buffer.length() / 4, cost);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                long cost = System.currentTimeMillis() - start;
                log.error("[ChatModelService.stream] model={} | cost={}ms | status=FAILED | error={}",
                        modelName, cost, error.getMessage(), error);
                errorRef.set(error);
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("流式调用被中断 [" + modelName + "]", e);
        }

        if (errorRef.get() != null) {
            throw new RuntimeException("流式LLM调用失败 [" + modelName + "]: " + errorRef.get().getMessage(), errorRef.get());
        }
        return buffer.toString();
    }

    private void logFullPrompt(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder("\n========== LLM FULL PROMPT ==========\n");
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                sb.append("[SYSTEM]: ").append(sm.text()).append("\n");
            } else if (msg instanceof UserMessage um) {
                sb.append("[USER]: ").append(um.singleText()).append("\n");
            }
        }
        sb.append("=======================================");
        log.info(sb.toString());
    }

    private String extractUserPrompt(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                return um.singleText();
            }
        }
        return "";
    }

    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                total += sm.text().length();
            } else if (msg instanceof UserMessage um) {
                total += um.singleText().length();
            }
        }
        return total / 4;
    }

    // ==================== 模型解析 ====================

    private ChatModel resolveModel(ModelType modelType) {
        return switch (modelType) {
            case FAST -> fastModel != null ? fastModel : qwenChatModel;
            case SMART -> smartModel != null ? smartModel : qwenChatModel;
        };
    }

    private StreamingChatModel resolveStreamingModel(ModelType modelType) {
        return switch (modelType) {
            case FAST -> null;
            case SMART -> smartStreamingModel;
        };
    }

    /**
     * 设置 SMART 类型对应的默认模型名称，影响 checklist / design_concept 等节点的模型选择
     */
    public void setDefaultSmartModelName(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            this.defaultSmartModelName = modelName;
            log.info("[ChatModelService] 默认 SMART 模型已切换: {}", modelName);
        }
    }

    private String resolveModelName(ModelType modelType) {
        return switch (modelType) {
            case FAST -> "qwen-turbo";
            case SMART -> defaultSmartModelName;
        };
    }

    private ChatModel resolveModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return smartModel != null ? smartModel : qwenChatModel;
        }
        return modelCache.computeIfAbsent(modelName, this::createChatModel);
    }

    private StreamingChatModel resolveStreamingModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return smartStreamingModel;
        }
        return streamingModelCache.computeIfAbsent(modelName, this::createStreamingModel);
    }

    // ==================== 模型工厂 ====================

    private StreamingChatModel createStreamingModel(String modelName) {
        ModelConfig config = resolveModelConfig(modelName);
        log.info("[ChatModelService] 创建流式模型实例: name={} | baseUrl={} | modelName={}",
                modelName, config.baseUrl, config.actualModelName);
        return buildOpenAiStreamingModel(config.actualModelName, config.apiKey, config.baseUrl);
    }

    private ChatModel createChatModel(String modelName) {
        ModelConfig config = resolveModelConfig(modelName);
        log.info("[ChatModelService] 创建同步模型实例: name={} | baseUrl={} | modelName={}",
                modelName, config.baseUrl, config.actualModelName);
        return buildOpenAiChatModel(config.actualModelName, config.apiKey, config.baseUrl);
    }

    private ModelConfig resolveModelConfig(String modelName) {
        String mn = modelName.toLowerCase();
        if (mn.contains("qwen3.6-max")) {
            return new ModelConfig("https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey, "qwen3.6-max-preview");
        }
        if (mn.contains("deepseek")) {
            return new ModelConfig("https://api.deepseek.com/v1",
                    "sk-8f258f2ddeb748b0ab13bf722b632642", "deepseek-chat");
        }
        // 默认回退
        return new ModelConfig("https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey, "qwen3.6-plus");
    }

    private static OpenAiChatModel buildOpenAiChatModel(String modelName, String apiKey) {
        return buildOpenAiChatModel(modelName, apiKey, "https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    private static OpenAiChatModel buildOpenAiChatModel(String modelName, String apiKey, String baseUrl) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private static OpenAiStreamingChatModel buildOpenAiStreamingModel(String modelName, String apiKey) {
        return buildOpenAiStreamingModel(modelName, apiKey, "https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    private static OpenAiStreamingChatModel buildOpenAiStreamingModel(String modelName, String apiKey, String baseUrl) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private record ModelConfig(String baseUrl, String apiKey, String actualModelName) {}
}
