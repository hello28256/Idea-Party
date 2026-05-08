package com.ideaparty.dto;

import com.ideaparty.entity.Room;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class RoomResponse {

    private String id;
    private String name;
    private String theme;
    private LocalDateTime createdAt;
    private List<CharacterResponse> characters;

    public RoomResponse() {}

    public static RoomResponse fromEntity(Room room) {
        RoomResponse response = new RoomResponse();
        response.setId(room.getId());
        response.setName(room.getName());
        response.setTheme(room.getTheme());
        response.setCreatedAt(room.getCreatedAt());
        response.setCharacters(room.getCharacters().stream()
            .map(CharacterResponse::fromEntity)
            .collect(Collectors.toList()));
        return response;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public List<CharacterResponse> getCharacters() { return characters; }
    public void setCharacters(List<CharacterResponse> characters) { this.characters = characters; }
}
