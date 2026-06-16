package com.ideaparty.dto;

import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.MessageFeedback;

import java.time.Instant;

public class FeedbackResponse {

    private String id;
    private String messageId;
    private FeedbackType type;
    private FeedbackCategory category;
    private String comment;
    private Instant createdAt;
    private Instant updatedAt;

    public FeedbackResponse() {}

    public static FeedbackResponse fromEntity(MessageFeedback fb) {
        FeedbackResponse r = new FeedbackResponse();
        r.setId(fb.getId().toString());
        r.setMessageId(fb.getMessage().getId());
        r.setType(fb.getType());
        r.setCategory(fb.getCategory());
        r.setComment(fb.getComment());
        r.setCreatedAt(fb.getCreatedAt());
        r.setUpdatedAt(fb.getUpdatedAt());
        return r;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public FeedbackType getType() { return type; }
    public void setType(FeedbackType type) { this.type = type; }
    public FeedbackCategory getCategory() { return category; }
    public void setCategory(FeedbackCategory category) { this.category = category; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
