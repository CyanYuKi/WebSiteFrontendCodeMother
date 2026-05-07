package com.example.websitemother.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Pexels 图片搜索服务
 * 提供与网站主题相关的高质量真实图片
 */
@Slf4j
@Service
public class PexelsImageService {

    private static final String PEXELS_API_URL = "https://api.pexels.com/v1/search";

    @Value("${pexels.api-key:}")
    private String pexelsApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 图片信息（含 URL 和原始尺寸）
     */
    public record ImageInfo(String url, int width, int height) {}

    /**
     * 搜索与关键词相关的图片（返回完整信息含尺寸）
     *
     * @param query 搜索关键词（英文效果最佳）
     * @param count 返回图片数量
     * @param size  图片尺寸：large2x/large/medium/small/original
     * @return 图片信息列表
     */
    public List<ImageInfo> searchImagesWithInfo(String query, int count, String size) {
        List<ImageInfo> images = new ArrayList<>();
        if (pexelsApiKey == null || pexelsApiKey.isBlank()) {
            log.warn("[PexelsImageService] 未配置 Pexels API Key");
            return images;
        }

        String preferredSize = validateSize(size);

        try {
            String url = String.format("%s?query=%s&per_page=%d&page=1",
                    PEXELS_API_URL, java.net.URLEncoder.encode(query, "UTF-8"), Math.min(count, 80));

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", pexelsApiKey);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode photos = root.path("photos");
                for (JsonNode photo : photos) {
                    JsonNode src = photo.path("src");
                    String imageUrl = src.path(preferredSize).asText();
                    if (imageUrl == null || imageUrl.isBlank()) {
                        imageUrl = src.path("large").asText();
                    }
                    if (imageUrl == null || imageUrl.isBlank()) {
                        imageUrl = src.path("original").asText();
                    }
                    if (imageUrl != null && !imageUrl.isBlank()) {
                        int width = photo.path("width").asInt(0);
                        int height = photo.path("height").asInt(0);
                        images.add(new ImageInfo(imageUrl, width, height));
                    }
                }
                log.info("[PexelsImageService] 搜索 '{}' size={} 返回 {} 张图片", query, preferredSize, images.size());
            } else {
                log.warn("[PexelsImageService] 搜索失败: HTTP {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[PexelsImageService] 搜索异常: {}", e.getMessage(), e);
        }

        return images;
    }

    /**
     * 搜索与关键词相关的图片（仅返回 URL 列表）
     */
    public List<String> searchImages(String query, int count, String size) {
        return searchImagesWithInfo(query, count, size).stream()
                .map(ImageInfo::url)
                .toList();
    }

    /**
     * 搜索与关键词相关的图片（默认 large 尺寸）
     */
    public List<String> searchImages(String query, int count) {
        return searchImages(query, count, "large");
    }

    /**
     * 获取单张最佳匹配图片
     */
    public String searchOneImage(String query) {
        List<ImageInfo> results = searchImagesWithInfo(query, 1, "large");
        return results.isEmpty() ? null : results.get(0).url();
    }

    private String validateSize(String size) {
        if (size == null || size.isBlank()) {
            return "large";
        }
        String lower = size.toLowerCase();
        return switch (lower) {
            case "large2x", "large", "medium", "small", "original", "portrait", "landscape", "tiny" -> lower;
            default -> "large";
        };
    }
}
