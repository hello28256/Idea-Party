package com.ideaparty.config;

import com.ideaparty.websocket.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Spring 原生 WebSocket 配置类。
 * 当前已让位于 SocketConfig（基于 socket.io-java），本类作为降级实现保留：
 * 在不引入 Socket.IO 的极简部署场景下，可直接启用 @Configuration 注解即可恢复原生 STOMP-less WebSocket。
 * 与 ChatWebSocketHandler 协作：把 handler 注册到 /ws 端点，供前端 wsClient 使用。
 */
/**
 * 用于实时聊天的 WebSocket 配置类。
 * 把 ChatWebSocketHandler 注册到 /ws 端点，并支持 CORS。
 */
// @Configuration  // Disabled - using SocketConfig instead
// @EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    // 原生 Spring WebSocket 处理器；运行时由 SocketConfig（Socket.IO）替代，
    // 保留此类作为降级/对照实现，便于未来在不需要 Socket.IO 的场景下快速切换。
    // 由 Spring 容器注入；启用本类（去掉 @Configuration 注释）后，前端 ws://host/ws 会路由到此 handler。
    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;

    /**
     * 注册原生 WebSocket 端点。
     * 路径 /ws 与前端 wsClient 默认连接地址保持一致；
     * setAllowedOrigins 仅放通本地 Vite/CRA 开发端口，避免生产环境 CORS 误开放；
     * withSockJS 提供浏览器不支持 WebSocket 时的长轮询降级，提升兼容性。
     *
     * 契约：
     * - 入参 registry：Spring 提供的注册器，仅在应用启动时被调用一次。
     * - 副作用：把 chatWebSocketHandler 绑定到 /ws，并安装 SockJS 降级握手端点。
     * - 调用方：Spring 容器在 WebSocket 初始化阶段触发。
     * - 返回值：void。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws")
            .setAllowedOrigins("http://localhost:5173", "http://localhost:3000")
            .withSockJS();
    }
}
