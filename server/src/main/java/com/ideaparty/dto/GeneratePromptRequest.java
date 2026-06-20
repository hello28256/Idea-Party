package com.ideaparty.dto;

/**
 * 生成角色 Prompt 的入参 DTO。
 *
 * <p>封装前端调用「AI 生成角色 Prompt」接口时提交的最小上下文：角色名称、可选描述、
 * 以及是否启用联网检索/AI 增强开关。Controller 层接收该 DTO 后，
 * 会交给 PromptGenerationService 完成 Firecrawl 检索与 LLM Prompt 组装。
 */
public class GeneratePromptRequest {

    /**
     * 角色名称（如「苏格拉底」「马斯克」）。
     *
     * <p>作为联网检索与 Prompt 生成的唯一锚点：服务层会基于该名字抓取公开资料，
     * 因此必须是真实存在的公众人物或具名实体，否则检索结果将无意义。
     */
    private String name;

    /**
     * 用户对该角色的补充描述（背景、立场、关注领域等）。
     *
     * <p>可选字段。提供后可让生成的 Prompt 更贴合用户预期；为空时服务层
     * 完全依赖联网检索结果进行概括，避免用户输入与检索结论冲突。
     */
    private String description;

    /**
     * 是否启用 AI 增强（联网检索 + LLM 组装 Prompt）。
     *
     * <p>默认 true 以走完整生成链路；当 Firecrawl/DeepSeek 不可用或用户希望
     * 仅用 description 模板化生成时，可置为 false 走降级路径。
     */
    private Boolean useAi = true;

    /**
     * 读取角色名称，供 PromptGenerationService 作为检索锚点使用。
     */
    public String getName() { return name; }

    /**
     * 写入角色名称，由 Controller 反序列化前端 JSON 时调用。
     */
    public void setName(String name) { this.name = name; }

    /**
     * 读取用户补充描述，用于在 Prompt 中拼接额外上下文。
     */
    public String getDescription() { return description; }

    /**
     * 写入用户补充描述，可为空以表示完全依赖联网检索。
     */
    public void setDescription(String description) { this.description = description; }

    /**
     * 读取是否启用 AI 增强开关，决定走完整生成链路还是降级模板。
     */
    public Boolean getUseAi() { return useAi; }

    /**
     * 写入 AI 增强开关，默认 true 由字段初始化保证，前端可不传。
     */
    public void setUseAi(Boolean useAi) { this.useAi = useAi; }
}
