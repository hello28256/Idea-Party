package com.ideaparty.repository;

import com.ideaparty.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 聊天室成员关系的数据访问层。
 *
 * 基于 Spring Data JPA 暴露 RoomMember 实体的查询/写入入口，与
 * {@link com.ideaparty.service.RoomMemberService}、RoomService 协作：
 * 房间成员列表、权限校验、删除房间时级联清理成员行均由本仓库提供底层支撑，
 * 是聊天室权限边界与"谁在房间里"事实数据的唯一来源。
 */
@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {

    /**
     * 拉取指定房间全部仍处于 active 状态的成员（含其关联 User）。
     * JOIN FETCH user 是为了避免成员列表渲染时的 N+1，前端成员面板与权限校验
     * （如列出 owner/admin）均依赖此查询。
     *
     * @param roomId 聊天室主键，UUID 类型对应 Room.id
     * @return active 状态的成员列表；房间不存在或无成员时返回空列表
     */
    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.user WHERE rm.room.id = :roomId AND rm.status = 'active'")
    List<RoomMember> findActiveMembersByRoomId(@Param("roomId") UUID roomId);

    /**
     * 定位某用户在某房间中的 active 成员记录（含 User）。
     * 用于鉴权前置检查（"此人是否还在这个房间里"），返回 Optional 是为了与
     * 业务层的"未找到即拒绝"语义对齐，避免调用方忘记判空。
     *
     * @param roomId 聊天室主键
     * @param userId 用户主键
     * @return 存在 active 记录时返回对应成员，否则返回 Optional.empty()
     */
    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.user WHERE rm.room.id = :roomId AND rm.user.id = :userId AND rm.status = 'active'")
    Optional<RoomMember> findActiveMember(@Param("roomId") UUID roomId, @Param("userId") UUID userId);

    /**
     * 轻量级成员存在性判断，仅返回布尔值。
     * 相比 {@link #findActiveMember(UUID, UUID)} 省去实体加载，专用于"是否准入"
     * 的高频权限校验路径（如 WebSocket 连接鉴权、发送消息前置检查），
     * 减少不必要的数据传输。
     *
     * @param roomId 聊天室主键
     * @param userId 用户主键
     * @return 用户在该房间拥有 active 成员记录时返回 true，否则 false
     */
    @Query("SELECT CASE WHEN COUNT(rm) > 0 THEN true ELSE false END FROM RoomMember rm WHERE rm.room.id = :roomId AND rm.user.id = :userId AND rm.status = 'active'")
    boolean isMember(@Param("roomId") UUID roomId, @Param("userId") UUID userId);

    /**
     * 列出某用户参与中的所有 active 房间（含 Room 信息）。
     * 按 joinedAt DESC 排序——最近加入的房间排在前端，便于"我的房间"列表
     * 默认展示最近活跃会话；JOIN FETCH room 避免逐条查询 Room 详情。
     *
     * @param userId 用户主键
     * @return 该用户 active 状态下的房间成员记录列表（含 Room 关联）
     */
    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.room WHERE rm.user.id = :userId AND rm.status = 'active' ORDER BY rm.joinedAt DESC")
    List<RoomMember> findActiveRoomsByUserId(@Param("userId") UUID userId);

    /**
     * 按 roomId 批量物理删除成员行。
     * 由删除房间的服务调用，用于在 Room 被删除时一并清理关联的成员关系
     * （绕过软删除 status=removed，因为整间房间已不存在，保留无意义）。
     * Spring Data 根据方法名自动派生 delete 实现。
     *
     * @param roomId 聊天室主键
     */
    void deleteByRoomId(UUID roomId);
}
