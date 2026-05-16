package com.ideaparty.dto;

import com.ideaparty.entity.RoomMember;
import lombok.Getter;

import java.time.Instant;

@Getter
public class RoomMemberResponse {
    private final String userId;
    private final String username;
    private final String displayName;
    private final String avatarUrl;
    private final String role;
    private final String status;
    private final Instant joinedAt;

    public RoomMemberResponse(RoomMember member) {
        this.userId = member.getUser().getId().toString();
        this.username = member.getUser().getUsername();
        this.displayName = member.getUser().getDisplayName();
        this.avatarUrl = member.getUser().getAvatarUrl();
        this.role = member.getRole();
        this.status = member.getStatus();
        this.joinedAt = member.getJoinedAt();
    }
}
