package com.ideaparty.service;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.Room;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class MessageService {

    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final CharacterRepository characterRepository;

    public MessageService(MessageRepository messageRepository, RoomRepository roomRepository, CharacterRepository characterRepository) {
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.characterRepository = characterRepository;
    }

    public Message saveMessage(String roomId, String content, String role, String characterId) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new RuntimeException("Room not found: " + roomId));

        Message message = new Message();
        message.setContent(content);
        message.setRole(role);
        message.setRoom(room);

        if (characterId != null) {
            Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new RuntimeException("Character not found: " + characterId));
            message.setCharacter(character);
        }

        return messageRepository.save(message);
    }

    public List<Message> getMessagesByRoomId(String roomId) {
        return messageRepository.findByRoomIdWithCharacter(roomId);
    }

    public Page<Message> getMessagesPaginated(String roomId, int page, int size) {
        return messageRepository.findByRoomIdOrderByCreatedAtDesc(
            roomId,
            PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"))
        );
    }

    public Optional<Message> getMessageById(String id) {
        return messageRepository.findById(id);
    }
}
