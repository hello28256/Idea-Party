package com.ideaparty.controller;

import com.ideaparty.dto.FeedbackResponse;
import com.ideaparty.dto.SubmitFeedbackRequest;
import com.ideaparty.service.MessageFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/messages/{messageId}/feedback")
@RequiredArgsConstructor
@Slf4j
public class MessageFeedbackController {

    private final MessageFeedbackService feedbackService;

    @PostMapping
    public ResponseEntity<FeedbackResponse> submit(
            Authentication auth,
            @PathVariable String messageId,
            @Valid @RequestBody SubmitFeedbackRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] POST feedback user={} message={}", userId, messageId);
        FeedbackResponse response = feedbackService.submit(userId, messageId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<FeedbackResponse> get(
            Authentication auth,
            @PathVariable String messageId) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] GET feedback user={} message={}", userId, messageId);
        return feedbackService.get(userId, messageId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(
            Authentication auth,
            @PathVariable String messageId) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] DELETE feedback user={} message={}", userId, messageId);
        feedbackService.delete(userId, messageId);
        return ResponseEntity.noContent().build();
    }
}
