package com.ideaparty.dto;

import com.ideaparty.entity.Character;

import java.time.Instant;
import java.util.UUID;

// 角色对外返回的 DTO：与持久化实体 Character 解耦，避免直接把 @Entity 序列化给前端
// （实体含懒加载关联、审计字段、内部标记等不适合暴露的细节），并由 Controller 层统一组装。
// 与 CharacterService / CharacterController 配合，作为角色查询与聊天室成员展示的契约载体。
public class CharacterResponse {

    private UUID id;
    private String name;
    private String description;
    private String avatarUrl;
    private String prompt;
    // 仅暴露 ownerId 而非整个 User 实体：前端展示创建者身份足够，且避免泄漏用户敏感字段。
    private UUID ownerId;
    // 区分「平台预设角色」与「用户自建角色」，前端据此控制编辑/删除权限与展示样式。
    private boolean isPreset;
    private Instant createdAt;
    private Instant updatedAt;

    public CharacterResponse() {}

    // Entity → DTO 的唯一入口：集中控制哪些字段对外可见，
    // 处理 owner 懒加载可能为 null 的边界（预设角色无 owner），避免上层重复判空。
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
