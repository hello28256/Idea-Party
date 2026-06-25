package com.ideaparty.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户私有场景创建/更新请求的入参 DTO。
 *
 * 由 Controller 在接收前端"创建/编辑场景"表单时反序列化使用，
 * 与 {@link com.ideaparty.entity.UserScenario} 实体一一映射，但仅承载用户可控的输入字段，
 * 避免把 id / ownerId / createdAt / updatedAt 等服务端字段暴露给客户端。
 *
 * 字段上的 Bean Validation 注解会在 Controller 入口触发校验，
 * 校验失败时由全局异常处理器转为 400 响应。
 *
 * 注意：mode / dynamicPrompt / requiresUserInput / suggestedCharacterIds 等
 * "硬编码行为字段" 不暴露给用户——前端为简化 UI 仅让用户填核心 7 个字段，
 * 其他字段（mode=single, dynamicPrompt=false 等）由前端 store 在
 * 最终创建 Character / Room 时按需补全。
 */
public class UserScenarioRequest {

    /**
     * 场景图标（如 🤝 / 💰），前端卡片展示用。
     * 限长 8 字符以兼容 emoji 多字节序列（最坏情况为 7 字节 + 留余量）。
     */
    @NotBlank(message = "Emoji is required")
    @Size(max = 8, message = "Emoji must be at most 8 characters")
    private String emoji;

    /**
     * 场景标题（如"客户谈判"），用户可见且唯一（在同 owner 下）。
     * 必填，限 2-100 字符。
     */
    @NotBlank(message = "Title is required")
    @Size(min = 2, max = 100, message = "Title must be between 2 and 100 characters")
    private String title;

    /**
     * 场景一句话描述，渲染在场景卡片副标题。
     * 必填，限 5-500 字符。
     */
    @NotBlank(message = "Description is required")
    @Size(min = 5, max = 500, message = "Description must be between 5 and 500 characters")
    private String description;

    /**
     * 创建 Character 时使用的固定角色名（如"老王·采购总监"）。
     * 必填，限 2-100 字符。
     */
    @NotBlank(message = "Character name is required")
    @Size(min = 2, max = 100, message = "Character name must be between 2 and 100 characters")
    private String characterName;

    /**
     * 用户输入框标签（如"你要卖什么产品/服务？"），可选。
     * 为空时弹窗不显示输入区（requiresUserInput=false）。
     */
    @Size(max = 100, message = "User input label must be at most 100 characters")
    private String userInputLabel;

    /**
     * 用户输入框占位符（如"例如：SaaS 客服系统"），可选。
     */
    @Size(max = 200, message = "User input placeholder must be at most 200 characters")
    private String userInputPlaceholder;

    /**
     * 场景的核心 system prompt，注入到 Character.prompt 后由 LLM 消费。
     * 必填，限 20-4000 字符（短于 20 字符无法形成有效指令；长于 4000 字符可能撑爆 LLM 上下文）。
     */
    @NotBlank(message = "Prompt template is required")
    @Size(min = 20, max = 4000, message = "Prompt template must be between 20 and 4000 characters")
    private String promptTemplate;

    public UserScenarioRequest() {}

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
}
