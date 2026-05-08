package com.ideaparty.service;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Room;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class RoomService {

    private final RoomRepository roomRepository;
    private final CharacterRepository characterRepository;

    public RoomService(RoomRepository roomRepository, CharacterRepository characterRepository) {
        this.roomRepository = roomRepository;
        this.characterRepository = characterRepository;
    }

    public Room createRoom(String name, String theme, List<String> characterIds) {
        Room room = new Room();
        room.setName(name);
        room.setTheme(theme);

        if (characterIds != null) {
            for (String characterId : characterIds) {
                characterRepository.findById(characterId).ifPresent(character -> {
                    room.addCharacter(character);
                });
            }
        }

        return roomRepository.save(room);
    }

    public Optional<Room> getRoomById(String id) {
        return roomRepository.findWithCharactersById(id);
    }

    public List<Room> getAllRooms() {
        return roomRepository.findAll();
    }

    public List<Room> getRoomsByTheme(String theme) {
        return roomRepository.findByThemeOrderByCreatedAtDesc(theme);
    }

    public Room addCharacterToRoom(String roomId, String characterId) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new RuntimeException("Room not found: " + roomId));
        Character character = characterRepository.findById(characterId)
            .orElseThrow(() -> new RuntimeException("Character not found: " + characterId));

        room.addCharacter(character);
        return roomRepository.save(room);
    }
}
