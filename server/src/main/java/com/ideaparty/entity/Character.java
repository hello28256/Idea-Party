package com.ideaparty.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 聊天室中的 AI 角色实体。
 * 作为多角色圆桌聊天的最小可调用单元，承载角色 prompt / 人设 / 风格等提示工程素材，
 * 由 {@code ChatRoom} 通过多对多关联组合使用，由 {@code AIService} 在 Moderator 编排下注入到 LLM 调用。
 * 用户私有角色与平台预设角色共用此实体，通过 {@link #isPreset} 区分可见性与所有权。
 */
@Entity
@Table(name = "characters")
public class Character {

    // 主键使用 UUID 而非自增：分布式/前端可预生成 ID，避免暴露业务量；也便于未来多节点生成时不冲突。
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    // 用户在角色列表看到的简介，长度不受 255 限制，故使用 TEXT。
    @Column(columnDefinition = "TEXT")
    private String description;

    // 头像外链 URL，限长 500 防止异常长字符串写入数据库。
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    // 角色在 LLM 调用中使用的核心系统提示词，由联网检索结果合成，可超长故用 TEXT。
    @Column(columnDefinition = "TEXT")
    private String prompt;

    // EAGER 加载：角色资料展示页基本都会展示专长标签，避免每个页面触发额外查询。
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "character_expertise", joinColumns = @JoinColumn(name = "character_id"))
    @Column(name = "expertise")
    private List<String> expertise;

    // 角色所属时代（如"三国"/"现代"），仅作为人设元数据，便于 prompt 拼接，不参与逻辑分支。
    @Column(length = 50)
    private String era;

    @Column(name = "speaking_style", length = 500)
    private String speakingStyle;

    // 细粒度人设补充（如口癖、立场），与 prompt 区分用于不同注入阶段。
    @Column(columnDefinition = "TEXT")
    private String persona;

    // LAZY：列表/会话场景下不必加载 owner，避免 N+1；仅在权限校验等需要 owner 的场景触发加载。
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    // 平台预设角色对所有用户可见且不可被普通用户改/删；私有角色 owner_id 必填，由业务层校验。
    @Column(name = "is_preset", nullable = false)
    private boolean isPreset = false;

    // 创建时间由 JPA 在首次持久化时写入，业务层不应手动 set，避免被覆盖。
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 更新时间由 JPA 在每次更新时自动刷新，业务层无须手动维护。
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // 由 JPA 在 INSERT 前回调；同时初始化 createdAt 和 updatedAt，保证两者一致，避免依赖数据库默认值。
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    // 由 JPA 在 UPDATE 前回调；只刷新 updatedAt，保持 createdAt 不变。
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Character() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    // Alias for DataLoader compatibility
    public void setAvatar(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getAvatar() { return avatarUrl; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }

    public List<String> getExpertise() { return expertise; }
    public void setExpertise(List<String> expertise) { this.expertise = expertise; }

    public String getEra() { return era; }
    public void setEra(String era) { this.era = era; }

    public String getSpeakingStyle() { return speakingStyle; }
    public void setSpeakingStyle(String speakingStyle) { this.speakingStyle = speakingStyle; }

    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }

    public boolean isPreset() { return isPreset; }
    public void setPreset(boolean preset) { isPreset = preset; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
