package com.ideaparty.controller;

import com.ideaparty.dto.MessageSignalsResponse;
import com.ideaparty.dto.RecordEventRequest;
import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.service.MessageEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 消息事件 REST 入口。
 * 负责把前端的"点赞/点踩/举报/查看"等行为落库并提供管理员维度的聚合查询。
 * 配合 MessageEventService 持久化事件，依赖 Spring Security 的 Authentication 拿到当前用户。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class MessageEventController {

    // 业务服务层：负责事件的写入与聚合统计，由 Lombok @RequiredArgsConstructor 注入。
    private final MessageEventService eventService;
    // 管理员校验需要读取 isAdmin 字段，因此直接注入 UserRepository 而非走 service，避免在 service 里再加一层间接跳转。
    private final UserRepository userRepository;

    /**
     * 记录一条消息事件（like/dislike/report/view 等）。
     * 调用方：前端聊天面板在用户产生交互时调用；返回 204 表示写入成功但不返回 body。
     * 副作用：会向 MessageEventService 持久化一行事件，并在日志中打印用户/消息/事件类型三元组用于排查。
     */
    @PostMapping("/api/messages/{messageId}/events")
    public ResponseEntity<Void> record(Authentication auth, @PathVariable String messageId, @Valid @RequestBody RecordEventRequest request) {
        // auth.getName() 存的是 subject（即用户 UUID 字符串），这里直接解析为 UUID 供下游使用。
        UUID userId = UUID.fromString(auth.getName());
        // 按用户调试约定加 [DEBUG] 前缀，便于在日志里快速过滤消息事件轨迹。
        log.info("[DEBUG] event user={} message={} type={}", userId, messageId, request.getEventType());
        eventService.record(userId, messageId, request);
        // 事件写入属于"做完即忘"语义，204 No Content 比 200 + 空 body 更准确。
        return ResponseEntity.noContent().build();
    }

    /**
     * Admin-only aggregated signals. Mounted under /api/admin/messages so the
     * existing admin auth check (isAdmin || whitelist) applies.
     */
    /**
     * 管理员聚合接口：返回某条消息的事件统计（点赞/点踩/举报次数等）。
     * 调用方：管理后台审核面板；要求调用者具备管理员权限，否则抛 AccessDeniedException。
     */
    @GetMapping("/api/admin/messages/{messageId}/signals")
    public ResponseEntity<MessageSignalsResponse> signals(Authentication auth, @PathVariable String messageId) {
        // 在 controller 层做权限校验：复用现有 admin 检查约定，避免在 service 里再分裂权限逻辑。
        requireAdmin(auth);
        return ResponseEntity.ok(eventService.aggregate(messageId));
    }

    /**
     * 守卫方法：校验当前 Authentication 对应用户是否拥有 isAdmin=true。
     * 找不到用户或非管理员时统一抛出 AccessDeniedException，由全局异常处理器转成 403。
     * 用 Boolean.TRUE.equals 防止 NPE：用户表里 isAdmin 默认 false，但仍可能是 null。
     */
    private void requireAdmin(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && Boolean.TRUE.equals(user.getIsAdmin())) {
            return;
        }
        throw new AccessDeniedException("Admin permission required");
    }
}
