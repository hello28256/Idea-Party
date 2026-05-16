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

@Service
@RequiredArgsConstructor
public class RoomMemberService {

    private final RoomMemberRepository roomMemberRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    public List<RoomMember> getRoomMembers(UUID roomId) {
        return roomMemberRepository.findActiveMembersByRoomId(roomId);
    }

    public boolean isRoomMember(UUID roomId, UUID userId) {
        return roomMemberRepository.isMember(roomId, userId);
    }

    public List<RoomMember> getUserRooms(UUID userId) {
        return roomMemberRepository.findActiveRoomsByUserId(userId);
    }

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
