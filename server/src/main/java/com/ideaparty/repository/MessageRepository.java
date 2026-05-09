package com.ideaparty.repository;

import com.ideaparty.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.character WHERE m.room.id = :roomId ORDER BY m.createdAt ASC")
    List<Message> findByRoomIdWithCharacter(@Param("roomId") UUID roomId);

    List<Message> findByRoomIdOrderByCreatedAtAsc(UUID roomId);

    Page<Message> findByRoomIdOrderByCreatedAtDesc(UUID roomId, Pageable pageable);
}
