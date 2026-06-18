package com.ideaparty.dto;

import com.ideaparty.entity.Room;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天室对外返回视图：从 Room 实体投影而来，供 REST 控制器序列化给前端。
 * 同时携带 ownerName 等冗余字段，避免前端再次请求用户信息；与 CharacterResponse 配合输出嵌套角色列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomResponse {

    private UUID id;
    private String name;
    private String topic;
    private UUID ownerId;
    // 冗余字段：直接返回所有者显示名，省去前端按 ownerId 再查用户接口的开销与一致性麻烦
    private String ownerName;
    // 冗余计数：列表页只需数量时不必反序列化整个 characters 数组
    private int characterCount;
    private List<CharacterResponse> characters;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastEnterTime;
    private String chatMode;
    private Integer maxDiscussionRounds;
    private String mode;

    /**
     * 实体到 DTO 的工厂方法：处理字符集合的空安全投影，避免空集合序列化成 [null] 或触发懒加载异常。
     * 调用方应在事务内传入已加载关联的 Room，否则 owner/characters 懒访问可能抛 LazyInitializationException。
     */
    public static RoomResponse fromEntity(Room room) {
        // 故意保留 null 而非空列表：让前端可据此区分「未加载」与「空集合」，触发不同的占位 UI
        List<CharacterResponse> characterList = null;
        if (room.getCharacters() != null && !room.getCharacters().isEmpty()) {
            characterList = room.getCharacters().stream()
                    .map(CharacterResponse::fromEntity)
                    .collect(Collectors.toList());
        }
        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName())
                .topic(room.getTopic())
                .ownerId(room.getOwner().getId())
                .ownerName(room.getOwner().getDisplayName())
                .characterCount(room.getCharacterCount())
                .characters(characterList)
                .createdAt(room.getCreatedAt())
                .updatedAt(room.getUpdatedAt())
                .lastEnterTime(room.getLastEnterTime())
                .chatMode(room.getChatMode())
                .maxDiscussionRounds(room.getMaxDiscussionRounds())
                .mode(room.getMode())
                .build();
    }
}
