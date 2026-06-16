package com.ideaparty.repository;

import com.ideaparty.entity.MessageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageEventRepository extends JpaRepository<MessageEvent, UUID> {

    /** All events for a message, oldest first. Used to aggregate implicit signals. */
    List<MessageEvent> findByMessageIdOrderByCreatedAtAsc(String messageId);

    /** Per-event-type counts (avoids loading full entities when we just need the count). */
    long countByMessageIdAndEventType(String messageId, MessageEvent.EventType eventType);
}
