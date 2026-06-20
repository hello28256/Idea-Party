package com.ideaparty.controller;

import com.ideaparty.dto.RoomMemberResponse;
import com.ideaparty.service.RoomMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 聊天室成员管理控制器。
 * 负责成员列表查看、邀请新成员加入聊天室的 HTTP 入口；
 * 所有接口都受 Spring Security 保护（Authentication 由 JWT 过滤器填充），
 * 并把鉴权后的用户上下文委托给 {@link RoomMemberService} 处理业务规则。
 */
@RestController
@RequestMapping("/api/rooms/{roomId}/members")
@RequiredArgsConstructor
public class RoomMemberController {

    // 业务实现委托给 service 层：controller 只做参数解析、权限校验与响应装配，保持薄控制器风格。
    private final RoomMemberService roomMemberService;

    /**
     * 查询指定聊天室的成员列表。
     * 调用方：前端成员管理面板 / 邀请候选列表。
     * 契约：要求请求者本人必须是该房间成员（否则 403），
     * 返回 DTO 列表以避免直接暴露 RoomMember 实体字段。
     */
    @GetMapping
    public ResponseEntity<?> getRoomMembers(
            Authentication auth,
            @PathVariable UUID roomId) {
        // 防御性兜底：理论上 @PathVariable 非空，但显式校验可在路径缺失时返回更友好的中文提示。
        if (roomId == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "聊天室 ID 不能为空"));
        }
        // auth.getName() 在本项目 JWT 过滤器中存放的就是 userId 字符串（详见 SecurityConfig）。
        UUID userId = UUID.fromString(auth.getName());
        // 业务规则：仅房间成员可见名单，避免陌生人枚举成员信息；授权检查放在 controller 以尽早失败。
        if (!roomMemberService.isRoomMember(roomId, userId)) {
            return ResponseEntity.status(403).body(java.util.Map.of("message", "你不是该聊天室成员，无法查看成员列表"));
        }
        // 用 DTO 构造而非直接返回实体，隔离持久化模型与 API 契约，防止内部字段意外泄露。
        List<RoomMemberResponse> members = roomMemberService.getRoomMembers(roomId).stream()
                .map(RoomMemberResponse::new)
                .toList();
        return ResponseEntity.ok(members);
    }

    /**
     * 邀请新成员加入聊天室（按角色关键词匹配）。
     * 调用方：前端邀请弹窗，传入角色 keyword；service 负责查找角色并写入成员表。
     * 副作用：写入 RoomMember 持久化记录；失败时（角色不存在/已加入/无权限）抛 IllegalArgumentException。
     */
    @PostMapping("/invite")
    public ResponseEntity<?> inviteMember(
            Authentication auth,
            @PathVariable UUID roomId,
            @RequestBody InviteMemberRequest request) {
        // 与 GET 同样的兜底：路径变量缺失时给出明确错误，避免被框架转成 500。
        if (roomId == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "聊天室 ID 不能为空"));
        }
        UUID inviterId = UUID.fromString(auth.getName());
        try {
            // 由 service 校验邀请权限（房间所有者/已存在成员）与角色有效性，集中处理避免规则散落。
            var member = roomMemberService.inviteMember(roomId, inviterId, request.keyword());
            return ResponseEntity.ok(new RoomMemberResponse(member));
        } catch (IllegalArgumentException e) {
            // service 用 IllegalArgumentException 表达"业务校验失败"语义；统一映射为 400 + 中文 message。
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }
}

/**
 * 邀请请求的最小入参载体：仅含一个角色关键词 keyword。
 * 用 Java 17 record 表达不可变请求体，避免引入 Lombok 且自动获得构造/getter。
 * 调用方：{@link RoomMemberController#inviteMember}。
 */
record InviteMemberRequest(String keyword) {}
