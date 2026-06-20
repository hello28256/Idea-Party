package com.ideaparty.controller;

import com.ideaparty.dto.MessageResponse;
import com.ideaparty.dto.SendMessageRequest;
import com.ideaparty.entity.Message;
import com.ideaparty.service.MessageService;
import com.ideaparty.service.ModerationService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天室消息 REST 控制器，挂在房间资源子路径下：所有消息都属于某个 roomId。
 * 由前端 RoomView 在初次加载、翻页加载历史和发送新消息时调用；
 * 真实时消息推送走 Socket.IO/WebSocket 通道（{@code ChatWebSocketHandler}），本控制器只承担 REST 维度的查询与写入入口。
 * 通过 MessageService 持久化聊天记录，并通过 ModerationService 在入库前做内容审核；
 * 异常统一交给 GlobalExceptionHandler 转换为 ErrorResponse 风格，保持控制器无内联错误响应。
 */
@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
public class MessageController {

    // 消息持久化与查询服务，负责入库、按房间查询、分页查询等能力；所有写操作经此处落库。
    private final MessageService messageService;
    // 内容审核服务，在用户消息入库前做合规检查（违规/敏感词），阻止非法内容写入数据库。
    private final ModerationService moderationService;

    /**
     * Spring 构造器注入：两个依赖都是无状态服务，构造期装配即可，无需 setter 或字段注入。
     * @param messageService 消息服务，由 Spring 容器提供单例
     * @param moderationService 审核服务，由 Spring 容器提供单例
     */
    public MessageController(MessageService messageService, ModerationService moderationService) {
        this.messageService = messageService;
        this.moderationService = moderationService;
    }

    /**
     * 拉取指定房间的全量消息列表（不分页），按存储顺序返回。
     * 调用方：前端首屏加载或小房间直接拉全量；消息量大时建议走 {@link #getMessagesPaginated}。
     * @param roomId 路径变量，房间 UUID 字符串；为空或非法格式由 {@link #parseRoomId} 抛 IllegalArgumentException
     * @return 200 OK，消息响应列表（已通过 MessageResponse.fromEntity 转 DTO）
     */
    @GetMapping
    public ResponseEntity<List<MessageResponse>> getMessages(@PathVariable String roomId) {
        UUID roomUuid = parseRoomId(roomId);
        List<MessageResponse> messages = messageService.getMessagesByRoomId(roomUuid).stream()
            .map(MessageResponse::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(messages);
    }

    /**
     * 分页拉取指定房间的消息，Spring Data Page 包装包含总数、页码等元信息。
     * 调用方：前端聊天记录滚动加载更多时使用，避免一次性加载整张表。
     * @param roomId 路径变量，房间 UUID 字符串
     * @param page 页码，0-based；缺省 0（即第一页）
     * @param size 每页大小，缺省 50；过大可能拖慢响应，按业务需要调整
     * @return 200 OK，分页后的消息响应 Page 对象
     */
    @GetMapping("/paginated")
    public ResponseEntity<Page<MessageResponse>> getMessagesPaginated(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID roomUuid = parseRoomId(roomId);
        Page<Message> messages = messageService.getMessagesPaginated(roomUuid, page, size);
        return ResponseEntity.ok(messages.map(MessageResponse::fromEntity));
    }

    /**
     * 发送一条消息：校验登录态 → 内容审核 → 持久化入库 → 返回 DTO。
     * 调用方：前端用户在聊天室输入文本并提交时；该方法只负责 REST 写入，真正实时广播仍走 WebSocket 通道。
     * @param auth Spring Security 认证对象，auth.getName() 即用户 UUID；为 null 时抛 AccessDeniedException，由 GlobalExceptionHandler 转为 401
     * @param roomId 路径变量，目标房间 UUID 字符串
     * @param request 消息请求体，包含 content/senderType/characterId 等字段
     * @return 201 Created + 已入库的消息 DTO；审核不通过抛 IllegalArgumentException（400）；未登录抛 AccessDeniedException（401）
     */
    @PostMapping
    public ResponseEntity<MessageResponse> sendMessage(
            Authentication auth,
            @PathVariable String roomId,
            @RequestBody SendMessageRequest request) {
        // 统一交给 GlobalExceptionHandler：401/400/404 等都用 ErrorResponse 返回
        // auth 为 null 通常出现在 SecurityContext 被过滤器链清空或匿名访问受保护接口时；
        // 这里显式抛 AccessDeniedException 而非让后续 UUID.fromString 抛 NPE，确保前端拿到的是 401 而非 500。
        if (auth == null) {
            throw new org.springframework.security.access.AccessDeniedException("请先登录");
        }
        UUID roomUuid = parseRoomId(roomId);

        ModerationService.ModerationResult result = moderationService.moderate(request.getContent());
        if (!result.isAllowed()) {
            throw new IllegalArgumentException(result.getReason());
        }

        UUID userId = UUID.fromString(auth.getName());
        Message.SenderType senderType = Message.SenderType.valueOf(request.getSenderType());
        UUID characterUuid = request.getCharacterId() != null ? UUID.fromString(request.getCharacterId()) : null;
        Message message = messageService.saveMessage(
            roomUuid,
            characterUuid,
            senderType,
            request.getContent(),
            userId
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageResponse.fromEntity(message));
    }

    /**
     * 把 controller 层的字符串校验集中在此：保持方法签名只接 UUID，
     * 让 GlobalExceptionHandler 统一转换为 ErrorResponse，避免 controller 内联 Map.of 风格。
     */
    private UUID parseRoomId(String roomId) {
        if (roomId == null || roomId.isBlank() || "null".equals(roomId) || "undefined".equals(roomId)) {
            throw new IllegalArgumentException("聊天室 ID 不能为空");
        }
        try {
            return UUID.fromString(roomId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的聊天室 ID 格式");
        }
    }
}