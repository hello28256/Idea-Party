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

@RestController
@RequestMapping("/api/admin/feedbacks")
@RequiredArgsConstructor
@Slf4j
public class AdminFeedbackController {

    private final AdminFeedbackService adminFeedbackService;
    private final UserRepository userRepository;

    /** Bootstrap admin whitelist from application.yml. Fallback when User.isAdmin=false. */
    @Value("${app.admin.user-ids:}")
    private String adminUserIdsConfig;

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

    private void requireAdmin(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        // Fast path: User.isAdmin
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && Boolean.TRUE.equals(user.getIsAdmin())) {
            return;
        }
        // Fallback: bootstrap whitelist (does not require DB write)
        if (adminWhitelist().contains(userId)) {
            log.info("[DEBUG] admin access granted via app.admin.user-ids whitelist for user {}", userId);
            return;
        }
        throw new AccessDeniedException("Admin permission required");
    }

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

    @GetMapping("/{id}")
    public ResponseEntity<AdminFeedbackDetail> detail(
            Authentication auth,
            @PathVariable UUID id) {
        requireAdmin(auth);
        log.info("[DEBUG] admin feedback detail id={}", id);
        return ResponseEntity.ok(adminFeedbackService.detail(id));
    }
}
