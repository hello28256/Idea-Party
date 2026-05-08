package com.ideaparty.repository;

import com.ideaparty.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, String> {

    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.character WHERE m.room.id = :roomId ORDER BY m.createdAt ASC")
    List<Message> findByRoomIdWithCharacter(@Param("roomId") String roomId);

    Page<Message> findByRoomIdOrderByCreatedAtDesc(String roomId, Pageable pageable);
}
