package com.ideaparty.controller;

import com.ideaparty.dto.MessageResponse;
import com.ideaparty.dto.SendMessageRequest;
import com.ideaparty.entity.Message;
import com.ideaparty.service.MessageService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
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
    public ResponseEntity<MessageResponse> sendMessage(
            @PathVariable String roomId,
            @RequestBody SendMessageRequest request) {
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
