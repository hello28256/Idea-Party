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

    // 负责聊天室的创建/查询/成员与角色编排；权限校验（成员/房主）在这里前置，
    // 让 Controller 只需要转发请求并处理 DTO 转换。

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
        // 选择"在创建时直接绑定角色"而非事务结束再追加，避免出现"已建空房间但角色未挂上"的中间态，
        // 同时复用 Room.characters 的级联保存，省一次显式事务。
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
        // 只列出"我是成员"的房间（不仅是我创建的），匹配前端"我的聊天室"列表的语义。
        log.info("[DEBUG] Finding rooms for user: {}", userId);

        return roomRepository.findRoomsByMemberUserId(userId).stream()
                .map(RoomResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public void deleteIfOwner(UUID roomId, UUID userId) {
        // 仅房主可删除：刻意只比较 owner，不退化成"任一成员都能解散"，避免误删他人创建的会话。
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
        // 仅校验"成员资格"而非房主：设计上允许任何成员拉新角色入群，体现多人协作编排。
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
        // 仅刷新 lastEnterTime，用于"最近进入"排序；不写消息，避免污染聊天历史。
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
        // 任意成员即可调整发言模式：Moderator Agent 在每轮对话中实时读取这两个字段，
        // 因此变更要立即落库而不是缓存到会话内。
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
