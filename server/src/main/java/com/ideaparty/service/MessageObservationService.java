package com.ideaparty.service;

import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.MessageObservation;
import com.ideaparty.repository.MessageObservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Maintains the per-message observation rollup used by the admin overview.
 * One observation row per AI message. Counters are kept in sync with
 * message_feedbacks by the feedback service.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MessageObservationService {

    private final MessageObservationRepository observationRepository;

    /** Called when an AI message is first persisted. Idempotent. */
    public void onAiMessagePersisted(Message message) {
        if (message == null || message.getId() == null) return;
        if (observationRepository.existsById(message.getId())) return;
        MessageObservation obs = MessageObservation.builder()
                .messageId(message.getId())
                .roomId(message.getRoom().getId().toString())
                .characterId(message.getCharacter() != null ? message.getCharacter().getId().toString() : null)
                .feedbackCount(0)
                .likeCount(0)
                .dislikeCount(0)
                .build();
        observationRepository.save(obs);
    }

    /** Idempotent seed for messages that existed before this table. */
    public void ensureExists(String messageId, UUID roomId, UUID characterId) {
        if (observationRepository.existsById(messageId)) return;
        MessageObservation obs = MessageObservation.builder()
                .messageId(messageId)
                .roomId(roomId.toString())
                .characterId(characterId != null ? characterId.toString() : null)
                .build();
        observationRepository.save(obs);
    }

    /**
     * Recompute counters from current message_feedbacks for a given message.
     * Called after submit/delete to keep the rollup accurate.
     * Uses the feedback repository directly to count.
     */
    public void recompute(String messageId, long likeCount, long dislikeCount, java.time.Instant lastFeedbackAt) {
        MessageObservation obs = observationRepository.findById(messageId)
                .orElseThrow(() -> new IllegalStateException("Observation missing for " + messageId));
        int total = (int) (likeCount + dislikeCount);
        obs.setLikeCount((int) likeCount);
        obs.setDislikeCount((int) dislikeCount);
        obs.setFeedbackCount(total);
        obs.setLastFeedbackAt(lastFeedbackAt);
        observationRepository.save(obs);
    }

    @Transactional(readOnly = true)
    public Optional<MessageObservation> find(String messageId) {
        return observationRepository.findById(messageId);
    }
}
