package com.example.websitemother.node;

import com.example.websitemother.prompt.PromptTemplates;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * Node 1: 意图分析
 * 判断用户输入是闲聊(chat)还是建站需求(create)
 */
@Slf4j
@Component
public class IntentAnalyzer implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        String currentInput = state.currentInput();
        log.info("[IntentAnalyzer] 分析用户输入: {}", currentInput);

        String response = chatModelService.chat(
                PromptTemplates.INTENT_ANALYZER_SYSTEM,
                PromptTemplates.intentAnalyzerUser(currentInput)
        );

        String intentType = "chat";
        String chatReply = "";

        // 解析 LLM 输出
        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.startsWith("INTENT:")) {
                String value = line.substring("INTENT:".length()).trim().toLowerCase();
                if (value.contains("create")) {
                    intentType = "create";
                }
            } else if (line.startsWith("REPLY:")) {
                chatReply = line.substring("REPLY:".length()).trim();
                if ("null".equalsIgnoreCase(chatReply) || chatReply.isEmpty()) {
                    chatReply = "";
                }
            }
        }

        log.info("[IntentAnalyzer] 意图识别结果: intentType={}", intentType);

        return Map.of(
                ProjectState.INTENT_TYPE, intentType,
                ProjectState.CHAT_REPLY, chatReply
        );
    }
}
