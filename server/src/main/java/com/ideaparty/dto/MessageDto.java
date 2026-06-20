package com.ideaparty.dto;

import com.ideaparty.entity.Message;
import java.time.LocalDateTime;

/**
 * 聊天消息的对外传输对象（DTO）。
 * 用于把持久层的 {@link Message} 实体（带有 room/character/user 多对多关联）扁平化为
 * 仅含前端渲染所需字段的轻量结构，避免在 API/WebSocket 响应中直接暴露 JPA 实体和懒加载关系。
 * 主要配合 Controller / WebSocket 推送层使用，把数据库对象安全地交付给客户端。
 */
public class MessageDto {

    /** 消息唯一标识，对应实体主键；客户端去重与滚动定位依赖此字段。 */
    private String id;
    /** 所属聊天室 ID；前端按 roomId 分组渲染消息流。 */
    private String roomId;
    /** 发言角色 ID；为空表示此消息由用户本人发出。 */
    private String characterId;
    /** 发言角色名称；冗余存储是为了避免前端额外查询角色表即可直接展示昵称。 */
    private String characterName;
    /** 发送方类型（USER/AI 等枚举名）；前端据此切换气泡样式与头像来源。 */
    private String senderType;
    /** 真实用户 ID；用户发言时填充，AI 角色发言时为 null。 */
    private String userId;
    /** 消息正文；已包含 Moderator 编排后的最终文本。 */
    private String content;
    /** 发言角色头像 URL；冗余存储以减少前端渲染时的二次请求。 */
    private String avatarUrl;
    /** 消息创建时间；前端按时间排序和分组展示。 */
    private LocalDateTime createdAt;

    /**
     * 无参构造器，供 Jackson 等反序列化框架通过反射创建实例。
     * 不由业务代码直接调用。
     */
    public MessageDto() {}

    /**
     * 把持久层 {@link Message} 实体扁平化为前端友好的 DTO。
     * 关联字段（room/character/user）若为 null 则对应 DTO 字段保持 null，
     * 调用方无需再做空值判断即可直接序列化。
     *
     * @param message 数据库中的消息实体，关联对象可能为 null（如纯用户消息没有 character）
     * @return 可直接写入 HTTP 响应或 WebSocket 帧的 DTO
     */
    public static MessageDto fromEntity(Message message) {
        MessageDto dto = new MessageDto();
        dto.setId(message.getId());
        dto.setRoomId(message.getRoom().getId().toString());
        dto.setSenderType(message.getSenderType().name());

        if (message.getCharacter() != null) {
            dto.setCharacterId(message.getCharacter().getId().toString());
            dto.setCharacterName(message.getCharacter().getName());
            dto.setAvatarUrl(message.getCharacter().getAvatarUrl());
        }

        if (message.getUser() != null) {
            dto.setUserId(message.getUser().getId().toString());
        }

        dto.setContent(message.getContent());
        dto.setCreatedAt(message.getCreatedAt());

        return dto;
    }

    /** 读取消息 ID，主要供 Jackson 序列化输出。 */
    public String getId() { return id; }
    /** 设置消息 ID；一般由 {@link #fromEntity(Message)} 内部赋值。 */
    public void setId(String id) { this.id = id; }

    /** 读取所属聊天室 ID，供前端消息分组使用。 */
    public String getRoomId() { return roomId; }
    /** 设置所属聊天室 ID；一般由 {@link #fromEntity(Message)} 内部赋值。 */
    public void setRoomId(String roomId) { this.roomId = roomId; }

    /** 读取发言角色 ID；为空表示用户本人发言。 */
    public String getCharacterId() { return characterId; }
    /** 设置发言角色 ID；AI 消息由 {@link #fromEntity(Message)} 赋值，用户消息保持 null。 */
    public void setCharacterId(String characterId) { this.characterId = characterId; }

    /** 读取发言角色名称，供前端直接展示昵称。 */
    public String getCharacterName() { return characterName; }
    /** 设置发言角色名称；冗余字段，避免前端二次查询。 */
    public void setCharacterName(String characterName) { this.characterName = characterName; }

    /** 读取发送方类型，前端据此切换气泡样式。 */
    public String getSenderType() { return senderType; }
    /** 设置发送方类型（枚举名）；由 {@link #fromEntity(Message)} 调用枚举的 {@code name()} 写入。 */
    public void setSenderType(String senderType) { this.senderType = senderType; }

    /** 读取发送用户 ID；仅用户消息时非空。 */
    public String getUserId() { return userId; }
    /** 设置发送用户 ID；用户消息时由 {@link #fromEntity(Message)} 赋值。 */
    public void setUserId(String userId) { this.userId = userId; }

    /** 读取消息正文内容。 */
    public String getContent() { return content; }
    /** 设置消息正文；由 {@link #fromEntity(Message)} 直接拷贝实体字段。 */
    public void setContent(String content) { this.content = content; }

    /** 读取发言角色头像 URL；用户消息时为 null。 */
    public String getAvatarUrl() { return avatarUrl; }
    /** 设置发言角色头像 URL；冗余字段，避免前端二次查询。 */
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    /** 读取消息创建时间，用于排序与前端分组。 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** 设置消息创建时间；由 {@link #fromEntity(Message)} 从实体直接拷贝。 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}