package com.example.websitemother.controller;

import com.example.websitemother.dto.UserDto;
import com.example.websitemother.service.UserService;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Resource
    private UserService userService;

    @PostMapping("/register")
    public AuthResponse register(@RequestBody AuthRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank() ||
                request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        UserDto user = userService.register(
                request.getUsername().trim(),
                request.getPassword(),
                request.getEmail()
        );
        return new AuthResponse(user.getToken(), user);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank() ||
                request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        UserDto user = userService.login(request.getUsername().trim(), request.getPassword());
        return new AuthResponse(user.getToken(), user);
    }

    @PostMapping("/logout")
    public void logout(@RequestAttribute("currentUser") com.example.websitemother.entity.User currentUser) {
        if (currentUser.getToken() != null) {
            userService.logout(currentUser.getToken());
        }
    }

    @GetMapping("/me")
    public UserDto me(@RequestAttribute("currentUser") com.example.websitemother.entity.User currentUser) {
        return userService.getById(currentUser.getId());
    }

    @Data
    public static class AuthRequest {
        private String username;
        private String password;
        private String email;
    }

    @Data
    public static class AuthResponse {
        private final String token;
        private final UserDto user;
    }
}
