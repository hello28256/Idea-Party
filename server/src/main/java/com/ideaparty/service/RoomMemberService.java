package com.ideaparty.service;

import com.ideaparty.entity.Room;
import com.ideaparty.entity.RoomMember;
import com.ideaparty.entity.User;
import com.ideaparty.repository.RoomMemberRepository;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 聊天室成员领域服务。
 *
 * 负责聊天室与用户之间的多对多关联：成员邀请、移除、所有者登记、权限校验。
 * 与 RoomService 协作（创建聊天室后由本服务补登 owner 成员行），与 WebSocket / 权限拦截器协作（用于校验访问权）。
 */
@Service
@RequiredArgsConstructor
public class RoomMemberService {

    /** 成员关系持久化入口；软删除（status=removed）也由其承担，避免硬删除破坏历史消息归属。 */
    private final RoomMemberRepository roomMemberRepository;
    /** 用于在邀请/移除流程中按 ID 加载 Room 实体，校验目标聊天室真实存在。 */
    private final RoomRepository roomRepository;
    /** 用于解析邀请关键字（用户名或邮箱）以及加载 inviter 实体以写入 invitedBy 字段。 */
    private final UserRepository userRepository;

    /**
     * 获取某聊天室当前所有有效成员（status=active）。
     * 调用方：房间详情页、成员管理面板、AI 编排时读取角色清单。
     *
     * @param roomId 聊天室主键
     * @return 有效成员列表，未做权限校验，由调用方自行决定是否需要鉴权
     */
    public List<RoomMember> getRoomMembers(UUID roomId) {
        return roomMemberRepository.findActiveMembersByRoomId(roomId);
    }

    /**
     * 判断某用户是否是某聊天室的有效成员。
     * 调用方：WebSocket 连接鉴权、消息发送前的快速校验、权限拦截器。
     *
     * @param roomId 聊天室主键
     * @param userId 待校验用户主键
     * @return true 表示该用户当前是有效成员
     */
    public boolean isRoomMember(UUID roomId, UUID userId) {
        return roomMemberRepository.isMember(roomId, userId);
    }

    /**
     * 列出某用户加入的所有有效聊天室。
     * 调用方：「我的聊天室」侧边栏/列表页。
     *
     * @param userId 用户主键
     * @return 该用户作为有效成员的 RoomMember 列表（含关联 Room 实体）
     */
    public List<RoomMember> getUserRooms(UUID userId) {
        return roomMemberRepository.findActiveRoomsByUserId(userId);
    }

    /**
     * 邀请一个用户加入聊天室，校验邀请人必须是当前成员、被邀请人不能已在房间内。
     * 整个流程在一个事务内完成，避免出现「半邀请」状态。
     *
     * @param roomId    目标聊天室
     * @param inviterId 邀请人用户 ID（必须已是有效成员）
     * @param keyword   被邀请人的用户名或邮箱（任一匹配即可）
     * @return 新建的成员关系实体
     * @throws IllegalArgumentException 聊天室/用户/邀请人不存在，或被邀请人已在房间内
     */
    @Transactional
    public RoomMember inviteMember(UUID roomId, UUID inviterId, String keyword) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("聊天室不存在"));

        User inviter = userRepository.findById(inviterId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (!roomMemberRepository.isMember(roomId, inviterId)) {
            throw new IllegalArgumentException("你没有权限邀请成员");
        }

        User targetUser = userRepository.findByUsernameOrEmail(keyword)
                .orElseThrow(() -> new IllegalArgumentException("未找到该用户，请检查用户名或邮箱"));

        if (roomMemberRepository.isMember(roomId, targetUser.getId())) {
            throw new IllegalArgumentException("该用户已在聊天室中");
        }

        RoomMember member = RoomMember.builder()
                .room(room)
                .user(targetUser)
                .invitedBy(inviter)
                .role("member")
                .status("active")
                .build();

        return roomMemberRepository.save(member);
    }

    /**
     * 创建聊天室后将所有者补登为成员行（role=owner）。
     * 幂等：若所有者已是成员则直接返回，避免重复插入触发唯一约束。
     * 由 RoomService 在新建房间事务中调用，保证房间与 owner 成员行一起落库。
     *
     * @param room  已持久化的 Room 实体
     * @param owner 聊天室所有者 User 实体
     */
    @Transactional
    public void addOwnerAsMember(Room room, User owner) {
        if (!roomMemberRepository.isMember(room.getId(), owner.getId())) {
            RoomMember ownerMember = RoomMember.builder()
                    .room(room)
                    .user(owner)
                    .role("owner")
                    .status("active")
                    .build();
            roomMemberRepository.save(ownerMember);
        }
    }

    /**
     * 从聊天室中移除成员（软删除：将 status 置为 removed 而非物理删除，保留历史消息归属）。
     * 校验：执行者必须是当前成员；不能移除 owner；目标必须是当前有效成员。
     *
     * @param roomId    聊天室主键
     * @param userId    被移除的用户 ID
     * @param removerId 执行移除操作的用户 ID
     * @throws IllegalArgumentException 执行者无权限、目标不在房间内、或目标是所有者
     */
    @Transactional
    public void removeMember(UUID roomId, UUID userId, UUID removerId) {
        if (!roomMemberRepository.isMember(roomId, removerId)) {
            throw new IllegalArgumentException("你没有权限移除成员");
        }

        RoomMember member = roomMemberRepository.findActiveMember(roomId, userId)
                .orElseThrow(() -> new IllegalArgumentException("该用户不在聊天室中"));

        if ("owner".equals(member.getRole())) {
            throw new IllegalArgumentException("不能移除聊天室所有者");
        }

        member.setStatus("removed");
        roomMemberRepository.save(member);
    }
}
