package com.example.websitemother.repository;

import com.example.websitemother.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, String> {
    List<Project> findByUserIdOrderByCreatedAtDesc(Long userId);
    void deleteByProjectId(String projectId);
}
