package com.ideaparty.controller;

import com.ideaparty.dto.MessageResponse;
import com.ideaparty.dto.SendMessageRequest;
import com.ideaparty.entity.Message;
import com.ideaparty.service.MessageService;
import com.ideaparty.service.ModerationService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
public class MessageController {

    private final MessageService messageService;
    private final ModerationService moderationService;

    public MessageController(MessageService messageService, ModerationService moderationService) {
        this.messageService = messageService;
        this.moderationService = moderationService;
    }

    @GetMapping
    public ResponseEntity<List<MessageResponse>> getMessages(@PathVariable String roomId) {
        UUID roomUuid = parseRoomId(roomId);
        List<MessageResponse> messages = messageService.getMessagesByRoomId(roomUuid).stream()
            .map(MessageResponse::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/paginated")
    public ResponseEntity<Page<MessageResponse>> getMessagesPaginated(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID roomUuid = parseRoomId(roomId);
        Page<Message> messages = messageService.getMessagesPaginated(roomUuid, page, size);
        return ResponseEntity.ok(messages.map(MessageResponse::fromEntity));
    }

    @PostMapping
    public ResponseEntity<MessageResponse> sendMessage(
            Authentication auth,
            @PathVariable String roomId,
            @RequestBody SendMessageRequest request) {
        // 统一交给 GlobalExceptionHandler：401/400/404 等都用 ErrorResponse 返回
        if (auth == null) {
            throw new org.springframework.security.access.AccessDeniedException("请先登录");
        }
        UUID roomUuid = parseRoomId(roomId);

        ModerationService.ModerationResult result = moderationService.moderate(request.getContent());
        if (!result.isAllowed()) {
            throw new IllegalArgumentException(result.getReason());
        }

        UUID userId = UUID.fromString(auth.getName());
        Message.SenderType senderType = Message.SenderType.valueOf(request.getSenderType());
        UUID characterUuid = request.getCharacterId() != null ? UUID.fromString(request.getCharacterId()) : null;
        Message message = messageService.saveMessage(
            roomUuid,
            characterUuid,
            senderType,
            request.getContent(),
            userId
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageResponse.fromEntity(message));
    }

    /**
     * 把 controller 层的字符串校验集中在此：保持方法签名只接 UUID，
     * 让 GlobalExceptionHandler 统一转换为 ErrorResponse，避免 controller 内联 Map.of 风格。
     */
    private UUID parseRoomId(String roomId) {
        if (roomId == null || roomId.isBlank() || "null".equals(roomId) || "undefined".equals(roomId)) {
            throw new IllegalArgumentException("聊天室 ID 不能为空");
        }
        try {
            return UUID.fromString(roomId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的聊天室 ID 格式");
        }
    }
}