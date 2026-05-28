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
    public ResponseEntity<?> getMessages(@PathVariable String roomId) {
        if (roomId == null || roomId.isBlank() || "null".equals(roomId) || "undefined".equals(roomId)) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "聊天室 ID 不能为空"));
        }
        try {
            List<MessageResponse> messages = messageService.getMessagesByRoomId(UUID.fromString(roomId))
                .stream()
                .map(MessageResponse::fromEntity)
                .collect(Collectors.toList());
            return ResponseEntity.ok(messages);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "无效的聊天室 ID 格式"));
        }
    }

    @GetMapping("/paginated")
    public ResponseEntity<?> getMessagesPaginated(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (roomId == null || roomId.isBlank() || "null".equals(roomId) || "undefined".equals(roomId)) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "聊天室 ID 不能为空"));
        }
        try {
            Page<Message> messages = messageService.getMessagesPaginated(UUID.fromString(roomId), page, size);
            Page<MessageResponse> response = messages.map(MessageResponse::fromEntity);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "无效的聊天室 ID 格式"));
        }
    }

    @PostMapping
    public ResponseEntity<?> sendMessage(
            Authentication auth,
            @PathVariable String roomId,
            @RequestBody SendMessageRequest request) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(java.util.Map.of("message", "请先登录"));
        }
        if (roomId == null || roomId.isBlank() || "null".equals(roomId) || "undefined".equals(roomId)) {
            return ResponseEntity.badRequest()
                .body(java.util.Map.of("message", "聊天室 ID 不能为空"));
        }
        try {
            UUID userId = UUID.fromString(auth.getName());
            UUID roomUuid = UUID.fromString(roomId);

            ModerationService.ModerationResult result = moderationService.moderate(request.getContent());
            if (!result.isAllowed()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(java.util.Map.of("message", result.getReason()));
            }

            Message.SenderType senderType = Message.SenderType.valueOf(request.getSenderType());
            UUID characterUuid = request.getCharacterId() != null ? UUID.fromString(request.getCharacterId()) : null;
            Message message = messageService.saveMessage(
                roomUuid,
                characterUuid,
                senderType,
                request.getContent(),
                userId
            );
            return ResponseEntity.ok(MessageResponse.fromEntity(message));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(java.util.Map.of("message", "无效的请求参数: " + e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
