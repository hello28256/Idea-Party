package com.ideaparty.dto;

import com.ideaparty.entity.Character;

import java.time.Instant;
import java.util.UUID;

public class CharacterResponse {

    private UUID id;
    private String name;
    private String description;
    private String avatarUrl;
    private String prompt;
    private UUID ownerId;
    private boolean isPreset;
    private Instant createdAt;
    private Instant updatedAt;

    public CharacterResponse() {}

    public static CharacterResponse fromEntity(Character character) {
        CharacterResponse response = new CharacterResponse();
        response.setId(character.getId());
        response.setName(character.getName());
        response.setDescription(character.getDescription());
        response.setAvatarUrl(character.getAvatarUrl());
        response.setPrompt(character.getPrompt());
        response.setPreset(character.isPreset());
        response.setCreatedAt(character.getCreatedAt());
        response.setUpdatedAt(character.getUpdatedAt());
        if (character.getOwner() != null) {
            response.setOwnerId(character.getOwner().getId());
        }
        return response;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public boolean isPreset() { return isPreset; }
    public void setPreset(boolean preset) { isPreset = preset; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
