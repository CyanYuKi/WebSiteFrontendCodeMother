package com.example.websitemother.config;

import com.example.websitemother.entity.User;
import com.example.websitemother.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Resource
    private UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();

        // 放行登录和注册
        if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
            return true;
        }

        // 放行 H2 控制台
        if (path.startsWith("/h2-console")) {
            return true;
        }

        // 放行预览（公开访问）
        if (path.startsWith("/api/preview/")) {
            return true;
        }

        // /api/projects/my 需要登录，其他 /api/projects 公开
        if (path.startsWith("/api/projects/") && !path.startsWith("/api/projects/my")) {
            return true;
        }

        // 以下路径需要登录
        String token = extractToken(request);
        if (token == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"未登录，请先登录\"}");
            return false;
        }

        User user = userService.getByToken(token);
        if (user == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"登录已过期，请重新登录\"}");
            return false;
        }

        // 管理员路径校验
        if (path.startsWith("/api/users")) {
            if (user.getRole() != User.Role.ADMIN) {
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"权限不足，仅管理员可访问\"}");
                return false;
            }
        }

        request.setAttribute("currentUser", user);
        return true;
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
