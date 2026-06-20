package com.ideaparty.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 角色 Prompt 生成接口的出参 DTO。
 * 用于在「创建角色」流程中，把后端基于 Firecrawl 联网检索 + LLM 生成的 system prompt 回传给前端预览/保存。
 * 与 CharacterService.generatePrompt() 配对使用，前端通过该字段直接渲染或落库到 characters.prompt 列。
 */
public class GeneratePromptResponse {

    // 显式声明 JSON 字段名 "prompt"：与前端约定的 snake/camel 一致，避免 Spring 默认序列化策略变更时字段名漂移。
    @JsonProperty("prompt")
    private String prompt;

    /**
     * Jackson 反序列化所需的无参构造。
     * 框架在解析 HTTP 响应体时会调用，前端通常不直接 new 此对象。
     */
    public GeneratePromptResponse() {}

    /**
     * 业务侧常用的全参构造，便于在 Controller/Service 中直接构建返回对象。
     * @param prompt 完整的角色 system prompt 文本，将作为最终 LLM 调用的系统提示
     */
    public GeneratePromptResponse(String prompt) {
        this.prompt = prompt;
    }

    /**
     * 暴露 prompt 给前端：仅用于读取生成结果，不会被业务层修改。
     * @return 角色 system prompt 字符串，可能为 null（生成失败时的占位语义）
     */
    public String getPrompt() { return prompt; }

    /**
     * 预留 setter 以支持 Jackson 反序列化或测试场景手动注入；生产路径上 Controller 不会调用它。
     * @param prompt 角色 system prompt 字符串
     */
    public void setPrompt(String prompt) { this.prompt = prompt; }
}
