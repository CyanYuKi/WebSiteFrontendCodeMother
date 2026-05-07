package com.example.websitemother.service;

import com.example.websitemother.dto.UserDto;
import com.example.websitemother.entity.User;
import com.example.websitemother.repository.UserRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.security.SecureRandom;

@Slf4j
@Service
public class UserService {

    @Resource
    private UserRepository userRepository;

    private static final SecureRandom RANDOM = new SecureRandom();

    public UserDto register(String username, String password, String email) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(hashPassword(password));
        user.setEmail(email);
        user.setRole(User.Role.USER);
        user = userRepository.save(user);
        log.info("[UserService] 注册成功: username={}", username);
        return toDto(user);
    }

    public UserDto login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!verifyPassword(password, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        user.setToken(UUID.randomUUID().toString());
        user = userRepository.save(user);
        log.info("[UserService] 登录成功: username={}", username);
        return toDto(user);
    }

    public void logout(String token) {
        userRepository.findByToken(token).ifPresent(user -> {
            user.setToken(null);
            userRepository.save(user);
            log.info("[UserService] 登出成功: username={}", user.getUsername());
        });
    }

    public User getByToken(String token) {
        return userRepository.findByToken(token).orElse(null);
    }

    public List<UserDto> listAll() {
        return userRepository.findAll().stream().map(this::toDto).toList();
    }

    public UserDto getById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        return toDto(user);
    }

    @Transactional
    public UserDto updateUser(Long id, String role, String email) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (role != null && !role.isBlank()) {
            try {
                user.setRole(User.Role.valueOf(role.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("无效的角色: " + role);
            }
        }
        if (email != null) {
            user.setEmail(email);
        }
        user = userRepository.save(user);
        log.info("[UserService] 更新用户: id={}, role={}", id, user.getRole());
        return toDto(user);
    }

    public void deleteUser(Long id, Long currentUserId) {
        if (id.equals(currentUserId)) {
            throw new IllegalArgumentException("不能删除自己的账号");
        }
        if (!userRepository.existsById(id)) {
            throw new IllegalArgumentException("用户不存在");
        }
        userRepository.deleteById(id);
        log.info("[UserService] 删除用户: id={}", id);
    }

    private String hashPassword(String raw) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = DigestUtils.md5Digest(
                (Base64.getEncoder().encodeToString(salt) + raw).getBytes(StandardCharsets.UTF_8)
        );
        return Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyPassword(String raw, String stored) {
        String[] parts = stored.split(":");
        if (parts.length != 2) return false;
        byte[] hash = DigestUtils.md5Digest(
                (parts[0] + raw).getBytes(StandardCharsets.UTF_8)
        );
        return parts[1].equals(Base64.getEncoder().encodeToString(hash));
    }

    private UserDto toDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole().name());
        dto.setToken(user.getToken());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setUpdatedAt(user.getUpdatedAt());
        return dto;
    }
}
