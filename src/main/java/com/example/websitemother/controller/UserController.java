package com.example.websitemother.controller;

import com.example.websitemother.dto.UserDto;
import com.example.websitemother.entity.User;
import com.example.websitemother.service.UserService;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Resource
    private UserService userService;

    @GetMapping
    public List<UserDto> list() {
        return userService.listAll();
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable Long id) {
        return userService.getById(id);
    }

    @PutMapping("/{id}")
    public UserDto update(
            @PathVariable Long id,
            @RequestBody UpdateUserRequest request,
            @RequestAttribute("currentUser") User currentUser) {
        return userService.updateUser(id, request.getRole(), request.getEmail());
    }

    @DeleteMapping("/{id}")
    public void delete(
            @PathVariable Long id,
            @RequestAttribute("currentUser") User currentUser) {
        userService.deleteUser(id, currentUser.getId());
    }

    @Data
    public static class UpdateUserRequest {
        private String role;
        private String email;
    }
}
