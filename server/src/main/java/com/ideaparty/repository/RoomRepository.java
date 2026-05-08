package com.ideaparty.repository;

import com.ideaparty.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, String> {

    List<Room> findByThemeOrderByCreatedAtDesc(String theme);

    @Query("SELECT r FROM Room r LEFT JOIN FETCH r.characters WHERE r.id = :id")
    Optional<Room> findWithCharactersById(@Param("id") String id);
}
