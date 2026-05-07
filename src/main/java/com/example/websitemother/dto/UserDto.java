package com.example.websitemother.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserDto {
    private Long id;
    private String username;
    private String email;
    private String role;
    private String token;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
