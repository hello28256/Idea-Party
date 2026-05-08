package com.ideaparty.dto;

public class SendMessageRequest {

    private String content;
    private String role; // 'user' or 'character'
    private String characterId;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getCharacterId() { return characterId; }
    public void setCharacterId(String characterId) { this.characterId = characterId; }
}
