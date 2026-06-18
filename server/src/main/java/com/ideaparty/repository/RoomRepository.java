package com.ideaparty.repository;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoomRepository extends JpaRepository<Room, UUID> {

    @Query("SELECT r FROM Room r JOIN FETCH r.owner WHERE r.owner.id = :ownerId")
    List<Room> findByOwnerId(@Param("ownerId") UUID ownerId);

    @Query("SELECT r FROM Room r LEFT JOIN FETCH r.characters LEFT JOIN FETCH r.members WHERE r.id = :id")
    Optional<Room> findWithCharactersById(@Param("id") UUID id);

    Optional<Room> findByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    @Query("SELECT r FROM Room r JOIN FETCH r.owner JOIN FETCH r.members m JOIN FETCH m.user WHERE m.user.id = :userId AND m.status = 'active' ORDER BY COALESCE(r.lastEnterTime, r.updatedAt) DESC")
    List<Room> findRoomsByMemberUserId(@Param("userId") UUID userId);

    /** 查找所有引用了指定角色的房间（用于删除角色前解除关联） */
    List<Room> findAllByCharactersContaining(Character character);

    /** 统计引用了指定角色的房间数（用于删除角色前的引用检查） */
    @Query("SELECT COUNT(r) FROM Room r JOIN r.characters c WHERE c.id = :characterId")
    long countByCharactersId(@Param("characterId") UUID characterId);
}
