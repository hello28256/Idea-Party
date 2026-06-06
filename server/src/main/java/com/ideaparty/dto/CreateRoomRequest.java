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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public List<UUID> getCharacterIds() { return characterIds; }
    public void setCharacterIds(List<UUID> characterIds) { this.characterIds = characterIds; }
}
