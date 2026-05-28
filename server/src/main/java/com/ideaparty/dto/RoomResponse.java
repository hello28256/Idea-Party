package com.ideaparty.dto;

import com.ideaparty.entity.Room;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomResponse {

    private UUID id;
    private String name;
    private String topic;
    private UUID ownerId;
    private String ownerName;
    private int characterCount;
    private List<CharacterResponse> characters;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastEnterTime;
    private String chatMode;
    private Integer maxDiscussionRounds;

    public static RoomResponse fromEntity(Room room) {
        List<CharacterResponse> characterList = null;
        if (room.getCharacters() != null && !room.getCharacters().isEmpty()) {
            characterList = room.getCharacters().stream()
                    .map(CharacterResponse::fromEntity)
                    .collect(Collectors.toList());
        }
        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName())
                .topic(room.getTopic())
                .ownerId(room.getOwner().getId())
                .ownerName(room.getOwner().getDisplayName())
                .characterCount(room.getCharacterCount())
                .characters(characterList)
                .createdAt(room.getCreatedAt())
                .updatedAt(room.getUpdatedAt())
                .lastEnterTime(room.getLastEnterTime())
                .chatMode(room.getChatMode())
                .maxDiscussionRounds(room.getMaxDiscussionRounds())
                .build();
    }
}
