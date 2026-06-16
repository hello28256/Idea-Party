package com.ideaparty.service;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.Room;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class MessageService {

    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;
    private final MessageObservationService observationService;

    public MessageService(MessageRepository messageRepository, RoomRepository roomRepository,
                         CharacterRepository characterRepository, UserRepository userRepository,
                         MessageObservationService observationService) {
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
        this.observationService = observationService;
    }

    public Message saveMessage(UUID roomId, UUID characterId, Message.SenderType senderType, String content, UUID userId) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new RuntimeException("Room not found: " + roomId));

        Message message = new Message();
        message.setContent(content);
        message.setSenderType(senderType);
        message.setRoom(room);

        if (characterId != null) {
            Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new RuntimeException("Character not found: " + characterId));
            message.setCharacter(character);
        }

        if (userId != null && senderType == Message.SenderType.USER) {
            User user = userRepository.findById(userId).orElse(null);
            message.setUser(user);
        }

        Message saved = messageRepository.save(message);
        if (senderType == Message.SenderType.CHARACTER) {
            try {
                observationService.onAiMessagePersisted(saved);
            } catch (Exception e) {
                // Observation is best-effort; never fail the message write because of it.
                org.slf4j.LoggerFactory.getLogger(MessageService.class)
                    .warn("[MessageService] observation seed failed: {}", e.getMessage());
            }
        }
        return saved;
    }

    public List<Message> getMessagesByRoomId(UUID roomId) {
        return messageRepository.findByRoomIdWithCharacter(roomId);
    }

    public Page<Message> getMessagesPaginated(UUID roomId, int page, int size) {
        return messageRepository.findByRoomIdOrderByCreatedAtDesc(
            roomId,
            PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"))
        );
    }

    public Optional<Message> getMessageById(UUID id) {
        return messageRepository.findById(id);
    }
}
