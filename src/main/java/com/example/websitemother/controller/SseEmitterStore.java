package com.example.websitemother.controller;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE Emitter 全局存储
 * 由于 SseEmitter 不可序列化，不能放入 LangGraph4j 的 ProjectState 中
 * 通过 sessionId 作为 key 在全局 Map 中存取
 */
public class SseEmitterStore {

    private static final ConcurrentHashMap<String, SseEmitter> STORE = new ConcurrentHashMap<>();

    public static void put(String sessionId, SseEmitter emitter) {
        STORE.put(sessionId, emitter);
    }

    public static SseEmitter get(String sessionId) {
        return STORE.get(sessionId);
    }

    public static void remove(String sessionId) {
        STORE.remove(sessionId);
    }
}
