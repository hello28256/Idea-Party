package com.ideaparty.dto;

import com.ideaparty.entity.MessageFeedback;

import java.time.LocalDateTime;

public class AdminFeedbackDetail extends AdminFeedbackListItem {

    private String messageContent;
    private LocalDateTime messageCreatedAt;
    private String roomId;
    private String roomName;
    private String characterId;
    private String characterName;

    public AdminFeedbackDetail() {}

    public static AdminFeedbackDetail fromEntity(MessageFeedback fb) {
        AdminFeedbackDetail dto = new AdminFeedbackDetail();
        // Copy list-item fields
        dto.setId(fb.getId().toString());
        dto.setMessageId(fb.getMessage().getId());
        String content = fb.getMessage().getContent();
        dto.setMessagePreview(content != null && content.length() > 80
                ? content.substring(0, 80) + "..."
                : content);
        dto.setType(fb.getType());
        dto.setCategory(fb.getCategory());
        dto.setComment(fb.getComment());
        dto.setUserId(fb.getUser().getId().toString());
        dto.setUsername(fb.getUser().getUsername());
        dto.setDisplayName(fb.getUser().getDisplayName());
        dto.setCreatedAt(fb.getCreatedAt());

        // Detail-only fields
        dto.setMessageContent(content);
        dto.setMessageCreatedAt(fb.getMessage().getCreatedAt());
        dto.setRoomId(fb.getMessage().getRoom().getId().toString());
        dto.setRoomName(fb.getMessage().getRoom().getName());
        if (fb.getMessage().getCharacter() != null) {
            dto.setCharacterId(fb.getMessage().getCharacter().getId().toString());
            dto.setCharacterName(fb.getMessage().getCharacter().getName());
        }
        return dto;
    }

    public String getMessageContent() { return messageContent; }
    public void setMessageContent(String messageContent) { this.messageContent = messageContent; }
    public LocalDateTime getMessageCreatedAt() { return messageCreatedAt; }
    public void setMessageCreatedAt(LocalDateTime messageCreatedAt) { this.messageCreatedAt = messageCreatedAt; }
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = roomName; }
    public String getCharacterId() { return characterId; }
    public void setCharacterId(String characterId) { this.characterId = characterId; }
    public String getCharacterName() { return characterName; }
    public void setCharacterName(String characterName) { this.characterName = characterName; }
}
