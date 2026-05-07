package com.example.websitemother.node;

import com.example.websitemother.prompt.PromptTemplates;
import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * Node 2: 生成需求清单
 * 根据用户建站需求，利用大模型生成需要用户补充信息的表单字段
 */
@Slf4j
@Component
public class ChecklistBuilder implements NodeAction<ProjectState> {

    @Resource
    private ChatModelService chatModelService;

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        String currentInput = state.currentInput();
        log.info("[ChecklistBuilder] 根据需求生成清单: {}", currentInput);

        String response = chatModelService.chat(
                PromptTemplates.CHECKLIST_BUILDER_SYSTEM,
                PromptTemplates.checklistBuilderUser(currentInput),
                ChatModelService.ModelType.SMART
        );

        // 清理可能的 markdown 代码块标记
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length());
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length());
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length());
        }
        cleaned = cleaned.trim();

        // 校验 LLM 返回的是否为合法 JSON 数组，否则使用兜底清单
        if (!isValidChecklistJson(cleaned)) {
            log.warn("[ChecklistBuilder] LLM 返回的清单格式异常，使用兜底清单。原始响应前200字: {}",
                    response.substring(0, Math.min(200, response.length())));
            cleaned = getDefaultChecklistJson();
        }

        log.info("[ChecklistBuilder] 生成清单完成");

        return Map.of(ProjectState.CHECKLIST, cleaned);
    }

    /**
     * 校验字符串是否为合法的 checklist JSON 数组
     */
    private boolean isValidChecklistJson(String json) {
        if (json == null || json.isBlank() || !json.startsWith("[")) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
            if (list == null || list.isEmpty()) {
                return false;
            }
            for (Map<String, Object> item : list) {
                Object field = item.get("field");
                Object label = item.get("label");
                Object type = item.get("type");
                if (field == null || label == null || type == null) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 兜底清单：当 LLM 输出异常时返回通用问题
     */
    private String getDefaultChecklistJson() {
        return "["
                + "{\"field\":\"industry\",\"label\":\"网站主题/行业\",\"type\":\"text\",\"options\":[],\"description\":\"例如：摄影、餐饮、科技、教育\"},"
                + "{\"field\":\"style\",\"label\":\"设计风格偏好\",\"type\":\"select\",\"options\":[\"极简风\",\"科技感\",\"国潮风\",\"商务风\",\"活泼有趣\",\"高端奢华\"],\"description\":\"选择最符合品牌调性的风格\"},"
                + "{\"field\":\"color_scheme\",\"label\":\"配色倾向\",\"type\":\"text\",\"options\":[],\"description\":\"例如：黑白灰、暖色调、蓝白配色\"},"
                + "{\"field\":\"target_audience\",\"label\":\"目标受众\",\"type\":\"text\",\"options\":[],\"description\":\"例如：年轻人、企业客户、家长\"},"
                + "{\"field\":\"core_modules\",\"label\":\"核心功能模块\",\"type\":\"multi-select\",\"options\":[\"产品展示\",\"在线预约\",\"联系表单\",\"客户评价\",\"博客文章\",\"会员系统\"],\"description\":\"勾选网站需要的功能\"},"
                + "{\"field\":\"reference\",\"label\":\"参考网站/品牌\",\"type\":\"text\",\"options\":[],\"description\":\"如有喜欢的设计风格网站可填写\"},"
                + "{\"field\":\"logo_needed\",\"label\":\"是否需要生成品牌Logo\",\"type\":\"select\",\"options\":[\"是，生成品牌Logo\",\"否，不需要Logo\"],\"description\":\"\"}"
                + "]";
    }
}
