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

/**
 * 聊天室领域服务：负责聊天室的创建、查询、成员关系维护以及角色编排。
 * 之所以独立成 Service：把权限校验（成员/房主）和事务边界下沉到这一层，
 * 让 Controller 只负责 HTTP/DTO 转换，避免业务规则散落在多处。
 * 协作方：RoomController（HTTP 入口）、Room/Character/User 实体对应的 Repository
 * （持久化）、ChatService（消费本服务产出的 Room 进行 AI 编排）。
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class RoomService {

    // 四个 Repository 全部由 Lombok @RequiredArgsConstructor 注入，
    // final 字段保证线程安全与不可变性，单元测试时可手动构造。

    // 聊天室主表读写入口：负责 Room 实体及关联级联（characters / members）的持久化。
    private final RoomRepository roomRepository;
    // 仅用于 create() 时按 userId 反查 User 实体以便作为 owner 引用；其他场景靠 Room.owner.getId() 即可。
    private final UserRepository userRepository;
    // 用于校验角色存在性（创建房间时绑定角色、动态向群聊加角色）；不允许前端传字符串角色名拼装。
    private final CharacterRepository characterRepository;
    // 提供"用户是否是某房间成员"的判定方法（isMember），是权限校验的关键依赖。
    private final RoomMemberRepository roomMemberRepository;

    // 负责聊天室的创建/查询/成员与角色编排；权限校验（成员/房主）在这里前置，
    // 让 Controller 只需要转发请求并处理 DTO 转换。

    /**
     * 创建聊天室并完成初始编排（绑定角色 + 把房主登记为成员）。
     *
     * @param userId  当前登录用户 ID，作为新房间 owner；不存在时抛 IllegalArgumentException。
     * @param request 入参 DTO：name / topic / mode / characterIds；mode 会经过 normalizeMode 兜底。
     * @return       含 ID 与已绑定角色数量的 RoomResponse，供 Controller 直接序列化。
     *
     * 副作用：写入 room / room_member 两张表；characterIds 非空时会触发 Room.characters 的级联保存。
     */
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

    /**
     * 查询"我的聊天室"列表：返回当前用户作为成员（不仅是 owner）的全部房间。
     *
     * @param userId 当前登录用户 ID。
     * @return       RoomResponse 列表；只读事务，避免脏读与不必要的写锁。
     *
     * 调用方：前端 RoomListView 的 "my-rooms" 页面。
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> findByUserId(UUID userId) {
        // 只列出"我是成员"的房间（不仅是我创建的），匹配前端"我的聊天室"列表的语义。
        log.info("[DEBUG] Finding rooms for user: {}", userId);

        return roomRepository.findRoomsByMemberUserId(userId).stream()
                .map(RoomResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 仅房主可删除聊天室：非房主抛 AccessDeniedException；房间不存在抛 IllegalArgumentException。
     *
     * @param roomId 待删除的房间 ID。
     * @param userId 当前操作用户 ID。
     *
     * 副作用：级联删除关联的 RoomMember / Character 关联（由实体上的级联配置保证）。
     * 调用方：RoomController 的 DELETE 接口。
     */
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

    /**
     * 动态向已存在房间追加角色（仅 group 模式）。
     *
     * @param roomId      目标房间 ID；不存在时抛 IllegalArgumentException。
     * @param characterId 待加入的角色 ID；不存在时抛 IllegalArgumentException。
     * @param userId      操作人；必须为房间成员，否则抛 AccessDeniedException。
     * @return            更新后的 RoomResponse（含最新角色列表）。
     *
     * 副作用：通过 Room.characters 的级联写入关联表；调用方需注意幂等性（重复加入会生成重复关联）。
     */
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

    /**
     * 按 ID 查询房间（包含已关联角色），用于进入聊天室前的"房间详情"加载。
     *
     * @param roomId 房间 ID；不存在抛 IllegalArgumentException。
     * @return       RoomResponse；只读事务，避免脏写。
     *
     * 调用方：ChatRoomView 进入房间时拉取 Room + Characters 用于组装 Moderator 上下文。
     */
    @Transactional(readOnly = true)
    public RoomResponse findById(UUID roomId) {
        return roomRepository.findWithCharactersById(roomId)
                .map(RoomResponse::fromEntity)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));
    }

    /**
     * 记录用户进入房间，更新 Room.lastEnterTime，用于"最近进入"列表排序。
     *
     * @param roomId 目标房间 ID；不存在抛 IllegalArgumentException。
     * @param userId 当前用户 ID；必须为成员，否则抛 AccessDeniedException。
     *
     * 副作用：仅更新 Room.lastEnterTime，不会写聊天消息，避免污染对话历史。
     */
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

    /**
     * 更新房间发言模式与最大讨论轮次，任意成员均可修改。
     *
     * @param roomId               目标房间 ID；不存在抛 IllegalArgumentException。
     * @param userId               操作人；必须为成员，否则抛 AccessDeniedException。
     * @param chatMode             新发言模式；为 null 表示不修改。
     * @param maxDiscussionRounds  最大讨论轮次；为 null 表示不修改。
     * @return                     更新后的 RoomResponse。
     *
     * 副作用：落库 chatMode / maxDiscussionRounds；Moderator Agent 下一轮发言会读取新值。
     */
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
