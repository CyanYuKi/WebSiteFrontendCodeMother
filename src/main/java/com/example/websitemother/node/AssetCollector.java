package com.example.websitemother.node;

import com.example.websitemother.state.ProjectState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Node 3: 素材收集
 * 根据用户完善后的需求，生成占位图片URL JSON（使用 picsum.photos）
 */
@Slf4j
@Component
public class AssetCollector implements NodeAction<ProjectState> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        Map<String, String> answers = state.userAnswers();
        log.info("[AssetCollector] 收集素材，用户答案: {}", answers);

        Map<String, Object> assets = new HashMap<>();

        // 根据常见字段生成对应的占位图
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) continue;

            String keyword = extractKeyword(value);
            String imageUrl = buildPicsumUrl(keyword, 800, 600);

            Map<String, String> assetInfo = new HashMap<>();
            assetInfo.put("url", imageUrl);
            assetInfo.put("description", value);
            assetInfo.put("keyword", keyword);
            assets.put(key, assetInfo);
        }

        // 至少保证有一张hero/主图
        if (!assets.containsKey("hero")) {
            String mainKeyword = answers.containsKey("theme") ? extractKeyword(answers.get("theme")) : "website";
            Map<String, String> heroAsset = new HashMap<>();
            heroAsset.put("url", buildPicsumUrl(mainKeyword, 1200, 600));
            heroAsset.put("description", "主视觉图");
            heroAsset.put("keyword", mainKeyword);
            assets.put("hero", heroAsset);
        }

        String assetsJson = objectMapper.writeValueAsString(assets);
        log.info("[AssetCollector] 素材收集完成: {}", assetsJson);

        return Map.of(ProjectState.ASSETS_JSON, assetsJson);
    }

    /**
     * 从用户输入中提取关键词（简单处理：取前3个中文字或第一个英文单词）
     */
    private String extractKeyword(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return "random";

        // 取第一个空格前的词，或整个字符串（如果较短）
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0 && spaceIdx < 20) {
            return trimmed.substring(0, spaceIdx).toLowerCase();
        }

        // 限制长度，避免URL过长
        if (trimmed.length() > 20) {
            return trimmed.substring(0, 20).toLowerCase();
        }
        return trimmed.toLowerCase();
    }

    /**
     * 构造 picsum.photos 的确定性图片URL（基于seed）
     */
    private String buildPicsumUrl(String seed, int width, int height) {
        String safeSeed = seed.replaceAll("[^a-zA-Z0-9]", "_");
        return String.format("https://picsum.photos/seed/%s/%d/%d", safeSeed, width, height);
    }
}
