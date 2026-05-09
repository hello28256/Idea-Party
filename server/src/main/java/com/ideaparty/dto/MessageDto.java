package com.ideaparty.dto;

import com.ideaparty.entity.Message;
import java.time.LocalDateTime;

public class MessageDto {

    private String id;
    private String roomId;
    private String characterId;
    private String characterName;
    private String senderType;
    private String content;
    private String avatarUrl;
    private LocalDateTime createdAt;

    public MessageDto() {}

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

        dto.setContent(message.getContent());
        dto.setCreatedAt(message.getCreatedAt());

        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getCharacterId() { return characterId; }
    public void setCharacterId(String characterId) { this.characterId = characterId; }

    public String getCharacterName() { return characterName; }
    public void setCharacterName(String characterName) { this.characterName = characterName; }

    public String getSenderType() { return senderType; }
    public void setSenderType(String senderType) { this.senderType = senderType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}