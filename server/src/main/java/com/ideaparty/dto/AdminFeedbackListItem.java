package com.ideaparty.dto;

import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.MessageFeedback;

import java.time.Instant;

public class AdminFeedbackListItem {

    private static final int MESSAGE_PREVIEW_LEN = 80;

    private String id;
    private String messageId;
    private String messagePreview;
    private FeedbackType type;
    private FeedbackCategory category;
    private String comment;
    private String userId;
    private String username;
    private String displayName;
    private Instant createdAt;

    public AdminFeedbackListItem() {}

    public static AdminFeedbackListItem fromEntity(MessageFeedback fb) {
        AdminFeedbackListItem dto = new AdminFeedbackListItem();
        dto.setId(fb.getId().toString());
        dto.setMessageId(fb.getMessage().getId());
        String content = fb.getMessage().getContent();
        dto.setMessagePreview(content != null && content.length() > MESSAGE_PREVIEW_LEN
                ? content.substring(0, MESSAGE_PREVIEW_LEN) + "..."
                : content);
        dto.setType(fb.getType());
        dto.setCategory(fb.getCategory());
        dto.setComment(fb.getComment());
        dto.setUserId(fb.getUser().getId().toString());
        dto.setUsername(fb.getUser().getUsername());
        dto.setDisplayName(fb.getUser().getDisplayName());
        dto.setCreatedAt(fb.getCreatedAt());
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getMessagePreview() { return messagePreview; }
    public void setMessagePreview(String messagePreview) { this.messagePreview = messagePreview; }
    public FeedbackType getType() { return type; }
    public void setType(FeedbackType type) { this.type = type; }
    public FeedbackCategory getCategory() { return category; }
    public void setCategory(FeedbackCategory category) { this.category = category; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
