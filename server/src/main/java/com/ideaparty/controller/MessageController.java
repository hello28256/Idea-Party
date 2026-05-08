package com.ideaparty.controller;

import com.ideaparty.dto.MessageResponse;
import com.ideaparty.dto.SendMessageRequest;
import com.ideaparty.entity.Message;
import com.ideaparty.service.MessageService;
import com.ideaparty.service.ModerationService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
        List<MessageResponse> messages = messageService.getMessagesByRoomId(roomId)
            .stream()
            .map(MessageResponse::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/paginated")
    public ResponseEntity<Page<MessageResponse>> getMessagesPaginated(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<Message> messages = messageService.getMessagesPaginated(roomId, page, size);
        Page<MessageResponse> response = messages.map(MessageResponse::fromEntity);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<?> sendMessage(
            @PathVariable String roomId,
            @RequestBody SendMessageRequest request) {
        ModerationService.ModerationResult result = moderationService.moderate(request.getContent());
        if (!result.isAllowed()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(java.util.Map.of("error", result.getReason()));
        }

        try {
            Message message = messageService.saveMessage(
                roomId,
                request.getContent(),
                request.getRole(),
                request.getCharacterId()
            );
            return ResponseEntity.ok(MessageResponse.fromEntity(message));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
