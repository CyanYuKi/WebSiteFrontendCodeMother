package com.example.websitemother.controller;

import com.example.websitemother.service.ScreenshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 项目预览静态文件服务
 * 提供 /api/preview/{projectId}/** 路径访问生成的多页面网站
 */
@Slf4j
@RestController
@RequestMapping("/api/preview")
public class PreviewController {

    private static final String BASE_DIR = "generated-projects";

    @Autowired
    private ScreenshotService screenshotService;

    @GetMapping("/{projectId}/{fileName:.+}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable String projectId,
            @PathVariable String fileName) {
        if (!isValidProjectId(projectId)) {
            log.warn("[PreviewController] 非法 projectId: {}", projectId);
            return ResponseEntity.badRequest().build();
        }

        Path basePath = Paths.get(BASE_DIR).toAbsolutePath();
        Path projectDir = basePath.resolve(projectId).normalize();
        Path filePath = projectDir.resolve(fileName).normalize();

        // 安全检查：确保文件在 project 目录内，防止目录遍历
        if (!filePath.startsWith(projectDir)) {
            log.warn("[PreviewController] 非法路径访问: projectId={}, filePath={}", projectId, filePath);
            return ResponseEntity.badRequest().build();
        }

        if (!Files.exists(filePath)) {
            log.warn("[PreviewController] 文件不存在: projectId={}, file={}", projectId, filePath);
            // 兜底：如果请求的是 HTML 文件但不存在，回退到 index.html
            if (fileName.endsWith(".html")) {
                Path indexPath = projectDir.resolve("index.html").normalize();
                if (Files.exists(indexPath)) {
                    log.info("[PreviewController] 回退到 index.html: projectId={}", projectId);
                    Resource resource = new FileSystemResource(indexPath);
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("text/html"))
                            .body(resource);
                }
            }
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(filePath);
        String contentType = resolveContentType(fileName);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<Resource> serveIndex(@PathVariable String projectId) {
        return serveFile(projectId, "index.html");
    }

    @GetMapping("/{projectId}/")
    public ResponseEntity<Resource> serveIndexTrailing(@PathVariable String projectId) {
        return serveFile(projectId, "index.html");
    }

    /**
     * 生成并返回项目截图（懒加载：首次请求时截图，后续直接返回缓存）
     */
    @GetMapping("/{projectId}/screenshot.png")
    public ResponseEntity<Resource> serveScreenshot(@PathVariable String projectId) {
        if (!isValidProjectId(projectId)) {
            log.warn("[PreviewController] 非法 projectId: {}", projectId);
            return ResponseEntity.badRequest().build();
        }

        Path basePath = Paths.get(BASE_DIR).toAbsolutePath();
        Path projectDir = basePath.resolve(projectId).normalize();

        if (!projectDir.startsWith(basePath) || !Files.exists(projectDir)) {
            return ResponseEntity.notFound().build();
        }

        Path screenshotPath = screenshotService.captureProject(projectDir, projectId);
        if (screenshotPath == null || !Files.exists(screenshotPath)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(screenshotPath);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(resource);
    }

    private boolean isValidProjectId(String projectId) {
        return projectId != null && projectId.matches("^[0-9a-fA-F\\-]{36}$");
    }

    private String resolveContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=UTF-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }
}
