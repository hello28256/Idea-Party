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
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/feedbacks")
@RequiredArgsConstructor
@Slf4j
public class AdminFeedbackController {

    private final AdminFeedbackService adminFeedbackService;
    private final UserRepository userRepository;

    private void requireAdmin(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("User not found"));
        if (!Boolean.TRUE.equals(user.getIsAdmin())) {
            throw new AccessDeniedException("Admin permission required");
        }
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
