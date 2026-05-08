package com.example.websitemother.node;

import com.example.websitemother.state.ProjectState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node: 新页面检测器
 * 在代码审查通过后运行，对比 index.html 导航栏中的链接与现有 PAGES，
 * 识别出需要新生成的子页面。纯正则匹配，无 LLM 调用。
 */
@Slf4j
@Component
public class NewPageDetector implements NodeAction<ProjectState> {

    @Override
    public Map<String, Object> apply(ProjectState state) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> pages = (Map<String, String>) state.data()
                .getOrDefault(ProjectState.PAGES, Map.of());
        String indexHtml = pages.getOrDefault("index.html", state.htmlCode());

        Set<String> navLinks = extractLinksFromHtml(indexHtml);

        // 找出导航中有但 PAGES 中不存在的页面（用 List 而非 Set，避免 LangGraph 序列化类型问题）
        List<String> newPages = new ArrayList<>();
        for (String link : navLinks) {
            if (!pages.containsKey(link) && !newPages.contains(link)) {
                newPages.add(link);
            }
        }

        log.info("[NewPageDetector] 导航链接: {}, 现有页面: {}, 新页面: {}",
                navLinks, pages.keySet(), newPages);
        return Map.of(ProjectState.NEW_PAGES_DETECTED, newPages);
    }

    private Set<String> extractLinksFromHtml(String html) {
        Set<String> links = new LinkedHashSet<>();
        Pattern p = Pattern.compile("<a\\b[^>]*href=[\"']([^\"']+\\.html)[\"'][^>]*>");
        Matcher m = p.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            if (!"index.html".equals(href) && !href.startsWith("http")
                    && !href.startsWith("#") && !href.startsWith("/")) {
                links.add(href);
            }
        }
        return links;
    }
}
