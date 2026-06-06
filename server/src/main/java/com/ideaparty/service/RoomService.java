package com.ideaparty.service;

import com.ideaparty.dto.CreateRoomRequest;
import com.ideaparty.dto.RoomResponse;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Room;
import com.ideaparty.entity.RoomMember;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.RoomMemberRepository;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class RoomService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final CharacterRepository characterRepository;
    private final RoomMemberRepository roomMemberRepository;

    public RoomResponse create(UUID userId, CreateRoomRequest request) {
        log.info("[DEBUG] Creating room for user: {}", userId);

        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Room room = Room.builder()
                .name(request.getName())
                .topic(request.getTopic())
                .owner(owner)
                .mode(normalizeMode(request.getMode()))
                .build();

        Room saved = roomRepository.save(room);

        // Add characters (group mode). Mirrors the findById + add pattern from addCharacterToRoom.
        // No ownership/visibility check here because the existing addCharacterToRoom does not perform one either.
        if (request.getCharacterIds() != null && !request.getCharacterIds().isEmpty()) {
            for (UUID characterId : request.getCharacterIds()) {
                Character character = characterRepository.findById(characterId)
                        .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));
                saved.getCharacters().add(character);
            }
            saved = roomRepository.save(saved);
        }

        // Add owner as a member
        RoomMember ownerMember = RoomMember.builder()
                .room(saved)
                .user(owner)
                .role("owner")
                .status("active")
                .build();
        roomMemberRepository.save(ownerMember);

        log.info("[DEBUG] Room created with id: {} with {} characters", saved.getId(), saved.getCharacters().size());

        return RoomResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> findByUserId(UUID userId) {
        log.info("[DEBUG] Finding rooms for user: {}", userId);

        return roomRepository.findRoomsByMemberUserId(userId).stream()
                .map(RoomResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public void deleteIfOwner(UUID roomId, UUID userId) {
        log.info("[DEBUG] Deleting room {} for user {}", roomId, userId);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));

        if (!room.getOwner().getId().equals(userId)) {
            log.warn("[DEBUG] User {} is not owner of room {}", userId, roomId);
            throw new AccessDeniedException("You are not the owner of this room");
        }

        roomRepository.delete(room);
        log.info("[DEBUG] Room {} deleted successfully", roomId);
    }

    public RoomResponse addCharacterToRoom(UUID roomId, UUID characterId, UUID userId) {
        log.info("[DEBUG] Adding character {} to room {} by user {}", characterId, roomId, userId);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));

        // Check if user is a member
        if (!roomMemberRepository.isMember(roomId, userId)) {
            log.warn("[DEBUG] User {} is not a member of room {}", userId, roomId);
            throw new AccessDeniedException("You are not a member of this room");
        }

        // Single rooms are 1-on-1 and immutable in membership.
        if ("single".equalsIgnoreCase(room.getMode())) {
            log.warn("[DEBUG] User {} tried to add character to single-mode room {}", userId, roomId);
            throw new AccessDeniedException("Single-mode rooms cannot accept additional characters");
        }

        Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));

        room.getCharacters().add(character);
        Room saved = roomRepository.save(room);

        log.info("[DEBUG] Character {} added to room {}", characterId, roomId);

        return RoomResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public RoomResponse findById(UUID roomId) {
        return roomRepository.findWithCharactersById(roomId)
                .map(RoomResponse::fromEntity)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));
    }

    public void recordEnter(UUID roomId, UUID userId) {
        log.info("[DEBUG] Recording enter for room {} by user {}", roomId, userId);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));

        // Check if user is a member
        if (!roomMemberRepository.isMember(roomId, userId)) {
            log.warn("[DEBUG] User {} is not a member of room {}", userId, roomId);
            throw new AccessDeniedException("You are not a member of this room");
        }

        room.setLastEnterTime(Instant.now());
        roomRepository.save(room);
        log.info("[DEBUG] Updated lastEnterTime for room {}", roomId);
    }

    public RoomResponse updateChatMode(UUID roomId, UUID userId, String chatMode, Integer maxDiscussionRounds) {
        log.info("[DEBUG] Updating chat mode for room {} by user {}", roomId, userId);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));

        // Check if user is a member
        if (!roomMemberRepository.isMember(roomId, userId)) {
            log.warn("[DEBUG] User {} is not a member of room {}", userId, roomId);
            throw new AccessDeniedException("You are not a member of this room");
        }

        if (chatMode != null) {
            room.setChatMode(chatMode);
        }
        if (maxDiscussionRounds != null) {
            room.setMaxDiscussionRounds(maxDiscussionRounds);
        }

        Room saved = roomRepository.save(room);
        log.info("[DEBUG] Room {} chat mode updated to {}", roomId, chatMode);

        return RoomResponse.fromEntity(saved);
    }

    /**
     * Normalize the requested room mode.
     * Accepts "single" or "group" (case-insensitive). Anything else falls back
     * to "group" so legacy clients (and the existing "starts-chat-with-character"
     * flow) still work.
     */
    private static String normalizeMode(String requested) {
        if (requested == null) return "group";
        String lower = requested.trim().toLowerCase();
        return "single".equals(lower) ? "single" : "group";
    }
}
