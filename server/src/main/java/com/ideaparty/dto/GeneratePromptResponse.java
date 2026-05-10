package com.ideaparty.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GeneratePromptResponse {

    @JsonProperty("prompt")
    private String prompt;

    public GeneratePromptResponse() {}

    public GeneratePromptResponse(String prompt) {
        this.prompt = prompt;
    }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
}
