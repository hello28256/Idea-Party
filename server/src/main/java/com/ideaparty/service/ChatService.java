package com.ideaparty.service;

import com.ideaparty.dto.MessageDto;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.Room;
import com.ideaparty.entity.User;
import com.ideaparty.exception.CharacterNotFoundException;
import com.ideaparty.exception.RoomNotFoundException;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Chat orchestration service.
 * Handles message persistence and coordinates AI round-robin responses.
 *
 * <p>核心职责：把用户消息落地到 MySQL，并按聊天室中角色的顺序依次调度 AIService
 * 生成回复，最终通过回调（onThinking / onMessage）把事件流推给 WebSocket 层，
 * 由前端实现「角色轮流发言」的群聊体验。
 */
@Service
@Transactional
public class ChatService {

    // 消息表写入入口；所有 sender=USER / CHARACTER 的消息都走这里持久化
    private final MessageRepository messageRepository;
    // 用于校验 roomId 合法性（消息必须挂在已存在的聊天室下）
    private final RoomRepository roomRepository;
    // 角色查找：saveMessage 校验 characterId；processUserMessage 传入的角色由 Controller 预加载
    private final CharacterRepository characterRepository;
    // 把 userId 关联到 sender=USER 的消息上（允许 null：历史导入/匿名场景）
    private final UserRepository userRepository;
    // 实际调用 DeepSeek/OpenAI 兼容接口的角色，由 LangChain4j 封装
    private final AIService aiService;
    // 把 Character 实体转成 system prompt；false 表示首版 prompt，不带「等待指令」之类后缀
    private final CharacterPromptBuilder characterPromptBuilder;

    /**
     * 构造器注入：Service 依赖多个仓储 + AI 编排组件，避免字段注入带来的可测试性问题。
     * 由 Spring 容器在启动时自动装配。
     */
    public ChatService(MessageRepository messageRepository,
                      RoomRepository roomRepository,
                      CharacterRepository characterRepository,
                      UserRepository userRepository,
                      AIService aiService,
                      CharacterPromptBuilder characterPromptBuilder) {
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
        this.aiService = aiService;
        this.characterPromptBuilder = characterPromptBuilder;
    }

    /**
     * Save a user or character message.
     *
     * <p>通用落库入口：既用于保存用户发言（characterId=null, senderType=USER），
     * 也用于保存 AI 角色回复（characterId 非空, senderType=CHARACTER）。
     *
     * @param roomId      必填，目标聊天室；不存在时抛 {@link RoomNotFoundException}
     * @param characterId 当 senderType=CHARACTER 时必填；USER 消息传 null
     * @param senderType  枚举，决定是否要绑定 user 关联
     * @param content     纯文本消息体；调用方需自行负责 XSS/长度校验
     * @param userId      USER 消息时关联到具体用户；CHARACTER 消息传 null
     * @return 持久化后的 DTO（带 id / createdAt），可直接通过 onMessage 推给前端
     * @throws RoomNotFoundException        roomId 不存在
     * @throws CharacterNotFoundException   characterId 非空但找不到对应角色
     */
    public MessageDto saveMessage(UUID roomId, UUID characterId, Message.SenderType senderType, String content, UUID userId) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new RoomNotFoundException("Room not found: " + roomId));

        Message message = new Message();
        message.setContent(content);
        message.setSenderType(senderType);
        message.setRoom(room);

        if (characterId != null) {
            Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new CharacterNotFoundException("Character not found: " + characterId));
            message.setCharacter(character);
        }

        if (userId != null && senderType == Message.SenderType.USER) {
            User user = userRepository.findById(userId).orElse(null);
            message.setUser(user);
        }

        Message saved = messageRepository.save(message);
        return MessageDto.fromEntity(saved);
    }

    /**
     * Get all messages for a room, ordered by creation time.
     *
     * <p>只读事务，避免脏读并复用 Hibernate 一级缓存。
     * 由 {@code RoomController} 在用户进入聊天室时调用，充当「历史回放」接口。
     *
     * @param roomId 聊天室 ID
     * @return 按 createdAt 升序的消息列表（用户/角色混合）
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
     * 2. Wait for AI response
     * 3. Save and broadcast the response
     *
     * @param roomId Room ID
     * @param content User message content
     * @param userId User ID of the sender
     * @param characters List of characters in the room (in display order)
     * @param onThinking Callback when a character starts thinking: (characterId) -> void
     * @param onMessage Callback when a message is ready: (MessageDto) -> void
     */
    public void processUserMessage(UUID roomId, String content, UUID userId, List<Character> characters,
                                   Consumer<String> onThinking, Consumer<MessageDto> onMessage) {
        // Step 1: Save user message
        // 先把用户这条消息入库并通过 onMessage 推送，前端立即可见；失败则整轮回滚（@Transactional）
        MessageDto userMsg = saveMessage(roomId, null, Message.SenderType.USER, content, userId);
        onMessage.accept(userMsg);

        // Step 2: Load conversation history for context
        // 把当前聊天室所有历史消息拼成 prompt 片段，让 AI 知道上文；包含本条用户消息本身
        String conversationHistory = buildConversationHistory(roomId);

        // Step 3: Round-robin AI responses
        for (Character character : characters) {
            // Emit thinking event
            // 通知前端「这个角色开始思考」，用于显示 loading/typing 指示器
            onThinking.accept(character.getId().toString());

            // Generate and save AI response using AIService (with history context)
            // 异步调用 AI：避免 DeepSeek 慢响应阻塞主线程；用 ForkJoinPool 公共线程池
            CompletableFuture<String> futureResponse = CompletableFuture.supplyAsync(() ->
                aiService.generateResponseWithHistory(characterPromptBuilder.build(character, false), content, conversationHistory)
            );

            // Note: In a real implementation, we would wait for each character's
            // response before moving to the next (sequential round-robin).
            // For streaming responses, we handle them as they complete.
            // 闭包内要用的可变变量必须 final；提前捕获避免 lambda 中的 effectively-final 报错
            final UUID charId = character.getId();
            final UUID roomUuid = roomId;

            futureResponse.thenAccept(response -> {
                // 角色回复落库后立刻推给前端；saveMessage 内会校验 room/character
                MessageDto aiMsg = saveMessage(roomUuid, charId, Message.SenderType.CHARACTER, response, null);
                onMessage.accept(aiMsg);
            });
        }
    }

    /**
     * Build conversation history string from messages in the room.
     * Formats as: "User: xxx\nCharacter: yyy\nUser: zzz\nCharacter: ..."
     *
     * <p>把 DB 里的结构化消息转成 LLM 偏好的纯文本格式，作为 system/user 之外的
     * 上下文片段传入 {@link AIService#generateResponseWithHistory}。
     * 历史为空时返回空串，调用方据此决定是否省略 history 参数。
     */
    private String buildConversationHistory(UUID roomId) {
        List<Message> messages = messageRepository.findByRoomIdOrderByCreatedAtAsc(roomId);
        if (messages.isEmpty()) {
            return "";
        }

        StringBuilder history = new StringBuilder();
        for (Message msg : messages) {
            if (msg.getSenderType() == Message.SenderType.USER) {
                history.append("User: ").append(msg.getContent()).append("\n");
            } else {
                String charName = msg.getCharacter() != null ? msg.getCharacter().getName() : "Character";
                history.append(charName).append(": ").append(msg.getContent()).append("\n");
            }
        }
        return history.toString();
    }
}
