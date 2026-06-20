package com.ideaparty.controller;

import com.ideaparty.dto.AdminFeedbackDetail;
import com.ideaparty.dto.AdminFeedbackListItem;
import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.service.AdminFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 管理端反馈查看接口。
 * 承担用户反馈（Bug/Feature/Question 等）的列表与详情查询，配合 AdminFeedbackService 做数据装配。
 * 与 AdminFeedbackService、UserRepository 协作完成 admin 鉴权与分页检索。
 */
@RestController
@RequestMapping("/api/admin/feedbacks")
@RequiredArgsConstructor
@Slf4j
public class AdminFeedbackController {

    /** 反馈查询业务封装：承担分页/过滤与详情组装，避免 Controller 直接接触 Repository。 */
    private final AdminFeedbackService adminFeedbackService;
    /** 用户表查询：用于读取 User.isAdmin 字段，完成 admin 鉴权快速路径。 */
    private final UserRepository userRepository;

    /** 从 application.yml 解析 bootstrap admin 白名单；当 User.isAdmin=false 时作为兜底。 */
    @Value("${app.admin.user-ids:}")
    private String adminUserIdsConfig;

    /**
     * 解析 application.yml 中的 bootstrap admin 白名单。
     * 返回值每次调用都重新解析（不缓存）：保证 yml 热更新或测试替换时立即生效。
     * 返回 HashSet 以便 contains 查询为 O(1)。
     */
    private Set<UUID> adminWhitelist() {
        if (adminUserIdsConfig == null || adminUserIdsConfig.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(adminUserIdsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * admin 鉴权闸门，所有 /api/admin/** 接口在执行业务前必须调用。
     * 先看 User.isAdmin（数据库事实），再看 application.yml 白名单（bootstrap/灾备）。
     * 失败抛 AccessDeniedException，由全局异常处理器转 403。
     */
    private void requireAdmin(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        // 快路径：User.isAdmin
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && Boolean.TRUE.equals(user.getIsAdmin())) {
            return;
        }
        // 兜底：bootstrap 白名单（无需 DB 写入）
        if (adminWhitelist().contains(userId)) {
            log.info("[DEBUG] admin access granted via app.admin.user-ids whitelist for user {}", userId);
            return;
        }
        throw new AccessDeniedException("Admin permission required");
    }

    /**
     * 反馈分页列表。
     * 入参支持按 type/category/userId/from/to 多维过滤，from/to 用 ISO-8601 Instant。
     * 调用方：管理后台反馈管理页；副作用：写一条 [DEBUG] 日志便于排查。
     */
    @GetMapping
    public ResponseEntity<Page<AdminFeedbackListItem>> list(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) FeedbackType type,
            @RequestParam(required = false) FeedbackCategory category,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        requireAdmin(auth);
        log.info("[DEBUG] admin list feedback page={} size={} type={} category={}", page, size, type, category);

        Page<AdminFeedbackListItem> result = adminFeedbackService.list(page, size, type, category, userId, from, to);
        return ResponseEntity.ok(result);
    }

    /**
 * 反馈详情。
     * 入参 id 为反馈主键 UUID；调用方：管理后台点击列表行进入详情。
     * 副作用：写一条 [DEBUG] 日志记录被查看的反馈 id；返回 AdminFeedbackDetail（含正文/截图等完整字段）。
     */
    @GetMapping("/{id}")
    public ResponseEntity<AdminFeedbackDetail> detail(
            Authentication auth,
            @PathVariable UUID id) {
        requireAdmin(auth);
        log.info("[DEBUG] admin feedback detail id={}", id);
        return ResponseEntity.ok(adminFeedbackService.detail(id));
    }
}
