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

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MessageEventService {

    private final MessageEventRepository eventRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RoomMemberRepository roomMemberRepository;

    /**
     * Record an implicit event for a message.
     * Mirrors the feedback submit flow: must be a room member, message must be CHARACTER.
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
            // Same: ignore cross-room noise rather than 403-ing the client.
            log.debug("[Event] ignoring event from non-member {} for room {}", userId, roomId);
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
