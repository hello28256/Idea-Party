package com.ideaparty.dto;

import com.ideaparty.entity.Message;
import java.time.LocalDateTime;

// 聊天消息的对外响应载体：把 Message 实体（含 Room/User/Character 关联）扁平化成前端可直接消费的字段，
// 避免 JPA 懒加载与实体内部结构直接暴露到 API 层；配合 Controller 返回给前端群聊视图渲染。
public class MessageResponse {

    private String id;
    private String content;
    // 枚举转字符串（USER/CHARACTER），前端按字符串分支渲染发言者气泡，保持 API 与枚举解耦。
    private String senderType;
    // 仅在 senderType=USER 时填充；CHARACTER 消息不关联 user，避免前端误判归属。
    private String userId;
    // 仅在 senderType=CHARACTER 时填充；USER 消息不发 AI 角色字段，前端按 null 隐藏头像。
    private String characterId;
    private String characterName;
    private String characterAvatar;
    private String roomId;
    private LocalDateTime createdAt;

    public MessageResponse() {}

    // 实体到 DTO 的映射入口：处理 senderType 枚举→字符串、关联 ID Long→String，并在关联为 null 时跳过填充，
    // 调用方需保证 message 与其 room 关联在事务内可访问，避免懒加载在 DTO 阶段抛 LazyInitializationException。
    public static MessageResponse fromEntity(Message message) {
        MessageResponse response = new MessageResponse();
        response.setId(message.getId());
        response.setContent(message.getContent());
        response.setSenderType(message.getSenderType().name());
        response.setRoomId(message.getRoom().getId().toString());
        response.setCreatedAt(message.getCreatedAt());

        if (message.getCharacter() != null) {
            response.setCharacterId(message.getCharacter().getId().toString());
            response.setCharacterName(message.getCharacter().getName());
            response.setCharacterAvatar(message.getCharacter().getAvatarUrl());
        }

        if (message.getUser() != null) {
            response.setUserId(message.getUser().getId().toString());
        }

        return response;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSenderType() { return senderType; }
    public void setSenderType(String senderType) { this.senderType = senderType; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getCharacterId() { return characterId; }
    public void setCharacterId(String characterId) { this.characterId = characterId; }

    public String getCharacterName() { return characterName; }
    public void setCharacterName(String characterName) { this.characterName = characterName; }

    public String getCharacterAvatar() { return characterAvatar; }
    public void setCharacterAvatar(String characterAvatar) { this.characterAvatar = characterAvatar; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
