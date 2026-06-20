package com.ideaparty.controller;

import com.ideaparty.dto.CreateRoomRequest;
import com.ideaparty.dto.RoomResponse;
import com.ideaparty.dto.UpdateRoomModeRequest;
import com.ideaparty.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@Slf4j
/**
     * 聊天室 REST 入口。负责鉴权上下文解析、参数传递与响应码包装；
     * 鉴权（JWT 解析与用户身份）由 Spring Security 过滤器链在更上层完成，此处只拿到已认证的 Authentication。
     * 业务规则（所有权校验、删除/角色关联副作用）下沉到 RoomService，保持控制器薄、可测试。
     */
public class RoomController {

    // 由 Lombok @RequiredArgsConstructor 注入；final 保证不可变，便于单测通过构造器替换 mock。
    private final RoomService roomService;

    /**
     * 返回当前用户可见的聊天室列表。调用方为前端「我的聊天室」页；
     * 仅返回当前 userId 拥有的房间，可见性过滤在 Service.findByUserId 内完成。
     */
    @GetMapping
    public ResponseEntity<List<RoomResponse>> getUserRooms(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Getting rooms for user: {}", userId);

        List<RoomResponse> rooms = roomService.findByUserId(userId);
        return ResponseEntity.ok(rooms);
    }

    /**
     * 创建聊天室并返回 201 Created。@Valid 让 Bean Validation 在请求体绑定阶段就拒绝非法入参，
     * 避免把脏数据带进 Service/DB；初始成员与角色装配逻辑在 Service 中完成。
     */
    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(Authentication auth, @Valid @RequestBody CreateRoomRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Creating room for user: {}", userId);

        RoomResponse room = roomService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(room);
    }

    /**
     * 获取聊天室详情。注意：所有权/可见性校验统一由 Service 完成（避免控制器内重复 if 逻辑漂移），
     * 控制器只负责把 Authentication 转成 userId 透传下去。
     */
    @GetMapping("/{id}")
    public ResponseEntity<RoomResponse> getRoomById(Authentication auth, @PathVariable UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Getting room {} for user: {}", id, userId);

        RoomResponse room = roomService.findById(id);
        return ResponseEntity.ok(room);
    }

    /**
     * 删除聊天室。返回 204 No Content 是 REST 惯例：删除成功且无返回体。
     * 仅房主可删的规则由 Service.deleteIfOwner 抛异常统一处理，避免在此处写分支。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRoom(Authentication auth, @PathVariable UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Deleting room {} for user: {}", id, userId);

        roomService.deleteIfOwner(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 将一个角色加入聊天室。路径里同时携带 roomId 与 characterId（而非 body），
     * 是为了契合 REST 资源层级语义：角色是 room 的子资源，且 GET 缓存友好。
     */
    @PostMapping("/{id}/characters/{characterId}")
    public ResponseEntity<RoomResponse> addCharacterToRoom(Authentication auth, @PathVariable UUID id, @PathVariable UUID characterId) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Adding character {} to room {} by user {}", characterId, id, userId);

        RoomResponse room = roomService.addCharacterToRoom(id, characterId, userId);
        return ResponseEntity.ok(room);
    }

    /**
     * 切换聊天模式（顺序/自由）与最大讨论轮数。用 PATCH 而非 PUT，
     * 是因为 chatMode 与 maxDiscussionRounds 都是可选局部更新，无需提交完整资源。
     */
    @PatchMapping("/{id}/mode")
    public ResponseEntity<RoomResponse> updateRoomMode(Authentication auth, @PathVariable UUID id, @Valid @RequestBody UpdateRoomModeRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Updating room {} mode by user {}", id, userId);

        RoomResponse room = roomService.updateChatMode(id, userId, request.getChatMode(), request.getMaxDiscussionRounds());
        return ResponseEntity.ok(room);
    }

    /**
     * 记录用户进入聊天室（用于最近活跃时间、未读计数等）。
     * 选用 PATCH+空体而非 GET，是因为这是一个写副作用：必须走安全/幂等约束，不能被浏览器/爬虫预取触发。
     */
    @PatchMapping("/{id}/enter")
    public ResponseEntity<Void> recordEnter(Authentication auth, @PathVariable UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] Recording enter for room {} by user {}", id, userId);

        roomService.recordEnter(id, userId);
        return ResponseEntity.ok().build();
    }
}
