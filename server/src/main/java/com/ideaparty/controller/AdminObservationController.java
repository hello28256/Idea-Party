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

@RestController
@RequestMapping("/api/admin/messages")
@RequiredArgsConstructor
@Slf4j
public class AdminObservationController {

    private final AdminObservationService observationService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<Page<AdminMessageObservationItem>> list(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        requireAdmin(auth);
        return ResponseEntity.ok(observationService.list(page, size, status));
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<AdminMessageObservationItem> detail(
            Authentication auth,
            @PathVariable String messageId) {
        requireAdmin(auth);
        UUID viewerId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(observationService.detail(messageId, viewerId));
    }

    private void requireAdmin(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && Boolean.TRUE.equals(user.getIsAdmin())) {
            return;
        }
        throw new AccessDeniedException("Admin permission required");
    }
}
