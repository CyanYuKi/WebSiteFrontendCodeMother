package com.example.websitemother.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成项目的元数据
 */
@Data
public class ProjectMeta {
    private String projectId;
    private String originalInput;
    private String projectName;
    private String htmlCodePreview;
    private String designConcept;
    private boolean reviewPassed;
    private int retryCount;
    private LocalDateTime createdAt;
}
