package com.ideaparty.dto;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.CharacterCategory;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// 角色对外返回的 DTO：与持久化实体 Character 解耦，避免直接把 @Entity 序列化给前端
// （实体含懒加载关联、审计字段、内部标记等不适合暴露的细节），并由 Controller 层统一组装。
// 与 CharacterService / CharacterController 配合，作为角色查询与聊天室成员展示的契约载体。
public class CharacterResponse {

    // 角色唯一标识：前端用于路由参数、聊天室成员引用及缓存 key，避免暴露自增 ID。
    private UUID id;
    // 角色显示名：群聊中作为发言者标签展示，需保证在用户可见场景下的人类可读性。
    private String name;
    // 角色简介：用于角色卡片与"加入聊天室"前的预览，约束在数十字以内以匹配列表布局。
    private String description;
    // 头像 URL：可为 null（前端回退到默认头像），存储在对象存储或第三方 CDN，避免与 DTO 耦合。
    private String avatarUrl;
    // 系统提示词：发送给 LLM 的核心指令，前端展示给用户以便编辑/调试，但不直接暴露给其他用户。
    private String prompt;
    // 仅暴露 ownerId 而非整个 User 实体：前端展示创建者身份足够，且避免泄漏用户敏感字段。
    private UUID ownerId;
    // 区分「平台预设角色」与「用户自建角色」，前端据此控制编辑/删除权限与展示样式。
    private boolean isPreset;
    // 推荐位分类（多分类集合）：发现页"分类标签条"按此过滤；用户自建角色为空。
    // 保留数组形态便于前端按需展示多个标签。
    private Set<CharacterCategory> categories = new HashSet<>();
    private Instant createdAt;
    private Instant updatedAt;

    // Jackson 反序列化与 fromEntity 反射赋值都需要无参构造；显式声明以保留后续字段初始化的扩展点。
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
        response.setCategories(character.getCategories());
        response.setCreatedAt(character.getCreatedAt());
        response.setUpdatedAt(character.getUpdatedAt());
        if (character.getOwner() != null) {
            response.setOwnerId(character.getOwner().getId());
        }
        return response;
    }

    public UUID getId() { return id; }
    // 测试与序列化场景使用；生产路径通常由 fromEntity 注入，避免业务层手工设置。
    public void setId(UUID id) { this.id = id; }

    // 群聊消息列表读取，用于渲染发言者头部。
    public String getName() { return name; }
    // 仅在角色编辑/克隆流程由 Service 写入；前端直传需走校验层。
    public void setName(String name) { this.name = name; }

    // 角色选择器读取，列表页与详情预览都会消费。
    public String getDescription() { return description; }
    // 与 getName 配合，由 CharacterService 在更新角色时同步写入。
    public void setDescription(String description) { this.description = description; }

    // 前端 <img> 标签直接消费；可能为 null，调用方需做兜底。
    public String getAvatarUrl() { return avatarUrl; }
    // 角色创建/编辑接口写入，校验层需确认 URL 协议防止 SSRF/XSS。
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    // 调试面板读取，便于在不调用 LLM 的情况下预览 prompt 内容。
    public String getPrompt() { return prompt; }
    // 由角色编辑接口写入；调用方需做长度上限校验避免 prompt 注入。
    public void setPrompt(String prompt) { this.prompt = prompt; }

    // 用于"我的角色"与"预设角色"列表筛选，由前端判断当前用户是否可编辑。
    public UUID getOwnerId() { return ownerId; }
    // fromEntity 在 owner 懒加载非空时调用，避免暴露完整 User 实体。
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    // 列表页据此隐藏删除按钮、显示"预设"标记；权限判定核心字段。
    public boolean isPreset() { return isPreset; }
    // 仅管理员/种子数据脚本调用，正常用户请求不应触发该 setter。
    public void setPreset(boolean preset) { isPreset = preset; }

    // 前端发现页"分类标签条"过滤；用户自建角色为 null。
    public Set<CharacterCategory> getCategories() { return categories; }
    public void setCategories(Set<CharacterCategory> categories) {
        this.categories = categories == null ? new HashSet<>() : categories;
    }

    // 列表"创建时间"列展示，按从新到旧排序时直接消费。
    public Instant getCreatedAt() { return createdAt; }
    // 由 fromEntity 同步实体 JPA 审计字段，禁止业务层手工篡改。
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    // 用于客户端判断角色是否被修改、是否需要刷新本地缓存。
    public Instant getUpdatedAt() { return updatedAt; }
    // 同 getCreatedAt：由 fromEntity 透传实体值，避免与数据库审计逻辑分裂。
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
