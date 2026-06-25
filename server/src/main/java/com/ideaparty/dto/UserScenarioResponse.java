package com.ideaparty.dto;

import com.ideaparty.entity.UserScenario;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户私有场景对外返回的 DTO。
 *
 * 与持久化实体 {@link UserScenario} 解耦，避免直接把 @Entity 序列化给前端
 * （实体含懒加载关联、审计字段等不适合直接暴露的细节），并由 Service 层统一组装。
 *
 * isPreset 恒为 false：预设场景由前端 SEED_SCENARIOS 常量维护，不经过本 DTO。
 * 前端 store 据此把"用户场景"和"预设场景"用同一接口消费，无需类型分支。
 */
public class UserScenarioResponse {

    private UUID id;
    private String emoji;
    private String title;
    private String description;
    private String characterName;
    private String userInputLabel;
    private String userInputPlaceholder;
    private String promptTemplate;
    // 仅暴露 ownerId 而非整个 User 实体：避免泄漏用户敏感字段。
    private UUID ownerId;
    // 恒为 false：预设场景由前端常量维护，不经过本 DTO。
    // 保留该字段便于前端 store 用 Scenario 单一接口消费所有场景。
    private boolean isPreset;
    private Instant createdAt;
    private Instant updatedAt;

    public UserScenarioResponse() {}

    public static UserScenarioResponse fromEntity(UserScenario entity) {
        UserScenarioResponse response = new UserScenarioResponse();
        response.setId(entity.getId());
        response.setEmoji(entity.getEmoji());
        response.setTitle(entity.getTitle());
        response.setDescription(entity.getDescription());
        response.setCharacterName(entity.getCharacterName());
        response.setUserInputLabel(entity.getUserInputLabel());
        response.setUserInputPlaceholder(entity.getUserInputPlaceholder());
        response.setPromptTemplate(entity.getPromptTemplate());
        response.setPreset(false);
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        if (entity.getOwner() != null) {
            response.setOwnerId(entity.getOwner().getId());
        }
        return response;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCharacterName() { return characterName; }
    public void setCharacterName(String characterName) { this.characterName = characterName; }

    public String getUserInputLabel() { return userInputLabel; }
    public void setUserInputLabel(String userInputLabel) { this.userInputLabel = userInputLabel; }

    public String getUserInputPlaceholder() { return userInputPlaceholder; }
    public void setUserInputPlaceholder(String userInputPlaceholder) { this.userInputPlaceholder = userInputPlaceholder; }

    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public boolean isPreset() { return isPreset; }
    public void setPreset(boolean preset) { isPreset = preset; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
