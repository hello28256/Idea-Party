package com.ideaparty.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.Room;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.service.AuthService;
import com.ideaparty.service.ChatService;
import com.ideaparty.service.MessageService;
import com.ideaparty.service.ModerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 实时聊天 WebSocket 处理器，兼容 Socket.IO 协议。
 * 处理事件：
 * - 'join room' { roomId } - 用户加入房间
 * - 'chat message' { roomId, content } - 用户发送消息，触发 ChatService
 * - 'leave room' { roomId } - 用户离开房间
 *
 * 发出事件：
 * - 'message' { MessageDto } - 新消息
 * - 'character thinking' { characterId } - 角色开始思考
 * - 'message stream' { characterId, chunk } - 流式响应片段（模拟）
 */
@Slf4j
// NOTE: 不再标注 @Component。当前运行时由 SocketConfig + ChatSocketHandler 接管 /ws 端点，
// 保留此类仅作降级 / 对照实现，便于未来在不需要 Socket.IO 的场景下快速切换。
// 若想启用，请同时打开 WebSocketConfig 中的 @Configuration / @EnableWebSocket，
// 并确认不会与 ChatSocketHandler 在同一路径产生两个 handler 冲突。
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // 将 roomId 映射到该房间的 session 集合
    // 用于按房间维度向所有在线 session 广播消息，房间无 session 时会随 join/leave 自动清理
    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    // 将 sessionId 映射到 roomId
    // 记录每个 session 当前所在的房间，断连时据此自动 leave，避免脏数据
    private final ConcurrentHashMap<String, String> sessionRooms = new ConcurrentHashMap<>();
    // 将 sessionId 映射到 userId
    // join room 时若前端传 token 则解析写入，供后续 chat message 关联到具体发言用户
    private final ConcurrentHashMap<String, UUID> sessionUsers = new ConcurrentHashMap<>();
    // 复用单例 ObjectMapper 解析 Socket.IO "42[event, data]" 帧，避免每次创建开销
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 注入 service/repository 供 handleChatMessage 串联审核-查房-编排-落库-广播全流程
    private final MessageService messageService;
    // 负责多角色轮询/Moderator 编排，由 ModerationService 通过构造注入保持测试可替换
    private final ChatService chatService;
    // 用户发言前的合规审查服务，命中策略时直接拒绝并向当前 session 回 error 事件
    private final ModerationService moderationService;
    // 加载房间实体及其角色列表（无角色则提示 "No characters in room"）
    private final RoomRepository roomRepository;
    // 校验 join room 时附带的 token，将合法 userId 写入 sessionUsers
    private final AuthService authService;
    // 当前未在 handler 内直接调用，保留以便后续按 userId 反查用户信息时复用
    private final UserRepository userRepository;

    // 通过构造器注入所有协作服务，便于单元测试时用 mock 替换（避免字段注入难以替换）
    public ChatWebSocketHandler(MessageService messageService, ChatService chatService,
                               ModerationService moderationService,
                               RoomRepository roomRepository,
                               AuthService authService,
                               UserRepository userRepository) {
        this.messageService = messageService;
        this.chatService = chatService;
        this.moderationService = moderationService;
        this.roomRepository = roomRepository;
        this.authService = authService;
        this.userRepository = userRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Socket.IO 客户端在初始时发送 "40"（连接包）
        // 不在此处主动推送，业务握手交由客户端的 "40" 帧触发，避免重复响应
    }

    @Override
    // Socket.IO 协议入口：所有来自客户端的帧都先经过此方法，按前缀分发到 ping/connect/MESSAGE 处理分支
    // 入参 session 为当前 WebSocket 连接上下文，message 携带原始 payload；异常向上抛由 Spring 框架处理
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        // Socket.IO 协议："42" 前缀表示带事件名的 MESSAGE
        // 格式：42["event_name", data]
        if (payload.startsWith("42")) {
            handleSocketIOMessage(session, payload.substring(2));
        } else if (payload.equals("2")) {
            // "2" 是 ping，回复 "3"（pong）
            session.sendMessage(new TextMessage("3"));
        } else if (payload.startsWith("40")) {
            // "40" 是 connect 包，回复 "40"（连接确认）
            session.sendMessage(new TextMessage("40"));
        }
    }

    // 解析 "42" 后的 JSON 数组 [event, data]，按事件名路由到对应处理器；非数组或长度不足 2 直接忽略
    // data 为去前缀后的原始 JSON 字符串，由调用方（handleTextMessage）裁剪
    private void handleSocketIOMessage(WebSocketSession session, String data) throws Exception {
        JsonNode node = objectMapper.readTree(data);
        if (node.isArray() && node.size() >= 2) {
            String event = node.get(0).asText();
            JsonNode eventData = node.get(1);

            switch (event) {
                case "join room":
                    handleJoinRoom(session, eventData);
                    break;
                case "leave room":
                    handleLeaveRoom(session, eventData);
                    break;
                case "chat message":
                    handleChatMessage(session, eventData);
                    break;
                default:
                    // 未知事件 —— 调试时记录日志
                    // 静默忽略未知事件，避免日志噪声；后续如需排查可加 log.debug
                    break;
            }
        }
    }

    // 处理 "join room" 事件：登记 session 到房间并解析可选 token，token 非法不影响加入（仅影响后续发言归属）
    // session 为新会话，data 至少包含 roomId，可选 token 用于绑定 userId
    private void handleJoinRoom(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        joinRoom(roomId, session);

        // 提取并校验 token 中的用户（如提供）
        // token 可选：未登录用户也能加入房间听广播，仅在发言时退化为匿名
        if (data.has("token") && !data.get("token").isNull()) {
            try {
                String token = data.get("token").asText();
                UUID userId = authService.validateToken(token);
                sessionUsers.put(session.getId(), userId);
                log.info("[WS] User {} joined room {}", userId, roomId);
            } catch (Exception e) {
                log.warn("[WS] Failed to validate token on join: {}", e.getMessage());
            }
        }

        session.sendMessage(new TextMessage("42[\"room-joined\",{\"roomId\":\"" + roomId + "\"}]"));
    }

    // 处理 "leave room" 事件：仅清理 roomId 维度映射，userId/sessionId 关联保留至连接关闭统一回收
    // session 为发起离开的会话，data 必含 roomId
    private void handleLeaveRoom(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        leaveRoom(roomId, session);
    }

    // 处理 "chat message" 事件：串联 ModerationService 审查、RoomRepository 加载、ChatService 编排、
    // 最终通过 onThinking/onMessage 回调向房间广播；sessionUsers 中若无 userId 则按匿名处理
    // session 为发言者连接，data 必含 roomId 与 content
    private void handleChatMessage(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        String content = data.get("content").asText();

        // 从 session 中获取 userId
        UUID userId = sessionUsers.get(session.getId());

        // 内容审核
        ModerationService.ModerationResult result = moderationService.moderate(content);
        if (!result.isAllowed()) {
            String errorMessage = "42[\"error\","
                + objectMapper.writeValueAsString(Map.of("message", result.getReason()))
                + "]";
            session.sendMessage(new TextMessage(errorMessage));
            return;
        }

        // 获取房间及其角色
        Room room = roomRepository.findById(UUID.fromString(roomId)).orElse(null);
        if (room == null) {
            String errorMessage = "42[\"error\","
                + objectMapper.writeValueAsString(Map.of("message", "Room not found"))
                + "]";
            session.sendMessage(new TextMessage(errorMessage));
            return;
        }

        List<Character> characters = room.getCharacters().stream().collect(Collectors.toList());
        if (characters.isEmpty()) {
            String errorMessage = "42[\"error\","
                + objectMapper.writeValueAsString(Map.of("message", "No characters in room"))
                + "]";
            session.sendMessage(new TextMessage(errorMessage));
            return;
        }

        // 使用 ChatService 进行轮询处理
        chatService.processUserMessage(
            java.util.UUID.fromString(roomId),
            content,
            userId,
            characters,
            // onThinking 回调
            (characterId) -> {
                try {
                    String thinkingEvent = "42[\"character thinking\","
                        + objectMapper.writeValueAsString(Map.of("characterId", characterId))
                        + "]";
                    broadcastToRoom(roomId, thinkingEvent);
                } catch (Exception e) {
                    log.warn("[WS] Failed to send thinking event: {}", e.getMessage());
                }
            },
            // onMessage 回调
            (messageDto) -> {
                try {
                    String msgEvent = "42[\"chat message\","
                        + objectMapper.writeValueAsString(Map.of(
                            "id", messageDto.getId() != null ? messageDto.getId() : "",
                            "roomId", messageDto.getRoomId() != null ? messageDto.getRoomId() : "",
                            "characterId", messageDto.getCharacterId() != null ? messageDto.getCharacterId() : "",
                            "characterName", messageDto.getCharacterName() != null ? messageDto.getCharacterName() : "",
                            "senderType", messageDto.getSenderType() != null ? messageDto.getSenderType() : "",
                            "userId", messageDto.getUserId() != null ? messageDto.getUserId() : "",
                            "content", messageDto.getContent() != null ? messageDto.getContent() : "",
                            "createdAt", messageDto.getCreatedAt() != null ? messageDto.getCreatedAt().toString() : ""
                        ))
                        + "]";
                    broadcastToRoom(roomId, msgEvent);
                } catch (Exception e) {
                    log.warn("[WS] Failed to send message event: {}", e.getMessage());
                }
            }
        );
    }

    @Override
    // 连接关闭钩子：清理 session ↔ room/user 映射并触发 leaveRoom，幂等可多次调用
    // 依赖框架保证 session.getId() 仍可用；若 roomId 为 null 表示该 session 从未加入任何房间
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = sessionRooms.remove(session.getId());
        sessionUsers.remove(session.getId());
        if (roomId != null) {
            leaveRoom(roomId, session);
        }
    }

    // 将 session 登记到指定房间的在线集合，并更新 session->room 反向索引，供断连时反向定位
    // 被 handleJoinRoom 与 afterConnectionEstablished 后的回放场景共用
    public void joinRoom(String roomId, WebSocketSession session) {
        rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        sessionRooms.put(session.getId(), roomId);
    }

    // 从指定房间移除 session；集合为空时同步删除 roomId 键，避免内存泄漏（房间维度无人后自动回收）
    // 双向索引同步清理，保证 sessionRooms 与 rooms 状态一致
    public void leaveRoom(String roomId, WebSocketSession session) {
        Set<WebSocketSession> room = rooms.get(roomId);
        if (room != null) {
            room.remove(session);
            if (room.isEmpty()) {
                rooms.remove(roomId);
            }
        }
        sessionRooms.remove(session.getId());
    }

    // 向房间内所有仍处于打开状态的 session 广播同一条消息；单条发送失败仅记 warn，不中断其他 session
    // message 必须已是完整的 Socket.IO 帧（含 "42[...]" 前缀），由调用方负责序列化
    public void broadcastToRoom(String roomId, String message) {
        Set<WebSocketSession> room = rooms.get(roomId);
        if (room != null) {
            TextMessage textMessage = new TextMessage(message);
            for (WebSocketSession s : room) {
                if (s.isOpen()) {
                    try {
                        s.sendMessage(textMessage);
                    } catch (Exception e) {
                        log.warn("[WS] Failed to send message to session {}: {}", s.getId(), e.getMessage());
                    }
                }
            }
        }
    }
}
