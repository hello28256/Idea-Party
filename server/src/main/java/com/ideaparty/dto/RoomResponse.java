package com.ideaparty.dto;

import com.ideaparty.entity.Room;
import com.ideaparty.util.ImageUrlResolver;
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
// Lombok @Builder：让 fromEntity 与测试代码能用流式 API 构造 DTO，避免手写全字段构造器
@Builder
// Lombok @NoArgsConstructor：Jackson 反序列化、@ModelAttribute 绑定等场景需要一个无参构造器
@NoArgsConstructor
// Lombok @AllArgsConstructor：让单元测试或某些框架（如 MapStruct）能直接以全字段构造 DTO
@AllArgsConstructor
public class RoomResponse {

    // 聊天室主键 UUID：对外暴露，避免直接泄漏自增数据库 ID；前端据此做路由与状态关联
    private UUID id;
    // 聊天室标题：在前端房间列表与聊天头部直接展示
    private String name;
    // 聊天室主题/描述：用于列表副标题、详情页与 AI 上下文提示
    private String topic;
    // 创建者 ID：权限校验与「我的房间」筛选的最小信息
    private UUID ownerId;
    // 冗余字段：直接返回所有者显示名，省去前端按 ownerId 再查用户接口的开销与一致性麻烦
    private String ownerName;
    // 冗余计数：列表页只需数量时不必反序列化整个 characters 数组
    private int characterCount;
    // 嵌套角色列表：详情页与「进入聊天」时直接可用；空集合/未加载以 null 区分（见 fromEntity）
    private List<CharacterResponse> characters;
    // 创建时间：前端排序与「创建于 X 天前」展示用
    private Instant createdAt;
    // 最近更新时间：用于列表排序与变更提示
    private Instant updatedAt;
    // 当前用户最近一次进入时间：用于「最近访问」置顶与未读提示
    private Instant lastEnterTime;
    // 聊天模式枚举字符串（如 FREE/MODERATED）：前端按值渲染不同 UI 与交互
    private String chatMode;
    // 最大讨论轮数：NULL 表示无限；前端用于禁用「继续讨论」按钮或显示提示
    private Integer maxDiscussionRounds;
    // 房间运行模式字符串：与 chatMode 区分（如 PRACTICE/DEBATE），驱动差异化提示词模板
    private String mode;

    /**
     * 实体到 DTO 的工厂方法：处理字符集合的空安全投影，避免空集合序列化成 [null] 或触发懒加载异常。
     * 调用方应在事务内传入已加载关联的 Room，否则 owner/characters 懒访问可能抛 LazyInitializationException。
     *
     * @param room 已加载 owner 与 characters 关联的 Room 实体
     * @return 填充好所有展示字段的 RoomResponse；characters 可能为 null
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

    /**
     * 把嵌套的 CharacterResponse.avatarUrl 统一转成完整 OSS URL。
     * 由调用方(RoomController / Service)在序列化前调一次。
     */
    public RoomResponse resolveImageUrls(ImageUrlResolver resolver) {
        if (this.characters != null) {
            this.characters = this.characters.stream()
                    .map(c -> c.resolveImageUrls(resolver))
                    .collect(Collectors.toList());
        }
        return this;
    }
}
