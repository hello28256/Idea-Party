package com.ideaparty.dto;

public class SendMessageRequest {

    private String content;
    private String senderType; // 'USER' or 'CHARACTER'
    private String characterId;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSenderType() { return senderType; }
    public void setSenderType(String senderType) { this.senderType = senderType; }

    public String getCharacterId() { return characterId; }
    public void setCharacterId(String characterId) { this.characterId = characterId; }
}
