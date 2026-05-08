package com.ideaparty.dto;

import com.ideaparty.entity.Message;
import java.time.LocalDateTime;

public class MessageResponse {

    private String id;
    private String content;
    private String role;
    private String characterId;
    private String characterName;
    private String characterAvatar;
    private String roomId;
    private LocalDateTime createdAt;

    public MessageResponse() {}

    public static MessageResponse fromEntity(Message message) {
        MessageResponse response = new MessageResponse();
        response.setId(message.getId());
        response.setContent(message.getContent());
        response.setRole(message.getRole());
        response.setRoomId(message.getRoom().getId());
        response.setCreatedAt(message.getCreatedAt());

        if (message.getCharacter() != null) {
            response.setCharacterId(message.getCharacter().getId());
            response.setCharacterName(message.getCharacter().getName());
            response.setCharacterAvatar(message.getCharacter().getAvatar());
        }

        return response;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

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
