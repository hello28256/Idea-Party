package com.ideaparty.config;

import com.ideaparty.websocket.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration for real-time chat.
 * Registers ChatWebSocketHandler at /ws endpoint with CORS support.
 */
// @Configuration  // Disabled - using SocketConfig instead
// @EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    // 原生 Spring WebSocket 处理器；运行时由 SocketConfig（Socket.IO）替代，
    // 保留此类作为降级/对照实现，便于未来在不需要 Socket.IO 的场景下快速切换。
    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;

    /**
     * 注册原生 WebSocket 端点。
     * 路径 /ws 与前端 wsClient 默认连接地址保持一致；
     * setAllowedOrigins 仅放通本地 Vite/CRA 开发端口，避免生产环境 CORS 误开放；
     * withSockJS 提供浏览器不支持 WebSocket 时的长轮询降级，提升兼容性。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws")
            .setAllowedOrigins("http://localhost:5173", "http://localhost:3000")
            .withSockJS();
    }
}
