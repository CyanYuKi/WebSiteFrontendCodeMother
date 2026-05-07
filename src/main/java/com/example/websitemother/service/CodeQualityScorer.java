package com.example.websitemother.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码质量评分器
 * 基于规则化评估生成的 Vue 代码质量（非 LLM，零额外 token 消耗）
 * 评分维度：设计系统完整性、结构完整性、视觉丰富度、交互效果、响应式设计、现代CSS使用
 */
@Slf4j
@Component
public class CodeQualityScorer {

    public QualityReport score(String code) {
        if (code == null || code.isBlank()) {
            return new QualityReport(0, "代码为空");
        }

        String lower = code.toLowerCase();
        int totalScore = 0;
        StringBuilder details = new StringBuilder();

        // 1. 设计系统完整性 (25分) - Claude Design 理念：设计系统优先
        int designSystemScore = 0;
        if (lower.contains("--color-primary") || lower.contains("--color")) designSystemScore += 8;
        if (lower.contains("--font-") || lower.contains("font-family")) designSystemScore += 5;
        if (lower.contains("--spacing-") || lower.contains("spacing")) designSystemScore += 5;
        if (lower.contains(":root") || lower.contains("@property")) designSystemScore += 7;
        totalScore += designSystemScore;
        details.append("设计系统: ").append(designSystemScore).append("/25; ");

        // 2. 基础结构完整性 (20分)
        int structureScore = 0;
        if (lower.contains("<!doctype html>")) structureScore += 5;
        if (lower.contains("<html")) structureScore += 5;
        if (lower.contains("<head")) structureScore += 5;
        if (lower.contains("<body")) structureScore += 5;
        totalScore += structureScore;
        details.append("结构完整性: ").append(structureScore).append("/20; ");

        // 3. 页面区块与内容质量 (20分) - 宁缺毋滥
        int sectionScore = 0;
        if (lower.contains("header") || lower.contains("<nav")) sectionScore += 2;
        if (hasHeroSection(lower)) sectionScore += 3;
        if (lower.contains("feature") || lower.contains("service")) sectionScore += 2;
        if (lower.contains("about") || lower.contains("content")) sectionScore += 2;
        if (lower.contains("contact") || lower.contains("cta") || lower.contains("form")) sectionScore += 2;
        if (lower.contains("footer")) sectionScore += 2;
        // 内容精炼度：无过度填充检测
        if (!hasFillerPatterns(lower)) sectionScore += 7;
        totalScore += Math.min(sectionScore, 20);
        details.append("页面区块: ").append(Math.min(sectionScore, 20)).append("/20; ");

        // 4. 视觉丰富度 (15分)
        int visualScore = 0;
        if (lower.contains("box-shadow") || lower.contains("shadow")) visualScore += 3;
        if (lower.contains("border-radius") || lower.contains("rounded")) visualScore += 3;
        if (lower.contains("font-size") || lower.contains("2rem") || lower.contains("3rem")) visualScore += 3;
        if (lower.contains("font-weight") || lower.contains("bold")) visualScore += 3;
        if (!hasAiSlopPatterns(lower)) visualScore += 3;
        totalScore += Math.min(visualScore, 15);
        details.append("视觉丰富度: ").append(Math.min(visualScore, 15)).append("/15; ");

        // 5. 交互与动画 (10分)
        int interactionScore = 0;
        if (lower.contains("hover") || lower.contains(":hover")) interactionScore += 3;
        if (lower.contains("transition") || lower.contains("animation")) interactionScore += 3;
        if (lower.contains("transform") || lower.contains("scale") || lower.contains("translate")) interactionScore += 4;
        totalScore += Math.min(interactionScore, 10);
        details.append("交互效果: ").append(Math.min(interactionScore, 10)).append("/10; ");

        // 6. 响应式设计 (10分)
        int responsiveScore = 0;
        if (lower.contains("@media")) responsiveScore += 5;
        if (lower.contains("viewport") || lower.contains("max-width") || lower.contains("min-width")) responsiveScore += 5;
        totalScore += Math.min(responsiveScore, 10);
        details.append("响应式设计: ").append(Math.min(responsiveScore, 10)).append("/10; ");

        // 7. 现代CSS使用 (10分)
        int modernCssScore = 0;
        if (lower.contains("display: grid") || lower.contains("grid-template")) modernCssScore += 3;
        if (lower.contains("text-wrap: pretty")) modernCssScore += 3;
        if (lower.contains("oklch(")) modernCssScore += 2;
        if (lower.contains("container-type") || lower.contains("@container")) modernCssScore += 2;
        totalScore += modernCssScore;
        details.append("现代CSS: ").append(modernCssScore).append("/10");

        String level = totalScore >= 85 ? "优秀" : totalScore >= 65 ? "良好" : totalScore >= 45 ? "一般" : "需改进";
        return new QualityReport(totalScore, level + " | " + details.toString());
    }

    private boolean hasHeroSection(String lower) {
        return lower.contains("hero")
                || lower.contains("min-height")
                || lower.contains("height: 100vh")
                || lower.contains("height: 600px")
                || (lower.contains("cta") && lower.contains("button"));
    }

    /**
     * 检测AI-slop设计套路
     */
    private boolean hasAiSlopPatterns(String lower) {
        return lower.contains("bg-gradient-to-") && lower.contains("from-purple"); // 典型AI渐变
    }

    /**
     * 检测内容填充套路
     */
    private boolean hasFillerPatterns(String lower) {
        // 统计无意义占位符
        int fillerCount = 0;
        if (lower.contains("lorem ipsum")) fillerCount++;
        if (lower.contains("placeholder")) fillerCount++;
        if (lower.contains("xxxx")) fillerCount++;
        if (lower.contains("1000+") && lower.contains("10000+")) fillerCount++; // 堆砌数据
        return fillerCount >= 2;
    }

    public record QualityReport(int score, String details) {}
}
