package com.ideaparty.controller;

import com.ideaparty.dto.AdminMessageObservationItem;
import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.service.AdminObservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 管理端聊天消息观察接口，负责在管理员权限下分页检索全站聊天消息及查看单条详情。
 * 之所以单独存在：普通用户只能看自己聊天室内的消息，平台运营/审计场景需要全局只读视图，
 * 因此提供该 Controller 与 AdminObservationService 配合，给后台管理页面使用。
 */
@RestController
@RequestMapping("/api/admin/messages")
@RequiredArgsConstructor
@Slf4j
public class AdminObservationController {

    /**
     * 消息观察业务服务，封装了全站消息分页查询与单条详情读取逻辑，避免在 Controller 中堆 SQL/DSL。
     */
    private final AdminObservationService observationService;

    /**
     * 用户仓储，用于在请求入口校验当前登录用户是否具备 admin 标记，权限判定走应用层而非 Spring Security 注解。
     */
    private final UserRepository userRepository;

    /**
     * 列出全站聊天消息（管理员视角），支持分页与按状态过滤。
     * 入参：page 从 0 开始、size 单页条数、status 可选的状态过滤条件（如 PENDING/FLAGGED）。
     * 副作用：每次调用都会先校验管理员身份，校验失败抛出 AccessDeniedException 由全局异常处理器翻译。
     * 调用方：管理后台的消息观察列表页。
     *
     * @param auth  Spring Security 注入的当前认证对象，name 字段承载用户 UUID
     * @param page  页码，默认 0
     * @param size  每页大小，默认 20
     * @param status 可选的消息状态过滤值，为空表示不过滤
     * @return 包装成 Page 的消息观察项列表
     */
    @GetMapping
    public ResponseEntity<Page<AdminMessageObservationItem>> list(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        requireAdmin(auth);
        return ResponseEntity.ok(observationService.list(page, size, status));
    }

    /**
     * 查看单条消息的观察详情，包含触发该消息的上下文（聊天室、角色、prompt 等），便于管理员追溯异常输出。
     * 入参：messageId 为消息主键字符串形式；viewerId 从登录态推导，用于审计谁查看了这条消息。
     * 副作用：要求管理员身份；具体是否记录查看日志由 Service 层决定。
     * 调用方：管理后台消息列表中点击某条记录进入详情抽屉/页面时触发。
     *
     * @param auth      当前认证信息，用于身份校验与获取 viewerId
     * @param messageId 路径变量，目标消息的 ID 字符串
     * @return 单条消息的观察详情 DTO
     */
    @GetMapping("/{messageId}")
    public ResponseEntity<AdminMessageObservationItem> detail(
            Authentication auth,
            @PathVariable String messageId) {
        requireAdmin(auth);
        UUID viewerId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(observationService.detail(messageId, viewerId));
    }

    /**
     * 应用层管理员权限校验：从 Authentication 中解析 userId，查询用户记录判断 isAdmin 标记。
     * 之所以写在 Controller 而非使用 @PreAuthorize：用户表中的 isAdmin 是动态字段，
     * 需要在运行时读取最新值，避免缓存导致权限变更后无法即时生效。
     * 失败时抛出 AccessDeniedException，由全局异常处理器统一转换为 403 响应。
     *
     * @param auth Spring Security 当前认证对象
     * @throws AccessDeniedException 当用户不存在或不具备管理员身份时抛出
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
