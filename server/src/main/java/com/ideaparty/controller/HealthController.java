package com.ideaparty.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供轻量级 HTTP 健康检查端点，供前端启动探活、负载均衡器探针和运维监控使用。
 * 与 WebSocket/STOMP 长连接分离，避免占用业务路径资源；统一挂载在 /api 前缀下，便于网关统一路由。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    /**
     * 返回服务存活状态与标识，供前端应用初始化时探测后端是否就绪，以及探针/监控系统轮询。
     * 固定返回 200 OK + status=UP；无副作用、无入参，调用方可以高频调用而不会影响业务。
     *
     * @return 包含 status（存活标记）与 service（服务名，便于在多服务环境区分来源）的 JSON 响应体
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "IdeaParty Server"
        ));
    }
}
