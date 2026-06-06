package com.ideaparty.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public class CreateRoomRequest {

    @jakarta.validation.constraints.NotBlank(message = "Room name is required")
    @Size(min = 1, max = 100, message = "Room name must be between 1 and 100 characters")
    private String name;

    @Size(max = 500, message = "Topic must be at most 500 characters")
    private String topic;

    @Size(max = 50, message = "At most 50 characters per room")
    private List<UUID> characterIds;

    /**
     * Room conversation shape: "single" (1-on-1 with one character) or
     * "group" (multi-character discussion). Optional — backend defaults to "group".
     */
    private String mode;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public List<UUID> getCharacterIds() { return characterIds; }
    public void setCharacterIds(List<UUID> characterIds) { this.characterIds = characterIds; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
