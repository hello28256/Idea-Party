package com.ideaparty.service;

import com.ideaparty.dto.MessageSignalsResponse;
import com.ideaparty.dto.RecordEventRequest;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.MessageEvent;
import com.ideaparty.entity.MessageEvent.EventType;
import com.ideaparty.entity.User;
import com.ideaparty.repository.MessageEventRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomMemberRepository;
import com.ideaparty.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 负责记录与聚合消息级"隐性反馈事件"（曝光停留、复制、改写、阅读完成、编辑等）。
 * 设计动机：用户对单条 AI 回复的轻量交互信号是改进角色质量与排序的关键数据源，
 * 但不应阻塞主聊天流程；因此写入与聚合都做成独立的、可失败的副作用层。
 * 与 MessageService / RoomMemberService / ModeratorAgent 协作：被控制器调用写入，
 * 聚合结果供后续反馈训练与排序使用。
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MessageEventService {

    // 事件持久化：由 Controller 写入、由聚合器读取，事件流是单向追加。
    private final MessageEventRepository eventRepository;
    // 用于校验 messageId 是否存在并读取 room 上下文，以判定权限。
    private final MessageRepository messageRepository;
    // 仅作为外键占位（getReferenceById），不查表，避免不必要的 SELECT。
    private final UserRepository userRepository;
    // 用来判定调用方是否属于消息所在房间的成员，决定是否丢弃事件。
    private final RoomMemberRepository roomMemberRepository;

    /**
     * 记录一条针对指定 message 的隐性事件。
     * 契约：userId 必须是 message 所在房间的成员；message 必须是 CHARACTER 类型；
     * 非成员/非角色消息会被静默忽略而非抛错，避免泄露房间成员关系。
     * 调用方：MessageEventController（POST /api/messages/{id}/events）。
     */
    public void record(UUID userId, String messageId, RecordEventRequest req) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));

        if (message.getSenderType() != Message.SenderType.CHARACTER) {
            // Silently ignore: client may attach events to a slot that was later replaced
            // with a user message. Not a user-facing error.
            log.debug("[Event] ignoring non-character message {}", messageId);
            return;
        }

        UUID roomId = message.getRoom().getId();
        if (!roomMemberRepository.isMember(roomId, userId)) {
            // Same: ignore cross-room noise rather than 403-ing the client. 这种"静默拒绝"
            // 比抛 AccessDeniedException 更好——攻击者无法通过响应差异枚举房间成员关系。
            // 但仍升级到 warn 级别便于安全监控发现异常事件流。
            log.warn("[Event] ignoring event from non-member {} for room {} (message {})",
                userId, roomId, messageId);
            return;
        }

        User userRef = userRepository.getReferenceById(userId);
        MessageEvent ev = MessageEvent.builder()
                .message(message)
                .user(userRef)
                .eventType(req.getEventType())
                .dwellMs(req.getDwellMs())
                .metadata(req.getMetadata())
                .build();
        eventRepository.save(ev);
    }

    /**
     * 按 messageId 聚合该消息的所有事件，输出计数与平均停留时间。
     * 契约：readOnly 事务；输入为已存在的 messageId（不存在时返回空聚合对象而非抛错，由调用方决定如何渲染）；
     * 返回值供前端展示"这条回复被复制的次数"等轻量信号，以及离线分析用。
     * 调用方：MessageEventController（GET /api/messages/{id}/signals）。
     */
    @Transactional(readOnly = true)
    public MessageSignalsResponse aggregate(String messageId) {
        List<MessageEvent> events = eventRepository.findByMessageIdOrderByCreatedAtAsc(messageId);

        long rewrite = events.stream().filter(e -> e.getEventType() == EventType.REWRITE).count();
        long copy = events.stream().filter(e -> e.getEventType() == EventType.COPY).count();
        long read = events.stream().filter(e -> e.getEventType() == EventType.READ_COMPLETE).count();
        long edit = events.stream().filter(e -> e.getEventType() == EventType.EDIT).count();

        Double avgDwell = events.stream()
                .filter(e -> (e.getEventType() == EventType.READ_COMPLETE || e.getEventType() == EventType.FOCUS)
                        && e.getDwellMs() != null)
                .mapToInt(MessageEvent::getDwellMs)
                .average()
                .stream().boxed().findFirst().orElse(null);

        long uniqueUsers = events.stream()
                .map(e -> e.getUser().getId())
                .distinct()
                .count();

        return MessageSignalsResponse.builder()
                .messageId(messageId)
                .rewriteCount(rewrite)
                .copyCount(copy)
                .readCompleteCount(read)
                .editCount(edit)
                .averageDwellMs(avgDwell)
                .uniqueUsers(uniqueUsers)
                .build();
    }
}
