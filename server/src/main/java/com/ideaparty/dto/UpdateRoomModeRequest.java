package com.ideaparty.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public class UpdateRoomModeRequest {

    @Pattern(regexp = "dialogue|discussion", message = "chatMode must be 'dialogue' or 'discussion'")
    private String chatMode;

    @Min(value = 1, message = "maxDiscussionRounds must be at least 1")
    @Max(value = 20, message = "maxDiscussionRounds must be at most 20")
    private Integer maxDiscussionRounds;

    public String getChatMode() { return chatMode; }
    public void setChatMode(String chatMode) { this.chatMode = chatMode; }

    public Integer getMaxDiscussionRounds() { return maxDiscussionRounds; }
    public void setMaxDiscussionRounds(Integer maxDiscussionRounds) { this.maxDiscussionRounds = maxDiscussionRounds; }
}
