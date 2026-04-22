package com.example.websitemother.service;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 交互底层服务
 * 封装对 DashScope Qwen 模型的调用，统一处理 SystemMessage + UserMessage 组装
 */
@Slf4j
@Service
public class ChatModelService {

    @Resource
    private QwenChatModel qwenChatModel;

    /**
     * 单轮对话：SystemPrompt + UserPrompt
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 生成的文本响应
     */
    public String chat(String systemPrompt, String userPrompt) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(SystemMessage.from(systemPrompt));
            }
            messages.add(UserMessage.from(userPrompt));

            ChatResponse response = qwenChatModel.chat(messages);
            String text = response.aiMessage().text();
            log.debug("LLM response: {}", text);
            return text;
        } catch (Exception e) {
            log.error("LLM调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI服务调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * 简化的单userPrompt调用（无systemPrompt）
     */
    public String chat(String userPrompt) {
        return chat(null, userPrompt);
    }
}
