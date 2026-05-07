package com.example.websitemother.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步线程池配置
 * 为 SSE 流式工作流执行提供后台线程
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "workflowExecutor")
    public ExecutorService workflowExecutor() {
        return Executors.newCachedThreadPool();
    }
}
