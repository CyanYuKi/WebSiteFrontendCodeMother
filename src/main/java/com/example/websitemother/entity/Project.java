package com.example.websitemother.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "project")
public class Project {

    @Id
    private String projectId;

    @Column(nullable = false)
    private Long userId;

    private String originalInput;

    private String projectName;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String designConcept;

    private String previewUrl;

    private String screenshotUrl;

    private boolean reviewPassed;

    private int retryCount;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String pagesJson;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String chatHistory;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
