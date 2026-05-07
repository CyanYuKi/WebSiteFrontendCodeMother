package com.example.websitemother.service;

import com.example.websitemother.dto.ProjectMeta;
import com.example.websitemother.entity.Project;
import com.example.websitemother.repository.ProjectRepository;
import com.example.websitemother.state.ProjectState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 生成项目的文件持久化服务
 * 每个项目保存在 generated-projects/{projectId}/ 目录下
 */
@Slf4j
@Service
public class ProjectStorageService {

    private static final String BASE_DIR = "generated-projects";
    private static final String HTML_FILE = "index.html";
    private static final String META_FILE = "meta.json";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Resource
    private ProjectRepository projectRepository;

    private Path basePath;

    @PostConstruct
    public void init() {
        basePath = Paths.get(BASE_DIR).toAbsolutePath();
        try {
            Files.createDirectories(basePath);
            log.info("[ProjectStorageService] 项目存储目录: {}", basePath);
        } catch (IOException e) {
            throw new RuntimeException("无法创建项目存储目录: " + basePath, e);
        }
    }

    /**
     * 保存生成的 HTML 项目（支持多页面）
     */
    public String saveProject(ProjectState state, Long userId) {
        return saveProject(state, userId, null);
    }

    /**
     * 保存/更新项目。如果提供 existingProjectId 则原地更新，否则新建。
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public String saveProject(ProjectState state, Long userId, String existingProjectId) {
        final String projectId;
        String preservedName = null;
        if (existingProjectId != null && !existingProjectId.isBlank()) {
            projectId = existingProjectId;
            // 保存旧项目名称
            preservedName = projectRepository.findById(projectId)
                    .map(com.example.websitemother.entity.Project::getProjectName).orElse(null);
            // 原地更新：先清理旧文件和数据库记录
            Path oldDir = basePath.resolve(projectId);
            try {
                if (Files.exists(oldDir)) {
                    try (Stream<Path> files = Files.walk(oldDir)) {
                        files.sorted(Comparator.reverseOrder())
                             .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                    }
                }
            } catch (IOException e) {
                log.warn("[ProjectStorageService] 清理旧项目文件失败: {}", projectId, e);
            }
            projectRepository.deleteByProjectId(projectId);
            log.info("[ProjectStorageService] 更新已有项目: id={}", projectId);
        } else {
            projectId = UUID.randomUUID().toString();
        }
        Path projectDir = basePath.resolve(projectId);

        try {
            Files.createDirectories(projectDir);

            Object pagesObj = state.data().get(ProjectState.PAGES);
            if (pagesObj instanceof Map<?, ?> pages && !pages.isEmpty()) {
                for (Map.Entry<?, ?> entry : pages.entrySet()) {
                    String fileName = entry.getKey().toString();
                    String content = entry.getValue().toString();
                    Path pagePath = projectDir.resolve(fileName);
                    Files.writeString(pagePath, content);
                }
                log.info("[ProjectStorageService] 保存多页面项目: id={}, 页面数={}", projectId, pages.size());
            } else {
                String htmlCode = state.htmlCode();
                Path htmlPath = projectDir.resolve(HTML_FILE);
                Files.writeString(htmlPath, htmlCode);
                log.info("[ProjectStorageService] 保存单页面项目: id={}", projectId);
            }

            // 保存元数据到文件
            ProjectMeta meta = new ProjectMeta();
            meta.setProjectId(projectId);
            meta.setOriginalInput(state.currentInput());
            String previewCode = state.htmlCode();
            meta.setHtmlCodePreview(previewCode.length() > 500 ? previewCode.substring(0, 500) + "..." : previewCode);
            meta.setDesignConcept(state.designConcept());
            meta.setReviewPassed(state.reviewPassed());
            meta.setRetryCount(state.retryCount());
            meta.setCreatedAt(LocalDateTime.now());

            Path metaPath = projectDir.resolve(META_FILE);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), meta);

            // 保存项目记录到数据库
            if (userId != null) {
                Project project = new Project();
                project.setProjectId(projectId);
                project.setUserId(userId);
                project.setOriginalInput(state.currentInput());
                if (preservedName != null) {
                    project.setProjectName(preservedName);
                }
                project.setDesignConcept(state.designConcept());
                project.setPreviewUrl("/api/preview/" + projectId + "/");
                project.setScreenshotUrl("/api/preview/" + projectId + "/screenshot.png");
                project.setReviewPassed(state.reviewPassed());
                project.setRetryCount(state.retryCount());
                try {
                    project.setPagesJson(objectMapper.writeValueAsString(pagesObj));
                } catch (Exception ignored) {}
                projectRepository.save(project);
            }

            log.info("[ProjectStorageService] 项目已保存: id={}, userId={}, path={}", projectId, userId, projectDir);
            return projectId;
        } catch (IOException e) {
            throw new RuntimeException("保存项目失败: " + projectId, e);
        }
    }



    /**
     * 列出所有已保存的项目（按创建时间倒序）
     */
    public List<ProjectMeta> listProjects() {
        try (Stream<Path> dirs = Files.list(basePath)) {
            return dirs
                    .filter(Files::isDirectory)
                    .map(this::readMeta)
                    .filter(m -> m != null)
                    .sorted(Comparator.comparing(ProjectMeta::getCreatedAt).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("[ProjectStorageService] 读取项目列表失败", e);
            return List.of();
        }
    }

    /**
     * 读取项目元数据
     */
    public ProjectMeta getProjectMeta(String projectId) {
        Path metaPath = basePath.resolve(projectId).resolve(META_FILE);
        if (!Files.exists(metaPath)) {
            return null;
        }
        return readMeta(metaPath.getParent());
    }

    /**
     * 读取 HTML 源码
     */
    public String readHtmlCode(String projectId) {
        Path htmlPath = basePath.resolve(projectId).resolve(HTML_FILE);
        try {
            return Files.readString(htmlPath);
        } catch (IOException e) {
            throw new RuntimeException("读取 HTML 代码失败: " + projectId, e);
        }
    }

    /**
     * 扫描项目目录下所有 HTML 文件，返回文件名列表
     */
    public java.util.List<String> listPageFiles(String projectId) {
        Path projectDir = basePath.resolve(projectId);
        if (!Files.exists(projectDir)) {
            return java.util.List.of();
        }
        try (Stream<Path> files = Files.list(projectDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".html"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("[ProjectStorageService] 扫描页面文件失败: projectId={}", projectId, e);
            return java.util.List.of();
        }
    }

    /**
     * 获取 HTML 文件的绝对路径（用于下载）
     */
    public Path getHtmlFilePath(String projectId) {
        return basePath.resolve(projectId).resolve(HTML_FILE);
    }

    @Transactional
    public void deleteProject(String projectId) {
        Path projectDir = basePath.resolve(projectId);
        try {
            if (Files.exists(projectDir)) {
                try (Stream<Path> files = Files.walk(projectDir)) {
                    files.sorted(Comparator.reverseOrder())
                         .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                }
            }
        } catch (IOException e) {
            log.error("[ProjectStorageService] 删除项目文件失败: {}", projectId, e);
        }
        projectRepository.deleteByProjectId(projectId);
        log.info("[ProjectStorageService] 项目已删除: id={}", projectId);
    }

    public Path zipProject(String projectId) throws IOException {
        Path projectDir = basePath.resolve(projectId);
        if (!Files.exists(projectDir)) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
        Path zipPath = projectDir.resolveSibling(projectId + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            try (Stream<Path> files = Files.walk(projectDir)) {
                files.filter(Files::isRegularFile).forEach(f -> {
                    try {
                        String entryName = projectDir.relativize(f).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(f, zos);
                        zos.closeEntry();
                    } catch (IOException ignored) {}
                });
            }
        }
        return zipPath;
    }

    private ProjectMeta readMeta(Path projectDir) {
        Path metaPath = projectDir.resolve(META_FILE);
        if (!Files.exists(metaPath)) {
            return null;
        }
        try {
            return objectMapper.readValue(metaPath.toFile(), ProjectMeta.class);
        } catch (IOException e) {
            log.warn("[ProjectStorageService] 读取元数据失败: {}", metaPath, e);
            return null;
        }
    }
}
