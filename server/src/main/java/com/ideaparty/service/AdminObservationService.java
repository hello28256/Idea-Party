package com.ideaparty.service;

import com.ideaparty.dto.AdminMessageObservationItem;
import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.MessageFeedback;
import com.ideaparty.entity.MessageObservation;
import com.ideaparty.repository.MessageFeedbackRepository;
import com.ideaparty.repository.MessageObservationRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin overview: list ALL AI messages (rated + unrated) with feedback rollup.
 * One row per observation, joined with message meta + caller's own feedback row
 * (if any) so the UI can distinguish rated / unrated / aggregated.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AdminObservationService {

    private final MessageObservationRepository observationRepository;
    private final MessageRepository messageRepository;
    private final MessageFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;

    /**
     * @param statusFilter  null = all, "RATED", "UNRATED", "AGGREGATED"
     */
    public Page<AdminMessageObservationItem> list(int page, int size, String statusFilter) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<MessageObservation> spec = (root, query, cb) -> {
            if (statusFilter == null || statusFilter.isBlank()) {
                return cb.conjunction();
            }
            return switch (statusFilter.toUpperCase()) {
                case "UNRATED" -> cb.equal(root.get("feedbackCount"), 0);
                case "RATED", "AGGREGATED", "FEEDBACK_EXISTS" ->
                    cb.greaterThan(root.get("feedbackCount"), 0);
                default -> cb.conjunction();
            };
        };

        Page<MessageObservation> observations = observationRepository.findAll(spec, pageable);
        if (observations.isEmpty()) return Page.empty(pageable);

        List<String> messageIds = observations.getContent().stream()
                .map(MessageObservation::getMessageId)
                .toList();
        Map<String, Message> messages = new HashMap<>();
        for (String id : messageIds) {
            messageRepository.findById(id).ifPresent(m -> messages.put(id, m));
        }

        List<AdminMessageObservationItem> items = observations.getContent().stream()
                .map(obs -> {
                    Message ai = messages.get(obs.getMessageId());
                    if (ai == null) return toItem(obs, null, null);
                    // Look up the most recent USER message before this AI one.
                    Message priorUser = messageRepository
                            .findPriorUserMessages(
                                    ai.getRoom().getId(),
                                    ai.getCreatedAt(),
                                    org.springframework.data.domain.PageRequest.of(0, 1))
                            .stream().findFirst().orElse(null);
                    return toItem(obs, ai, priorUser);
                })
                .toList();

        return new PageImpl<>(items, pageable, observations.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AdminMessageObservationItem detail(String messageId, UUID viewerUserId) {
        MessageObservation obs = observationRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Observation not found: " + messageId));
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        MessageFeedback viewerFb = null;
        if (viewerUserId != null) {
            viewerFb = feedbackRepository
                    .findByMessageIdAndUserId(messageId, viewerUserId)
                    .orElse(null);
        }
        Message priorUser = messageRepository
                .findPriorUserMessages(
                        message.getRoom().getId(),
                        message.getCreatedAt(),
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
        return toItem(obs, message, priorUser, viewerFb);
    }

    private AdminMessageObservationItem toItem(MessageObservation obs, Message m, Message priorUser) {
        return toItem(obs, m, priorUser, null);
    }

    private AdminMessageObservationItem toItem(MessageObservation obs, Message m, Message priorUser, MessageFeedback viewerFb) {
        AdminMessageObservationItem.AdminMessageObservationItemBuilder b = AdminMessageObservationItem.builder()
                .messageId(obs.getMessageId())
                .roomId(obs.getRoomId())
                .characterId(obs.getCharacterId())
                .feedbackCount(obs.getFeedbackCount())
                .likeCount(obs.getLikeCount())
                .dislikeCount(obs.getDislikeCount())
                .lastFeedbackAt(obs.getLastFeedbackAt());

        if (m != null) {
            b.messagePreview(truncate(m.getContent(), 80))
                    .messageCreatedAt(m.getCreatedAt());
            b.streamStatus(m.getStreamStatus() != null ? m.getStreamStatus().name() : "COMPLETE");
            if (m.getRoom() != null) {
                b.roomId(m.getRoom().getId().toString());
                b.roomName(m.getRoom().getName());
            }
            if (m.getCharacter() != null) {
                b.characterId(m.getCharacter().getId().toString());
                b.characterName(m.getCharacter().getName());
            }
        }

        if (priorUser != null) {
            b.userPrompt(truncate(priorUser.getContent(), 80));
            b.userPromptAt(priorUser.getCreatedAt());
        }

        if (viewerFb != null) {
            b.userId(viewerFb.getUser().getId().toString());
            b.username(viewerFb.getUser().getUsername());
            b.displayName(viewerFb.getUser().getDisplayName());
            b.feedbackType(viewerFb.getType());
            b.feedbackCategory(viewerFb.getCategory());
            b.feedbackComment(viewerFb.getComment());
            b.userFeedbackAt(viewerFb.getUpdatedAt());
            b.status("RATED");
        } else if (obs.getFeedbackCount() != null && obs.getFeedbackCount() > 0) {
            b.status("AGGREGATED");
        } else {
            b.status("UNRATED");
        }
        return b.build();
    }

    private String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
