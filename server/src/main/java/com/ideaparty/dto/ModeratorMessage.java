package com.ideaparty.dto;

public class ModeratorMessage {
    private String content;
    private String type; // "INVITE", "SUMMARY", "SELECT"

    public ModeratorMessage() {}

    public ModeratorMessage(String content, String type) {
        this.content = content;
        this.type = type;
    }

    // getters and setters
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
