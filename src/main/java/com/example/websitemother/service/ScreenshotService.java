package com.example.websitemother.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 网站截图服务
 * 使用 Playwright 对生成的 HTML 页面进行高质量截图
 */
@Slf4j
@Service
public class ScreenshotService {

    private static final String SCREENSHOT_FILE = "screenshot.png";

    /**
     * 对指定项目的 index.html 进行截图并保存到项目目录
     *
     * @param projectDir 项目目录绝对路径
     * @param projectId  项目 ID（用于日志）
     * @return 截图文件路径
     */
    public Path captureProject(Path projectDir, String projectId) {
        Path indexPath = projectDir.resolve("index.html").toAbsolutePath();
        Path screenshotPath = projectDir.resolve(SCREENSHOT_FILE);

        if (!indexPath.toFile().exists()) {
            log.warn("[ScreenshotService] index.html 不存在, projectId={}", projectId);
            return null;
        }

        // 如果已有截图且不超过 1 小时，直接返回缓存
        if (screenshotPath.toFile().exists()) {
            long age = System.currentTimeMillis() - screenshotPath.toFile().lastModified();
            if (age < 3600_000) {
                log.info("[ScreenshotService] 使用缓存截图, projectId={}", projectId);
                return screenshotPath;
            }
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            Page page = browser.newPage();
            page.setViewportSize(1440, 900);

            // 使用 file:// 协议加载本地 HTML
            page.navigate(indexPath.toUri().toString());
            page.waitForLoadState(LoadState.NETWORKIDLE);

            // 滚动触发所有懒加载图片（IntersectionObserver / loading="lazy"）
            page.evaluate("() => { window.scrollTo(0, document.body.scrollHeight); }");
            page.waitForTimeout(800);
            page.evaluate("() => { window.scrollTo(0, 0); }");
            page.waitForTimeout(300);

            // 截图：截取完整页面（含滚动区域）
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));

            browser.close();
            log.info("[ScreenshotService] 截图完成: projectId={}, path={}", projectId, screenshotPath);
            return screenshotPath;
        } catch (Exception e) {
            log.error("[ScreenshotService] 截图失败: projectId={}", projectId, e);
            return null;
        }
    }

    /**
     * 获取项目截图文件路径（如果不存在则返回 null）
     */
    public Path getScreenshotPath(Path projectDir) {
        Path path = projectDir.resolve(SCREENSHOT_FILE);
        return path.toFile().exists() ? path : null;
    }
}
