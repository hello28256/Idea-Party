package com.ideaparty.dto;

import com.ideaparty.entity.Character;
import java.util.List;

public class CharacterResponse {

    private String id;
    private String name;
    private String avatar;
    private String description;
    private List<String> expertise;
    private String era;
    private String speakingStyle;

    public CharacterResponse() {}

    public static CharacterResponse fromEntity(Character character) {
        CharacterResponse response = new CharacterResponse();
        response.setId(character.getId());
        response.setName(character.getName());
        response.setAvatar(character.getAvatar());
        response.setDescription(character.getDescription());
        response.setExpertise(character.getExpertise());
        response.setEra(character.getEra());
        response.setSpeakingStyle(character.getSpeakingStyle());
        return response;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getExpertise() { return expertise; }
    public void setExpertise(List<String> expertise) { this.expertise = expertise; }

    public String getEra() { return era; }
    public void setEra(String era) { this.era = era; }

    public String getSpeakingStyle() { return speakingStyle; }
    public void setSpeakingStyle(String speakingStyle) { this.speakingStyle = speakingStyle; }
}
