package com.ideaparty.dto;

import java.util.List;

public class CreateRoomRequest {

    private String name;
    private String theme;
    private List<String> characterIds;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public List<String> getCharacterIds() { return characterIds; }
    public void setCharacterIds(List<String> characterIds) { this.characterIds = characterIds; }
}
