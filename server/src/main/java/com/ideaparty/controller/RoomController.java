package com.ideaparty.controller;

import com.ideaparty.dto.CreateRoomRequest;
import com.ideaparty.dto.RoomResponse;
import com.ideaparty.entity.Room;
import com.ideaparty.service.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(@RequestBody CreateRoomRequest request) {
        Room room = roomService.createRoom(
            request.getName(),
            request.getTheme(),
            request.getCharacterIds()
        );
        return ResponseEntity.ok(RoomResponse.fromEntity(room));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomResponse> getRoomById(@PathVariable String id) {
        return roomService.getRoomById(id)
            .map(RoomResponse::fromEntity)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<RoomResponse>> getAllRooms() {
        List<RoomResponse> rooms = roomService.getAllRooms()
            .stream()
            .map(RoomResponse::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(rooms);
    }

    @GetMapping("/theme/{theme}")
    public ResponseEntity<List<RoomResponse>> getRoomsByTheme(@PathVariable String theme) {
        List<RoomResponse> rooms = roomService.getRoomsByTheme(theme)
            .stream()
            .map(RoomResponse::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(rooms);
    }

    @PostMapping("/{id}/characters")
    public ResponseEntity<RoomResponse> addCharacterToRoom(
            @PathVariable String id,
            @RequestBody AddCharacterRequest request) {
        try {
            Room room = roomService.addCharacterToRoom(id, request.getCharacterId());
            return ResponseEntity.ok(RoomResponse.fromEntity(room));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    public static class AddCharacterRequest {
        private String characterId;

        public String getCharacterId() { return characterId; }
        public void setCharacterId(String characterId) { this.characterId = characterId; }
    }
}
