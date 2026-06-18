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

/**
 * 聊天室消息的持久化与查询服务，作为 Controller 与 Repository 之间的编排层。
 * 与 RoomService/CharacterService 共享实体引用，但本服务只关心"消息写入"这一条主线，以及写入后对 AI 消息的旁路观测触发。
 */
@Service
@Transactional
public class MessageService {

    // 观测服务：仅在 AI 消息落库后触发，用于驱动 Moderator 编排下一轮发言等异步逻辑，不影响主写入路径。
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

    /**
     * 写入一条消息并按发送方类型补齐关联实体（character/user）。
     * 契约：characterId 仅在 AI 角色发送时携带；userId 仅在 USER 类型时设置，避免 AI 消息被错误归属到具体用户。
     * 副作用：CHARACTER 类型消息会异步触发观测服务用于驱动后续编排，失败仅记录 warn、不回滚主写入。
     */
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

        // 仅 USER 消息关联具体用户：AI 消息虽然由用户房间触发，但归属应为角色而非个人账号。
        if (userId != null && senderType == Message.SenderType.USER) {
            User user = userRepository.findById(userId).orElse(null);
            message.setUser(user);
        }

        Message saved = messageRepository.save(message);
        // 旁路观测：AI 消息落库即通知下游 Moderator/编排服务；失败降级为 warn，避免观测链路抖动阻塞聊天主链路。
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
        // 走带 character 关联的查询，避免前端展示时 N+1 回查角色表。
        return messageRepository.findByRoomIdWithCharacter(roomId);
    }

    public Page<Message> getMessagesPaginated(UUID roomId, int page, int size) {
        // 即使方法名带 Desc，排序仍取 ASC：前端通常按时间正序渲染，PageRequest 仅承担分页职责。
        return messageRepository.findByRoomIdOrderByCreatedAtDesc(
            roomId,
            PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"))
        );
    }

    public Optional<Message> getMessageById(UUID id) {
        return messageRepository.findById(id);
    }
}
