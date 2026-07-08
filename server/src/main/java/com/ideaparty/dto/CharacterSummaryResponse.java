package com.ideaparty.dto;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.CharacterCategory;
import com.ideaparty.util.ImageUrlResolver;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 角色精简 DTO：用于列表/推荐等"卡片渲染"场景，去掉 system prompt 等大字段。
 *
 * 为什么存在：
 *   完整 CharacterResponse 包含 prompt（TEXT,平均 2-3KB/角色,预设角色 585 个 → 1.5MB）
 *   和 persona/expertise 等列表页用不到的字段。
 *   列表/推荐端点全量返回 1.5MB 即便 gzip 后仍有 627KB,在 CVM 350KB/s 出口带宽下需 1.8s,
 *   用户在发现页"打开就卡"。
 *   用 Summary 砍掉 prompt 后体积降到 200KB,gzip 后 50KB,110ms 即可返回。
 *
 * 与 CharacterResponse 的关系:
 *   - 列表/推荐:用本 Summary,避免传输 prompt
 *   - getById(编辑页):仍用 CharacterResponse(需要 prompt 回显)
 *
 * 字段裁剪:
 *   去掉 prompt(平均 2-3KB)、persona(几百字)、expertise(列表中只展示用 categories 更合适)
 *   保留 id/name/description/avatarUrl/categories/isPreset/ownerId(渲染卡片必需)
 *   保留 createdAt/updatedAt(列表按时间排序)
 */
public class CharacterSummaryResponse {

    private UUID id;
    private String name;
    private String description;
    private String avatarUrl;
    private UUID ownerId;
    private boolean isPreset;
    private Set<CharacterCategory> categories = new HashSet<>();
    private Instant createdAt;
    private Instant updatedAt;
    // 仅 preset 角色填写,前端 clone 时直接传这个 prompt 给 create 接口,
    // 避免后端空 prompt 触发联网+DeepSeek 生成 (浪费 8s + token)。
    // 用户角色不返回,避免 prompt 泄露 (其他用户 clone 别人的角色时也能拿到 prompt)。
    private String prompt;

    public CharacterSummaryResponse() {}

    public static CharacterSummaryResponse fromEntity(Character character) {
        CharacterSummaryResponse response = new CharacterSummaryResponse();
        response.setId(character.getId());
        response.setName(character.getName());
        response.setDescription(character.getDescription());
        response.setAvatarUrl(character.getAvatarUrl());
        response.setPreset(character.isPreset());
        response.setCategories(character.getCategories());
        response.setCreatedAt(character.getCreatedAt());
        response.setUpdatedAt(character.getUpdatedAt());
        if (character.getOwner() != null) {
            response.setOwnerId(character.getOwner().getId());
        }
        // 列表 Summary 一律不带 prompt (2026-07 优化,1.5MB → 50KB)。
        // preset 角色的 prompt 由 clone 流程按需调 GET /characters/{id} 拿完整 CharacterResponse。
        return response;
    }

    /**
     * 从已包含完整字段的 CharacterResponse 转 Summary(丢弃 prompt)。
     * 用于 Controller 列表端点:Service 已返回 CharacterResponse 流(走内存缓存),
     * 没必要为了 Summary 再查一次 entity。
     *
     * <p>2026-07 优化:列表 Summary 一律不带 prompt,无论 preset 还是用户角色。
     * 此前 preset 例外保留 prompt,导致 585 × 2-3KB = 1.5MB JSON,gzip 后仍 630KB,
     * nginx 缓存命中后出 1.5MB 也要 1+ 秒,前端没有 featuredCharacters 就不渲染 <img>,
     * 用户感知"头像过很久才显示"。改为 clone 时前端按需调 GET /characters/{id}
     * 拿完整 CharacterResponse(单角色 ~2KB,nginx 1d 缓存,首次 30ms,后续 0ms)。
     */
    public static CharacterSummaryResponse fromResponse(CharacterResponse source) {
        CharacterSummaryResponse response = new CharacterSummaryResponse();
        response.setId(source.getId());
        response.setName(source.getName());
        response.setDescription(source.getDescription());
        response.setAvatarUrl(source.getAvatarUrl());
        response.setPreset(source.isPreset());
        response.setCategories(source.getCategories());
        response.setCreatedAt(source.getCreatedAt());
        response.setUpdatedAt(source.getUpdatedAt());
        response.setOwnerId(source.getOwnerId());
        return response;
    }

    /**
     * 把图片字段统一转成浏览器可直连的完整 OSS URL。
     * 由调用方(Controller / Service)在序列化前调一次。
     */
    public CharacterSummaryResponse resolveImageUrls(ImageUrlResolver resolver) {
        this.avatarUrl = resolver.resolve(this.avatarUrl);
        return this;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public boolean isPreset() { return isPreset; }
    public void setPreset(boolean preset) { isPreset = preset; }

    public Set<CharacterCategory> getCategories() { return categories; }
    public void setCategories(Set<CharacterCategory> categories) {
        this.categories = categories == null ? new HashSet<>() : categories;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
}
