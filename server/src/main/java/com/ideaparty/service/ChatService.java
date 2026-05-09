package com.ideaparty.service;

import com.ideaparty.dto.MessageDto;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.Room;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Chat orchestration service.
 * Handles message persistence and coordinates Mock AI round-robin responses.
 */
@Service
@Transactional
public class ChatService {

    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final CharacterRepository characterRepository;
    private final MockAiService mockAiService;

    public ChatService(MessageRepository messageRepository,
                      RoomRepository roomRepository,
                      CharacterRepository characterRepository,
                      MockAiService mockAiService) {
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.characterRepository = characterRepository;
        this.mockAiService = mockAiService;
    }

    /**
     * Save a user or character message.
     */
    public MessageDto saveMessage(UUID roomId, UUID characterId, Message.SenderType senderType, String content) {
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

        Message saved = messageRepository.save(message);
        return MessageDto.fromEntity(saved);
    }

    /**
     * Get all messages for a room, ordered by creation time.
     */
    @Transactional(readOnly = true)
    public List<MessageDto> getMessagesByRoom(UUID roomId) {
        List<Message> messages = messageRepository.findByRoomIdOrderByCreatedAtAsc(roomId);
        return messages.stream()
            .map(MessageDto::fromEntity)
            .toList();
    }

    /**
     * Process a user message and trigger round-robin AI responses.
     * For each character in the room, in order:
     * 1. Emit "character thinking" event
     * 2. Wait for mock AI response
     * 3. Save and broadcast the response
     *
     * @param roomId Room ID
     * @param content User message content
     * @param characters List of characters in the room (in display order)
     * @param onThinking Callback when a character starts thinking: (characterId) -> void
     * @param onMessage Callback when a message is ready: (MessageDto) -> void
     */
    public void processUserMessage(UUID roomId, String content, List<Character> characters,
                                   Consumer<String> onThinking, Consumer<MessageDto> onMessage) {
        // Step 1: Save user message
        MessageDto userMsg = saveMessage(roomId, null, Message.SenderType.USER, content);
        onMessage.accept(userMsg);

        // Step 2: Round-robin AI responses
        for (Character character : characters) {
            // Emit thinking event
            onThinking.accept(character.getId().toString());

            // Generate and save AI response
            CompletableFuture<String> futureResponse = mockAiService.generateResponse(character, content);

            // Note: In a real implementation, we would wait for each character's
            // response before moving to the next (sequential round-robin).
            // For streaming responses, we handle them as they complete.
            final UUID charId = character.getId();
            final UUID roomUuid = roomId;

            futureResponse.thenAccept(response -> {
                MessageDto aiMsg = saveMessage(roomUuid, charId, Message.SenderType.CHARACTER, response);
                onMessage.accept(aiMsg);
            });
        }
    }
}
