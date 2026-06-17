package com.ideaparty.dto;

/**
 * Response from dynamic interview prompt generation.
 * LLM 会输出 "角色名：xxx\n\n{完整 prompt}"，我们解析后拆成两个字段
 */
public class InterviewScenarioResponse {

    /** AI 解析出的角色名，如 "字节跳动 · 高级前端面试官" */
    private String characterName;

    /** AI 生成的完整 system prompt */
    private String prompt;

    public InterviewScenarioResponse() {}

    public InterviewScenarioResponse(String characterName, String prompt) {
        this.characterName = characterName;
        this.prompt = prompt;
    }

    public String getCharacterName() { return characterName; }
    public void setCharacterName(String characterName) { this.characterName = characterName; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
}
