package com.ideaparty.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI 角色创建/更新请求的入参 DTO。
 *
 * 由 Controller 在接收前端"新建/编辑角色"表单时反序列化使用，
 * 与 {@link com.ideaparty.entity.Character} 实体一一映射，但仅承载用户可控的输入字段，
 * 避免把 id、createdAt、ownerId 等服务端字段暴露给客户端。
 *
 * 字段上的 Bean Validation 注解会在 Controller 入口触发校验，
 * 校验失败时由全局异常处理器转为 400 响应。
 */
public class CharacterRequest {

    /**
     * 角色名称，作为聊天室里 @ 提及和发言展示的主要标识。
     * 必填且长度受限，便于在前端列表、提示词中稳定引用。
     */
    @NotBlank(message = "Character name is required")
    @Size(min = 1, max = 100, message = "Name must be between 1 and 100 characters")
    private String name;

    /**
     * 角色的公开简介，用于角色卡片和检索结果展示，可选。
     * 限制 2000 字防止恶意长文本撑爆聊天上下文窗口。
     */
    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    /**
     * 角色头像的远程 URL，由前端上传后回填，可选。
     * URL 长度上限对齐常见对象存储签名的最大长度。
     */
    @Size(max = 500, message = "Avatar URL must be at most 500 characters")
    private String avatarUrl;

    /**
     * 注入给 LLM 的系统提示词，定义角色人设、语气、知识范围。
     * 可选——后端在缺失时会基于 name 联网检索生成默认 prompt。
     */
    @Size(max = 5000, message = "Prompt must be at most 5000 characters")
    private String prompt;

    /**
     * 无参构造器：Jackson 反序列化与框架实例化所必需。
     */
    public CharacterRequest() {}

    /**
     * 读取角色名称，供 Service 拼装实体和持久化调用。
     */
    public String getName() { return name; }
    /**
     * 写入角色名称，由 Jackson 在反序列化时填充。
     */
    public void setName(String name) { this.name = name; }

    /**
     * 读取角色简介，Service 在落库前会做 HTML/敏感词二次过滤。
     */
    public String getDescription() { return description; }
    /**
     * 写入角色简介，反序列化入口。
     */
    public void setDescription(String description) { this.description = description; }

    /**
     * 读取头像 URL，渲染聊天消息头像时使用。
     */
    public String getAvatarUrl() { return avatarUrl; }
    /**
     * 写入头像 URL，反序列化入口。
     */
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    /**
     * 读取 prompt，AI 服务在每次发言时都会把它作为 system message。
     */
    public String getPrompt() { return prompt; }
    /**
     * 写入 prompt，反序列化入口；后端可能覆盖为空时使用联网检索结果填充。
     */
    public void setPrompt(String prompt) { this.prompt = prompt; }
}
