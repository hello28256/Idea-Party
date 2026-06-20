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

    // Spring-injected JPA repository; final + @RequiredArgsConstructor keeps it testable and immutable.
    private final MessageObservationRepository observationRepository;

    /**
     * Seed an observation row the first time an AI message lands in the database.
     * No-op if the message has no id yet (caller forgot to flush) or if the row already
     * exists, so it is safe to invoke from message-save listeners that may fire twice.
     *
     * @param message the just-persisted AI {@link Message}; expects non-null id and loaded room/character
     */
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

    /**
     * Idempotent seed for messages written before this rollup table existed.
     * Used by one-off backfill paths so legacy messages still get a row without
     * re-running the AI pipeline. Tolerates an existing row.
     *
     * @param messageId   primary key of the legacy {@link Message}
     * @param roomId      owning room id; stored as String for schema-level decoupling from entity FK types
     * @param characterId speaker id, nullable for system/narrator-style messages
     */
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
     * Called after submit/delete to keep the rollup accurate. Throws when the
     * row is missing so the caller knows the message is in an inconsistent state
     * (seed must happen first via {@link #onAiMessagePersisted} or {@link #ensureExists}).
     *
     * @param messageId       id of the message whose observation should be updated
     * @param likeCount       number of like feedbacks counted upstream
     * @param dislikeCount    number of dislike feedbacks counted upstream
     * @param lastFeedbackAt  timestamp of the most recent feedback, used for sorting/filtering
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

    /**
     * Read a single observation row for admin/overview lookups.
     * Read-only transaction: lets Hibernate skip dirty-checking and
     * play nicely with the read replica if one is added later.
     *
     * @param messageId message id to look up
     * @return the observation, or empty when no rollup row has been seeded yet
     */
    @Transactional(readOnly = true)
    public Optional<MessageObservation> find(String messageId) {
        return observationRepository.findById(messageId);
    }
}
