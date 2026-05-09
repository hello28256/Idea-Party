package com.ideaparty.dto;

import com.ideaparty.entity.Room;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

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
    private Instant createdAt;
    private Instant updatedAt;

    public static RoomResponse fromEntity(Room room) {
        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName())
                .topic(room.getTopic())
                .ownerId(room.getOwner().getId())
                .ownerName(room.getOwner().getName())
                .characterCount(room.getCharacterCount())
                .createdAt(room.getCreatedAt())
                .updatedAt(room.getUpdatedAt())
                .build();
    }
}
