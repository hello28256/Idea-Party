package com.ideaparty.service;

import com.ideaparty.dto.FeedbackResponse;
import com.ideaparty.dto.SubmitFeedbackRequest;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.MessageFeedback;
import com.ideaparty.entity.User;
import com.ideaparty.repository.MessageFeedbackRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomMemberRepository;
import com.ideaparty.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MessageFeedbackService {

    private static final int MAX_COMMENT_LENGTH = 1000;

    private final MessageFeedbackRepository feedbackRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final MessageObservationService observationService;

    /**
     * 提交或更新反馈。
     * 同一 (messageId, userId) 已有记录则更新，否则插入。
     */
    public FeedbackResponse submit(UUID userId, String messageId, SubmitFeedbackRequest req) {
        log.info("[DEBUG] submit feedback user={} message={} type={}", userId, messageId, req.getType());

        // Message.id is String (project quirk), not UUID — pass through directly.
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));

        if (message.getSenderType() != Message.SenderType.CHARACTER) {
            log.warn("[DEBUG] User {} tried to feedback non-character message {}", userId, messageId);
            throw new IllegalArgumentException("Can only give feedback to AI messages");
        }

        UUID roomId = message.getRoom().getId();
        if (!roomMemberRepository.isMember(roomId, userId)) {
            log.warn("[DEBUG] User {} is not a member of room {}", userId, roomId);
            throw new AccessDeniedException("You are not a member of this room");
        }

        if (req.getType() == FeedbackType.DISLIKE && req.getCategory() == null) {
            throw new IllegalArgumentException("DISLIKE feedback requires a category");
        }

        User userRef = userRepository.getReferenceById(userId);

        Optional<MessageFeedback> existing = feedbackRepository.findByMessageIdAndUserId(messageId, userId);
        MessageFeedback fb = existing.orElseGet(() -> MessageFeedback.builder()
                .message(message)
                .user(userRef)
                .build());

        fb.setType(req.getType());
        fb.setCategory(req.getType() == FeedbackType.DISLIKE ? req.getCategory() : null);
        fb.setComment(normalizeComment(req.getComment()));

        MessageFeedback saved = feedbackRepository.save(fb);
        log.info("[DEBUG] feedback saved id={}", saved.getId());
        recomputeObservation(message);
        return FeedbackResponse.fromEntity(saved);
    }

    private void recomputeObservation(Message message) {
        try {
            long likes = feedbackRepository.countByMessageIdAndType(message.getId(), FeedbackType.LIKE);
            long dislikes = feedbackRepository.countByMessageIdAndType(message.getId(), FeedbackType.DISLIKE);
            java.time.Instant last = feedbackRepository.findTopByMessageIdOrderByUpdatedAtDesc(message.getId())
                    .map(MessageFeedback::getUpdatedAt)
                    .orElse(null);
            observationService.recompute(message.getId(), likes, dislikes, last);
        } catch (Exception e) {
            log.warn("[Feedback] observation recompute failed: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<FeedbackResponse> get(UUID userId, String messageId) {
        return feedbackRepository.findByMessageIdAndUserId(messageId, userId)
                .map(FeedbackResponse::fromEntity);
    }

    public void delete(UUID userId, String messageId) {
        log.info("[DEBUG] delete feedback user={} message={}", userId, messageId);
        MessageFeedback fb = feedbackRepository.findByMessageIdAndUserId(messageId, userId)
                .orElseThrow(() -> new IllegalArgumentException("No feedback to delete"));
        feedbackRepository.delete(fb);
        messageRepository.findById(messageId).ifPresent(this::recomputeObservation);
    }

    private String normalizeComment(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() > MAX_COMMENT_LENGTH
                ? trimmed.substring(0, MAX_COMMENT_LENGTH)
                : trimmed;
    }
}
