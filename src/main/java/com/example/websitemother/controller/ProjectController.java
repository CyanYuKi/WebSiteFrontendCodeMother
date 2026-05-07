package com.example.websitemother.controller;

import com.example.websitemother.dto.ProjectMeta;
import com.example.websitemother.entity.Project;
import com.example.websitemother.entity.User;
import com.example.websitemother.repository.ProjectRepository;
import com.example.websitemother.service.ProjectStorageService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成项目管理 API
 * - GET /api/projects          : 列出所有已保存的项目
 * - GET /api/projects/{id}     : 查看指定项目的源码和元数据
 * - GET /api/projects/{id}/download : 下载 App.vue 文件
 */
@Slf4j
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    @Autowired
    private ProjectStorageService projectStorageService;

    @Autowired
    private ProjectRepository projectRepository;

    /**
     * 列出所有项目（按创建时间倒序）
     */
    @GetMapping
    public List<ProjectMeta> listProjects() {
        return projectStorageService.listProjects();
    }

    /**
     * 获取项目详情（含完整 HTML 源码）
     */
    @GetMapping("/{id}")
    public ProjectDetailResponse getProject(@PathVariable String id) {
        ProjectMeta meta = projectStorageService.getProjectMeta(id);
        if (meta == null) {
            throw new IllegalArgumentException("项目不存在: " + id);
        }
        String htmlCode = projectStorageService.readHtmlCode(id);
        List<String> pages = projectStorageService.listPageFiles(id);

        ProjectDetailResponse response = new ProjectDetailResponse();
        response.setProjectId(meta.getProjectId());
        response.setOriginalInput(meta.getOriginalInput());
        response.setHtmlCode(htmlCode);
        response.setDesignConcept(meta.getDesignConcept());
        response.setReviewPassed(meta.isReviewPassed());
        response.setRetryCount(meta.getRetryCount());
        response.setPages(pages);
        response.setCreatedAt(meta.getCreatedAt());
        return response;
    }

    /**
     * 获取当前用户的项目列表
     */
    @GetMapping("/my")
    public List<ProjectSummary> myProjects(@RequestAttribute("currentUser") User currentUser) {
        return projectRepository.findByUserIdOrderByCreatedAtDesc(currentUser.getId())
                .stream().map(p -> {
                    ProjectSummary s = new ProjectSummary();
                    s.setProjectId(p.getProjectId());
                    s.setOriginalInput(p.getOriginalInput());
                    s.setProjectName(p.getProjectName());
                    s.setPreviewUrl(p.getPreviewUrl());
                    s.setScreenshotUrl(p.getScreenshotUrl());
                    s.setDesignConcept(p.getDesignConcept());
                    s.setReviewPassed(p.isReviewPassed());
                    s.setRetryCount(p.getRetryCount());
                    s.setCreatedAt(p.getCreatedAt());
                    return s;
                }).collect(Collectors.toList());
    }

    /**
     * 获取当前用户的单个项目详情（含完整 HTML）
     */
    @GetMapping("/my/{id}")
    public ProjectDetailResponse myProjectDetail(@PathVariable String id,
                                                  @RequestAttribute("currentUser") User currentUser) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        if (!project.getUserId().equals(currentUser.getId())) {
            throw new IllegalArgumentException("无权访问该项目");
        }
        String htmlCode = projectStorageService.readHtmlCode(id);
        List<String> pages = projectStorageService.listPageFiles(id);
        ProjectDetailResponse response = new ProjectDetailResponse();
        response.setProjectId(project.getProjectId());
        response.setOriginalInput(project.getOriginalInput());
        response.setHtmlCode(htmlCode);
        response.setDesignConcept(project.getDesignConcept());
        response.setReviewPassed(project.isReviewPassed());
        response.setRetryCount(project.getRetryCount());
        response.setChatHistory(project.getChatHistory());
        response.setPagesJson(project.getPagesJson());
        response.setPages(pages);
        response.setCreatedAt(project.getCreatedAt());
        return response;
    }

    /**
     * 保存项目对话历史
     */
    @PutMapping("/my/{id}/chat-history")
    public void saveChatHistory(@PathVariable String id,
                                @RequestBody ChatHistoryRequest request,
                                @RequestAttribute("currentUser") User currentUser) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        if (!project.getUserId().equals(currentUser.getId())) {
            throw new IllegalArgumentException("无权操作该项目");
        }
        project.setChatHistory(request.getChatHistory());
        projectRepository.save(project);
    }

    /**
     * 删除当前用户的某个项目
     */
    @DeleteMapping("/my/{id}")
    public void deleteMyProject(@PathVariable String id,
                                @RequestAttribute("currentUser") User currentUser) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        if (!project.getUserId().equals(currentUser.getId())) {
            throw new IllegalArgumentException("无权操作该项目");
        }
        projectStorageService.deleteProject(id);
    }

    /**
     * 下载当前用户的项目（打包为 ZIP）
     */
    @GetMapping("/my/{id}/download-zip")
    public ResponseEntity<Resource> downloadMyProjectZip(@PathVariable String id,
                                                          @RequestAttribute("currentUser") User currentUser) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        if (!project.getUserId().equals(currentUser.getId())) {
            throw new IllegalArgumentException("无权操作该项目");
        }
        try {
            Path zipPath = projectStorageService.zipProject(id);
            Resource resource = new FileSystemResource(zipPath);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + ".zip\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(resource);
        } catch (IOException e) {
            throw new RuntimeException("打包项目失败: " + id, e);
        }
    }

    /**
     * 下载 index.html 文件
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadProject(@PathVariable String id) {
        Path htmlPath = projectStorageService.getHtmlFilePath(id);
        Resource resource = new FileSystemResource(htmlPath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("项目文件不存在: " + id);
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + "_index.html\"")
                .contentType(MediaType.TEXT_HTML)
                .body(resource);
    }

    // ==================== Response DTOs ====================

    @Data
    public static class ProjectDetailResponse {
        private String projectId;
        private String originalInput;
        private String htmlCode;
        private String designConcept;
        private boolean reviewPassed;
        private int retryCount;
        private String chatHistory;
        private String pagesJson;
        private List<String> pages;
        private java.time.LocalDateTime createdAt;
    }

    @Data
    public static class ChatHistoryRequest {
        private String chatHistory;
    }

    @Data
    public static class ProjectSummary {
        private String projectId;
        private String originalInput;
        private String projectName;
        private String previewUrl;
        private String screenshotUrl;
        private String designConcept;
        private boolean reviewPassed;
        private int retryCount;
        private java.time.LocalDateTime createdAt;
    }
}
