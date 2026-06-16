package com.ideaparty.repository;

import com.ideaparty.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.character WHERE m.room.id = :roomId ORDER BY m.createdAt ASC")
    List<Message> findByRoomIdWithCharacter(@Param("roomId") UUID roomId);

    List<Message> findByRoomIdOrderByCreatedAtAsc(UUID roomId);

    Page<Message> findByRoomIdOrderByCreatedAtDesc(UUID roomId, Pageable pageable);

    /**
     * Message.id is a String (declared via @GeneratedValue UUID but field type is String).
     * JpaRepository's built-in findById accepts only the generic ID type, so we add a
     * String overload that delegates via a JPQL query. Existing callers can keep using
     * findById(UUID) when they already have a UUID; this method accepts the raw id.
     */
    @Query("SELECT m FROM Message m WHERE m.id = :id")
    Optional<Message> findByIdString(@Param("id") String id);

    default Optional<Message> findById(String id) {
        return findByIdString(id);
    }

    /**
     * The most recent USER message in the same room strictly before the given
     * (CHARACTER) message. Used to show admins "what the user asked" alongside
     * a feedback detail. Returns empty when the AI message is the first in
     * the room or no prior USER message exists.
     */
    @Query("""
        SELECT m FROM Message m
        WHERE m.room.id = :roomId
          AND m.senderType = com.ideaparty.entity.Message.SenderType.USER
          AND m.createdAt < :before
        ORDER BY m.createdAt DESC
        """)
    List<Message> findPriorUserMessages(@Param("roomId") UUID roomId,
                                        @Param("before") java.time.LocalDateTime before,
                                        org.springframework.data.domain.Pageable pageable);
}
