package com.ideaparty.controller;

import com.ideaparty.dto.RoomMemberResponse;
import com.ideaparty.service.RoomMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms/{roomId}/members")
@RequiredArgsConstructor
public class RoomMemberController {

    private final RoomMemberService roomMemberService;

    @GetMapping
    public ResponseEntity<?> getRoomMembers(
            Authentication auth,
            @PathVariable UUID roomId) {
        if (roomId == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "聊天室 ID 不能为空"));
        }
        UUID userId = UUID.fromString(auth.getName());
        if (!roomMemberService.isRoomMember(roomId, userId)) {
            return ResponseEntity.status(403).body(java.util.Map.of("message", "你不是该聊天室成员，无法查看成员列表"));
        }
        List<RoomMemberResponse> members = roomMemberService.getRoomMembers(roomId).stream()
                .map(RoomMemberResponse::new)
                .toList();
        return ResponseEntity.ok(members);
    }

    @PostMapping("/invite")
    public ResponseEntity<?> inviteMember(
            Authentication auth,
            @PathVariable UUID roomId,
            @RequestBody InviteMemberRequest request) {
        if (roomId == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "聊天室 ID 不能为空"));
        }
        UUID inviterId = UUID.fromString(auth.getName());
        try {
            var member = roomMemberService.inviteMember(roomId, inviterId, request.keyword());
            return ResponseEntity.ok(new RoomMemberResponse(member));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }
}

record InviteMemberRequest(String keyword) {}
