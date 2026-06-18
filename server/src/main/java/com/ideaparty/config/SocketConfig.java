package com.ideaparty.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.ideaparty.socket.ChatSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WebSocket 路由注册配置：将 AI 群聊消息处理器挂载到 /ws 端点，
 * 配合 {@link ChatSocketHandler} 提供实时聊天能力，供前端 socket.io-client 连接使用。
 */
@Configuration
@EnableWebSocket
public class SocketConfig implements WebSocketConfigurer {

    @Autowired
    // 注入业务 handler：处理 AI 角色群聊的 STOMP/WS 消息收发，配置类只负责路由挂载
    private ChatSocketHandler chatSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatSocketHandler, "/ws")
            // 放开跨域：前端开发态独立 dev server，需要允许跨源 WS 握手
            .setAllowedOrigins("*");
    }
}
