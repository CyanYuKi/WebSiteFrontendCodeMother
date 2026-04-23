package com.example.websitemother.node;

import com.example.websitemother.service.LogoGenerationService;
import com.example.websitemother.service.PexelsImageService;
import com.example.websitemother.state.ProjectState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node 3: 素材收集
 * 使用 Pexels 搜索真实相关图片 + 通义万相生成 Logo
 */
@Slf4j
@Component
public class AssetCollector implements NodeAction<ProjectState> {

    @Resource
    private PexelsImageService pexelsImageService;

    @Resource
    private LogoGenerationService logoGenerationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        Map<String, String> answers = state.userAnswers();
        log.info("[AssetCollector] 收集素材，用户答案: {}", answers);

        Map<String, Object> assets = new HashMap<>();

        // 1. 提取关键信息用于搜索
        String themeKeyword = extractKeyword(getAnswer(answers, "website_industry", "theme", "industry", "business_type"));
        String styleKeyword = extractKeyword(getAnswer(answers, "design_style", "style", "brand_tone", ""));
        String brandName = extractKeyword(getAnswer(answers, "brand_name", "website_industry", "theme", "business"));
        String colorInfo = extractKeyword(getAnswer(answers, "color_preference", "color", "", ""));

        // 2. 生成 Logo（异步，在后台搜索图片的同时进行）
        String logoUrl = logoGenerationService.generateLogo(brandName, themeKeyword, styleKeyword);
        if (logoUrl != null) {
            Map<String, String> logoAsset = new HashMap<>();
            logoAsset.put("url", logoUrl);
            logoAsset.put("description", "AI生成的品牌Logo");
            logoAsset.put("keyword", "logo");
            assets.put("logo", logoAsset);
            log.info("[AssetCollector] Logo生成成功");
        }

        // 3. 使用 Pexels 搜索真实图片（英文关键词效果更好）
        String searchQuery = buildSearchQuery(themeKeyword, styleKeyword, colorInfo);
        List<String> pexelsImages = pexelsImageService.searchImages(searchQuery, 15);

        // 4. 分配图片到各个区块
        if (!pexelsImages.isEmpty()) {
            int idx = 0;
            assets.put("hero", buildAsset("主视觉Banner图", pexelsImages.get(idx++)));
            if (idx < pexelsImages.size()) assets.put("about", buildAsset("品牌介绍配图", pexelsImages.get(idx++)));
            if (idx < pexelsImages.size()) assets.put("feature", buildAsset("功能特色展示图", pexelsImages.get(idx++)));
            if (idx < pexelsImages.size()) assets.put("gallery", buildAsset("产品/作品展示图", pexelsImages.get(idx++)));
            if (idx < pexelsImages.size()) assets.put("team", buildAsset("团队风采图", pexelsImages.get(idx++)));
            if (idx < pexelsImages.size()) assets.put("testimonial", buildAsset("客户评价配图", pexelsImages.get(idx++)));
            if (idx < pexelsImages.size()) assets.put("contact", buildAsset("联系区配图", pexelsImages.get(idx)));

            // 剩余的放入 gallery_pool 供 LLM 选择使用
            List<String> remaining = new ArrayList<>();
            while (idx < pexelsImages.size()) {
                remaining.add(pexelsImages.get(idx++));
            }
            if (!remaining.isEmpty()) {
                Map<String, Object> pool = new HashMap<>();
                pool.put("description", "额外图片素材池");
                pool.put("images", remaining);
                assets.put("gallery_pool", pool);
            }
        } else {
            // Pexels 搜索失败，回退到 picsum.photos
            log.warn("[AssetCollector] Pexels 搜索无结果，回退到占位图");
            fallbackToPicsum(assets, themeKeyword);
        }

        String assetsJson = objectMapper.writeValueAsString(assets);
        log.info("[AssetCollector] 素材收集完成，共{}个素材", assets.size());

        return Map.of(ProjectState.ASSETS_JSON, assetsJson);
    }

    private String getAnswer(Map<String, String> answers, String... keys) {
        for (String key : keys) {
            if (key.isEmpty()) continue;
            String value = answers.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String buildSearchQuery(String theme, String style, String color) {
        StringBuilder query = new StringBuilder(theme);
        if (!style.isBlank()) {
            query.append(" ").append(style);
        }
        if (!color.isBlank()) {
            query.append(" ").append(color);
        }
        String result = query.toString().trim();
        // Pexels 对英文支持更好，简单处理：如果是纯中文，添加一些通用英文词
        if (result.matches("[\u4e00-\u9fa5\\s]+") || result.isBlank()) {
            result = "website business professional";
        }
        return result;
    }

    private Map<String, String> buildAsset(String description, String imageUrl) {
        Map<String, String> asset = new HashMap<>();
        asset.put("url", imageUrl);
        asset.put("description", description);
        asset.put("source", "pexels");
        return asset;
    }

    private void fallbackToPicsum(Map<String, Object> assets, String keyword) {
        String safeSeed = keyword.replaceAll("[^a-zA-Z0-9]", "_");
        assets.put("hero", buildAsset("主视觉Banner图",
                String.format("https://picsum.photos/seed/%s/1200/600", safeSeed)));
        assets.put("about", buildAsset("品牌介绍配图",
                String.format("https://picsum.photos/seed/%s_about/800/600", safeSeed)));
        assets.put("feature", buildAsset("功能特色展示图",
                String.format("https://picsum.photos/seed/%s_feature/800/600", safeSeed)));
        assets.put("gallery", buildAsset("产品/作品展示图",
                String.format("https://picsum.photos/seed/%s_product/600/600", safeSeed)));
        assets.put("team", buildAsset("团队风采图",
                String.format("https://picsum.photos/seed/%s_team/800/600", safeSeed)));
    }

    private String extractKeyword(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return "website";
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0 && spaceIdx < 25) {
            return trimmed.substring(0, spaceIdx);
        }
        if (trimmed.length() > 30) {
            return trimmed.substring(0, 30);
        }
        return trimmed;
    }
}
