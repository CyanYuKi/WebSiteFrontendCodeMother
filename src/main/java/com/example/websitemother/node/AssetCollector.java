package com.example.websitemother.node;

import com.example.websitemother.service.ChatModelService;
import com.example.websitemother.service.LogoGenerationService;
import com.example.websitemother.service.PexelsImageService;
import com.example.websitemother.state.ProjectState;
import com.example.websitemother.controller.SseEmitterStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    @Resource
    private ChatModelService chatModelService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String ASSET_CONFIG_PROMPT = """
你是一个专业的网站素材策划师。根据用户的建站需求和补充答案，生成最优的素材搜索配置。

用户原始需求：%s
补充答案（JSON）：%s

请严格输出以下 JSON 格式（不要包含markdown代码块标记，只输出纯JSON）：
{
  "brandName": "品牌名称，简洁，用于Logo生成。如果用户没指定，从需求中提取最合适的名称",
  "logoPrompt": "英文prompt，用于AI Logo生成。描述清晰有视觉细节（行业、风格、色彩、氛围），适合AI图像生成。纯图形标志，不要包含文字、字母或数字。100词左右",
  "searchQueries": [
    {"section": "hero", "query": "5-10个英文关键词，如luosifen food neon banner", "count": 2},
    {"section": "about", "query": "5-10个英文关键词，如chinese restaurant culture heritage", "count": 2},
    {"section": "feature", "query": "5-10个英文关键词，如noodle ingredients closeup studio", "count": 2},
    {"section": "gallery", "query": "5-10个英文关键词，如product packaging flatlay dark", "count": 2},
    {"section": "team", "query": "5-10个英文关键词，如chef team kitchen portrait", "count": 1},
    {"section": "testimonial", "query": "5-10个英文关键词，如customers dining happy friends", "count": 1},
    {"section": "contact", "query": "5-10个英文关键词，如modern storefront night neon", "count": 1}
  ]
}

要求：
1. brandName 用中文或英文，简洁
2. logoPrompt 用英文，包含行业、风格、色彩、氛围等视觉细节
3. 每个section的query必须是5-10个英文关键词（如：luosifen food neon cyberpunk night），不要长句子。Pexels图库搜索对简短关键词支持更好，长句子会导致搜索无结果
4. count是请求该section的图片数量，建议hero/about/feature/gallery各2张，其余1张
""";

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        sendStage(SseEmitterStore.get(state.sessionId()), "asset_collector");

        Map<String, String> answers = state.userAnswers();
        log.info("[AssetCollector] 收集素材，用户答案: {}", answers);

        Map<String, Object> assets = new HashMap<>();

        // 兜底：用户原始输入
        String userInput = state.currentInput() != null ? state.currentInput() : "";

        // 1. 调用 LLM 生成精准素材配置（使用SMART模型提升质量）
        AssetConfig config = generateAssetConfig(userInput, answers);
        log.info("[AssetCollector] LLM生成素材配置: brandName={}, searchQueries={}",
                config.brandName, config.searchQueries.size());

        // 2. 根据用户选择决定是否生成 Logo
        boolean needLogo = isNeedLogo(answers);
        if (needLogo) {
            String logoUrl = logoGenerationService.generateLogoWithPrompt(config.brandName, config.logoPrompt);
            if (logoUrl != null) {
                Map<String, String> logoAsset = new HashMap<>();
                logoAsset.put("url", logoUrl);
                logoAsset.put("description", "AI生成的品牌Logo");
                logoAsset.put("keyword", "logo");
                assets.put("logo", logoAsset);
                log.info("[AssetCollector] Logo生成成功");
            }
        } else {
            log.info("[AssetCollector] 用户选择不生成Logo，跳过");
        }

        // 3. 按区块分别搜索图片，每个区块用专属查询词
        List<String> galleryPool = new ArrayList<>();
        for (SectionQuery sq : config.searchQueries) {
            List<String> images = pexelsImageService.searchImages(sq.query, sq.count);
            if (!images.isEmpty()) {
                assets.put(sq.section, buildAsset(sq.description, images.get(0)));
                // 多余图片放入素材池
                for (int i = 1; i < images.size(); i++) {
                    galleryPool.add(images.get(i));
                }
            }
        }

        if (!galleryPool.isEmpty()) {
            Map<String, Object> pool = new HashMap<>();
            pool.put("description", "额外图片素材池");
            pool.put("images", galleryPool);
            assets.put("gallery_pool", pool);
        }

        // 如果关键区块没有图片，回退到占位图
        if (!assets.containsKey("hero")) {
            log.warn("[AssetCollector] Pexels 搜索无结果，回退到占位图");
            fallbackToPicsum(assets, config.fallbackQuery);
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

    /**
     * 判断用户是否需要生成Logo。默认生成，仅在用户明确选择不需要时跳过。
     */
    private boolean isNeedLogo(Map<String, String> answers) {
        String needLogo = getAnswer(answers, "need_logo", "logo", "generate_logo", "");
        if (needLogo.isEmpty()) {
            return true; // 默认生成
        }
        String lower = needLogo.toLowerCase();
        return !(lower.contains("否") || lower.contains("不需要") || lower.contains("不") ||
                 lower.contains("false") || lower.contains("skip") || lower.contains("no"));
    }

    private AssetConfig generateAssetConfig(String userInput, Map<String, String> answers) {
        try {
            String answersJson = objectMapper.writeValueAsString(answers);
            String prompt = String.format(ASSET_CONFIG_PROMPT, userInput, answersJson);
            String response = chatModelService.chat(prompt, ChatModelService.ModelType.SMART);

            // 清理可能的 markdown 代码块
            String json = response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            JsonNode node = objectMapper.readTree(json);

            String brandName = node.path("brandName").asText("");
            String logoPrompt = node.path("logoPrompt").asText("");
            String fallbackQuery = userInput + " website professional";

            List<SectionQuery> searchQueries = new ArrayList<>();
            JsonNode queriesNode = node.path("searchQueries");
            if (queriesNode.isArray()) {
                for (JsonNode qn : queriesNode) {
                    String section = qn.path("section").asText("");
                    String query = qn.path("query").asText("");
                    int count = qn.path("count").asInt(1);
                    if (!section.isBlank() && !query.isBlank()) {
                        searchQueries.add(new SectionQuery(section, query, count, sectionDescription(section)));
                    }
                }
            }

            // 兜底：如果LLM没有生成任何查询，使用默认查询
            if (searchQueries.isEmpty()) {
                searchQueries.add(new SectionQuery("hero", fallbackQuery, 3, sectionDescription("hero")));
                searchQueries.add(new SectionQuery("about", fallbackQuery, 2, sectionDescription("about")));
                searchQueries.add(new SectionQuery("feature", fallbackQuery, 2, sectionDescription("feature")));
                searchQueries.add(new SectionQuery("gallery", fallbackQuery, 2, sectionDescription("gallery")));
            }

            // 兜底
            if (brandName.isBlank()) brandName = userInput;
            if (logoPrompt.isBlank()) {
                logoPrompt = "Design a professional logo for " + brandName + ". Modern, clean, suitable for website, white background, pure graphic symbol without text.";
            }

            return new AssetConfig(brandName, logoPrompt, searchQueries, fallbackQuery);
        } catch (Exception e) {
            log.warn("[AssetCollector] LLM素材配置生成失败，使用兜底策略: {}", e.getMessage());
            String fallbackQuery = userInput + " website professional";
            List<SectionQuery> fallbackQueries = new ArrayList<>();
            fallbackQueries.add(new SectionQuery("hero", fallbackQuery, 3, sectionDescription("hero")));
            fallbackQueries.add(new SectionQuery("about", fallbackQuery, 2, sectionDescription("about")));
            fallbackQueries.add(new SectionQuery("feature", fallbackQuery, 2, sectionDescription("feature")));
            fallbackQueries.add(new SectionQuery("gallery", fallbackQuery, 2, sectionDescription("gallery")));
            return new AssetConfig(userInput,
                    "Design a professional logo for " + userInput + ". Modern, clean, white background, pure graphic symbol without text.",
                    fallbackQueries, fallbackQuery);
        }
    }

    private String sectionDescription(String section) {
        return switch (section) {
            case "hero" -> "主视觉Banner图";
            case "about" -> "品牌介绍配图";
            case "feature" -> "功能特色展示图";
            case "gallery" -> "产品/作品展示图";
            case "team" -> "团队风采图";
            case "testimonial" -> "客户评价配图";
            case "contact" -> "联系区配图";
            default -> "网站配图";
        };
    }

    private record AssetConfig(String brandName, String logoPrompt, List<SectionQuery> searchQueries, String fallbackQuery) {
    }

    private record SectionQuery(String section, String query, int count, String description) {
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

    private void sendStage(SseEmitter emitter, String stage) {
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("stage").data(stage));
            } catch (Exception ignored) {
            }
        }
    }

    private String extractKeyword(String input, String fallback) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return fallback.isEmpty() ? "" : fallback.trim();
        }
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
