package com.ideaparty.dto;

public class GeneratePromptRequest {

    private String name;
    private String description;
    private Boolean useAi = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getUseAi() { return useAi; }
    public void setUseAi(Boolean useAi) { this.useAi = useAi; }
}
