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

/**
 * REST 入口，处理单条 AI 消息的用户反馈（点赞 / 点踩 / 撤回）。
 * 路径以 messageId 为粒度，便于前端在聊天气泡上直接调用；
 * 业务编排交给 MessageFeedbackService，本控制器只做鉴权用户解析与日志。
 */
@RestController
@RequestMapping("/api/messages/{messageId}/feedback")
@RequiredArgsConstructor
@Slf4j
public class MessageFeedbackController {

    /** 业务实现：负责反馈的 upsert / 查询 / 删除，控制器不做任何持久化逻辑。 */
    private final MessageFeedbackService feedbackService;

    /**
     * 提交或覆盖当前用户对某条消息的反馈。
     * 调用方：前端聊天界面在用户点击 like/dislike 后触发；
     * 入参必须通过 {@code @Valid} 校验，messageId 用于定位消息实体。
     */
    @PostMapping
    public ResponseEntity<FeedbackResponse> submit(Authentication auth, @PathVariable String messageId, @Valid @RequestBody SubmitFeedbackRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] POST feedback user={} message={}", userId, messageId);
        FeedbackResponse response = feedbackService.submit(userId, messageId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 查询当前用户对某条消息已有的反馈状态，用于前端高亮 like/dislike 按钮。
     * 无记录时返回 404，前端据此渲染为未投票态。
     */
    @GetMapping
    public ResponseEntity<FeedbackResponse> get(Authentication auth, @PathVariable String messageId) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] GET feedback user={} message={}", userId, messageId);
        return feedbackService.get(userId, messageId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 撤回当前用户对某条消息的反馈（取消 like/dislike）。
     * 幂等：重复删除不会报错；成功返回 204 No Content。
     */
    @DeleteMapping
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable String messageId) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] DELETE feedback user={} message={}", userId, messageId);
        feedbackService.delete(userId, messageId);
        return ResponseEntity.noContent().build();
    }
}
