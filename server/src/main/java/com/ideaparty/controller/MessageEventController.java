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

@RestController
@RequiredArgsConstructor
@Slf4j
public class MessageEventController {

    private final MessageEventService eventService;
    private final UserRepository userRepository;

    @PostMapping("/api/messages/{messageId}/events")
    public ResponseEntity<Void> record(
            Authentication auth,
            @PathVariable String messageId,
            @Valid @RequestBody RecordEventRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] event user={} message={} type={}", userId, messageId, request.getEventType());
        eventService.record(userId, messageId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Admin-only aggregated signals. Mounted under /api/admin/messages so the
     * existing admin auth check (isAdmin || whitelist) applies.
     */
    @GetMapping("/api/admin/messages/{messageId}/signals")
    public ResponseEntity<MessageSignalsResponse> signals(
            Authentication auth,
            @PathVariable String messageId) {
        requireAdmin(auth);
        return ResponseEntity.ok(eventService.aggregate(messageId));
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
