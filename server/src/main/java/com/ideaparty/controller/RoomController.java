package com.ideaparty.controller;

import com.ideaparty.dto.CreateRoomRequest;
import com.ideaparty.dto.RoomResponse;
import com.ideaparty.dto.UpdateRoomModeRequest;
import com.ideaparty.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@Slf4j
public class RoomController {

    private final RoomService roomService;

    @GetMapping
    public ResponseEntity<List<RoomResponse>> getUserRooms(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Getting rooms for user: {}", userId);

        List<RoomResponse> rooms = roomService.findByUserId(userId);
        return ResponseEntity.ok(rooms);
    }

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(
            Authentication auth,
            @Valid @RequestBody CreateRoomRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Creating room for user: {}", userId);

        RoomResponse room = roomService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(room);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomResponse> getRoomById(
            Authentication auth,
            @PathVariable UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Getting room {} for user: {}", id, userId);

        RoomResponse room = roomService.findById(id);
        return ResponseEntity.ok(room);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRoom(
            Authentication auth,
            @PathVariable UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Deleting room {} for user: {}", id, userId);

        roomService.deleteIfOwner(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/characters/{characterId}")
    public ResponseEntity<RoomResponse> addCharacterToRoom(
            Authentication auth,
            @PathVariable UUID id,
            @PathVariable UUID characterId) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Adding character {} to room {} by user {}", characterId, id, userId);

        RoomResponse room = roomService.addCharacterToRoom(id, characterId, userId);
        return ResponseEntity.ok(room);
    }

    @PatchMapping("/{id}/mode")
    public ResponseEntity<RoomResponse> updateRoomMode(
            Authentication auth,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRoomModeRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Updating room {} mode by user {}", id, userId);

        RoomResponse room = roomService.updateChatMode(id, userId, request.getChatMode(), request.getMaxDiscussionRounds());
        return ResponseEntity.ok(room);
    }
}
