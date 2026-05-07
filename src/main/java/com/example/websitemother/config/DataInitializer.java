package com.example.websitemother.config;

import com.example.websitemother.entity.User;
import com.example.websitemother.repository.UserRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Component
public class DataInitializer implements CommandLineRunner {

    @Resource
    private UserRepository userRepository;

    @Override
    public void run(String... args) {
        if (userRepository.existsByUsername("admin")) {
            log.info("[DataInitializer] 默认管理员已存在，跳过初始化");
            return;
        }

        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(hashPassword("admin123"));
        admin.setEmail("admin@websitemother.com");
        admin.setRole(User.Role.ADMIN);
        userRepository.save(admin);
        log.info("[DataInitializer] 已创建默认管理员: admin / admin123");
    }

    private String hashPassword(String raw) {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = DigestUtils.md5Digest(
                (Base64.getEncoder().encodeToString(salt) + raw).getBytes(StandardCharsets.UTF_8)
        );
        return Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(hash);
    }
}
