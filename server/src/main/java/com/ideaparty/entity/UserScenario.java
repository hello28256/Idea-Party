package com.ideaparty.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 用户私有场景实体。
 *
 * 与 {@link Character} 的"预设/用户共享 + is_preset 区分"模式不同：
 * 预设场景由前端常量 {@code SEED_SCENARIOS} 维护（22 条），不会写入数据库；
 * 本实体只承载用户通过 UI 自建的私有场景。
 * 因此不需要 is_preset 字段——所有行都属于某个 owner。
 *
 * 场景的"角色名（character_name）"和"系统提示词（prompt_template）"是
 * 用户自定义场景的最小可运行单元：用户点击场景卡片后，后端会用这两个字段创建
 * 一个对应的 {@code Character}，再基于该 Character 创建房间。
 */
@Entity
@Table(
    name = "user_scenarios",
    // DB 层终极兜底：同 owner + 同 title 只能有一条私有场景。
    // 前端查重 + Service.findFirstByOwnerIdAndTitle 都被绕过时，
    // DB 唯一索引不会——并发插入同名场景时第二个请求直接报错。
    // 配套的 UserScenarioService.create 会捕获 DataIntegrityViolationException
    // 并回退到 findFirst 返回已存在记录，保持 create 接口幂等。
    uniqueConstraints = @UniqueConstraint(
        name = "uk_user_scenarios_owner_title",
        columnNames = {"owner_id", "title"}
    )
)
public class UserScenario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // 场景图标（如 🤝 / 💰），前端卡片展示用，限长 8 字符以兼容 emoji 多字节序列。
    @Column(nullable = false, length = 8)
    private String emoji;

    // 场景标题（如"客户谈判"），用户可见且唯一（在同 owner 下）。
    @Column(nullable = false, length = 100)
    private String title;

    // 场景一句话描述，渲染在场景卡片副标题。
    @Column(nullable = false, length = 500)
    private String description;

    // 创建 Character 时使用的固定角色名（如"老王·采购总监"），缺省时由前端 fallback。
    @Column(name = "character_name", nullable = false, length = 100)
    private String characterName;

    // 用户输入框标签（如"你要卖什么产品/服务？"），为空时弹窗不显示输入区。
    @Column(name = "user_input_label", length = 100)
    private String userInputLabel;

    // 用户输入框占位符（如"例如：SaaS 客服系统"），仅作 UI 提示。
    @Column(name = "user_input_placeholder", length = 200)
    private String userInputPlaceholder;

    // 场景的核心 system prompt，注入到 Character.prompt 后由 LLM 消费。
    // 使用 TEXT 因为 prompt 经常超 255 字符。
    @Column(name = "prompt_template", columnDefinition = "TEXT", nullable = false)
    private String promptTemplate;

    // LAZY：列表展示场景卡片不需要加载 owner；仅在权限校验时按需加载。
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UserScenario() {}

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

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
