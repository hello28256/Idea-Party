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

    // JPA 要求的无参构造器；由 Hibernate 在反序列化时反射调用，业务层不应直接 new 时忽略此构造。
    public Character() {}

    /** @return 角色全局唯一 UUID 主键，由 JPA 在首次持久化时生成。 */
    public UUID getId() { return id; }
    /** 通常由 JPA 自动注入；仅在测试或数据迁移场景需要手动预设 ID 时调用。 */
    public void setId(UUID id) { this.id = id; }

    /** @return 角色展示名，聊天室与角色列表均按此字段向用户呈现。 */
    public String getName() { return name; }
    /** @param name 角色展示名；非空约束由数据库列定义保证，业务层需在调用前校验。 */
    public void setName(String name) { this.name = name; }

    /** @return 角色简介，长文本，渲染于角色卡详情页。 */
    public String getDescription() { return description; }
    /** @param description 角色简介，可包含换行与 HTML 转义后的片段。 */
    public void setDescription(String description) { this.description = description; }

    /** @return 头像外链 URL，供前端 &lt;img src&gt; 直接使用。 */
    public String getAvatarUrl() { return avatarUrl; }
    /** @param avatarUrl 头像外链 URL；写入前业务层应做 URL 合法性校验。 */
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    // Alias for DataLoader compatibility
    /** DataLoader 约定的别名 setter，与 {@link #setAvatarUrl} 等价，仅为兼容旧调用链。 */
    public void setAvatar(String avatarUrl) { this.avatarUrl = avatarUrl; }
    /** DataLoader 约定的别名 getter，与 {@link #getAvatarUrl} 等价。 */
    public String getAvatar() { return avatarUrl; }

    /** @return 角色核心系统提示词，注入 LLM 的 system message。 */
    public String getPrompt() { return prompt; }
    /** @param prompt 角色系统提示词；由 {@code AIService} 检索联网信息后合成。 */
    public void setPrompt(String prompt) { this.prompt = prompt; }

    /** @return 角色所有者（创建者）；平台预设角色的 owner 为 null。 */
    public User getOwner() { return owner; }
    /** @param owner 角色所有者；私有角色必填，预设角色须保持 null 以便业务层识别。 */
    public void setOwner(User owner) { this.owner = owner; }

    /** @return 角色专长标签列表，用于角色卡展示与 Moderator 编排匹配。 */
    public List<String> getExpertise() { return expertise; }
    /** @param expertise 角色专长标签列表；底层使用 ElementCollection 持久化到子表。 */
    public void setExpertise(List<String> expertise) { this.expertise = expertise; }

    /** @return 角色所属时代（如"三国"/"现代"），仅作人设元数据。 */
    public String getEra() { return era; }
    /** @param era 角色所属时代描述，自由文本，最长 50 字符。 */
    public void setEra(String era) { this.era = era; }

    /** @return 角色口吻/说话风格描述，用于丰富 prompt 的人设部分。 */
    public String getSpeakingStyle() { return speakingStyle; }
    /** @param speakingStyle 角色说话风格文本，最长 500 字符。 */
    public void setSpeakingStyle(String speakingStyle) { this.speakingStyle = speakingStyle; }

    /** @return 角色细粒度人设补充（如口癖、立场），区别于 {@link #getPrompt} 的核心提示。 */
    public String getPersona() { return persona; }
    /** @param persona 角色细粒度人设文本；与 prompt 分阶段注入到 LLM。 */
    public void setPersona(String persona) { this.persona = persona; }

    /** @return true 表示平台预设角色（全员可见、不可改），false 表示用户私有角色。 */
    public boolean isPreset() { return isPreset; }
    /** @param preset 是否平台预设；仅系统初始化或管理后台可设为 true。 */
    public void setPreset(boolean preset) { isPreset = preset; }

    /** @return 角色首次持久化的时间戳，由 {@link #onCreate()} 自动写入。 */
    public Instant getCreatedAt() { return createdAt; }
    /** 仅供 JPA / 测试场景使用，正常流程由 {@link #onCreate()} 维护。 */
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /** @return 角色最近一次更新的时间戳，由 {@link #onUpdate()} 自动刷新。 */
    public Instant getUpdatedAt() { return updatedAt; }
    /** 仅供 JPA / 测试场景使用，正常流程由 {@link #onUpdate()} 维护。 */
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
