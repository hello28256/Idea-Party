package com.ideaparty.repository;

import com.ideaparty.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {

    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.user WHERE rm.room.id = :roomId AND rm.status = 'active'")
    List<RoomMember> findActiveMembersByRoomId(@Param("roomId") UUID roomId);

    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.user WHERE rm.room.id = :roomId AND rm.user.id = :userId AND rm.status = 'active'")
    Optional<RoomMember> findActiveMember(@Param("roomId") UUID roomId, @Param("userId") UUID userId);

    @Query("SELECT CASE WHEN COUNT(rm) > 0 THEN true ELSE false END FROM RoomMember rm WHERE rm.room.id = :roomId AND rm.user.id = :userId AND rm.status = 'active'")
    boolean isMember(@Param("roomId") UUID roomId, @Param("userId") UUID userId);

    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.room WHERE rm.user.id = :userId AND rm.status = 'active' ORDER BY rm.joinedAt DESC")
    List<RoomMember> findActiveRoomsByUserId(@Param("userId") UUID userId);

    void deleteByRoomId(UUID roomId);
}
