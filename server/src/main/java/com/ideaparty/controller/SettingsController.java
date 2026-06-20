package com.ideaparty.controller;

import com.ideaparty.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户级 API Key 设置的 REST 入口。
 * 存在原因：让登录用户在前端页面自助配置自己的 LLM API Key（项目约束：Key 不能写死在后端配置或暴露在前端代码中），
 * 由前端按需从后端拉取当前值并通过此控制器更新，配合 {@link SettingsService} 完成加密落库与读取。
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    // 记录 Key 更新、清除等敏感操作，便于排障与审计；info 级别即可，避免泄露 Key 本身。
    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    // 由 Lombok @RequiredArgsConstructor 注入；业务逻辑下沉到 service，controller 只做参数解析与响应包装。
    private final SettingsService settingsService;

    /**
     * 返回当前用户已保存的 API Key。
     * 契约：未设置时返回空串而非 null，便于前端统一处理（避免 JSON 字段缺失导致的 undefined 判断）。
     * 调用方：前端设置页加载时拉取一次用于回显。
     */
    @GetMapping("/api-key")
    public ResponseEntity<Map<String, String>> getApiKey() {
        return ResponseEntity.ok(Map.of("apiKey", settingsService.getApiKey() != null ? settingsService.getApiKey() : ""));
    }

    /**
     * 保存或覆盖当前用户的 API Key。
     * 契约：入参 body.apiKey 为空或纯空白时静默忽略（不抛错），保证前端“可空提交”体验；
     * 写入前 trim 避免误带的空格导致 Key 失效。
     * 副作用：底层 service 会加密后持久化，并可能刷新下游 AI 客户端的 Key 缓存。
     */
    @PostMapping("/api-key")
    public ResponseEntity<Void> setApiKey(@RequestBody Map<String, String> body) {
        String apiKey = body.get("apiKey");
        if (apiKey != null && !apiKey.isBlank()) {
            settingsService.setApiKey(apiKey.trim());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 清除当前用户的 API Key。
     * 契约：无入参；调用后用户将回退到无 Key 状态（下游 AI 调用会失败直到重新设置）。
     * 调用方：前端设置页的“清除/重置”动作。
     */
    @DeleteMapping("/api-key")
    public ResponseEntity<Void> clearApiKey() {
        settingsService.clearApiKey();
        return ResponseEntity.ok().build();
    }
}
