package com.ideaparty.dto;

import com.ideaparty.entity.RoomMember;
import com.ideaparty.util.ImageUrlResolver;
import lombok.Getter;

import java.time.Instant;

/**
 * 聊天室成员响应的 DTO。
 * <p>
 * 用于把 {@link RoomMember} 实体连同关联的 User 字段拍平后暴露给前端，
 * 避免直接序列化实体（实体带懒加载关联、Hibernate Proxy，跨层传输会触发 LazyInitializationException）。
 * 字段全部 final，由构造方法一次性注入；不暴露 setter，前端拿到的就是不可变快照。
 */
@Getter
public class RoomMemberResponse {
    /** 成员对应用户的 ID，序列化为字符串避免前端精度丢失，与房间/消息 ID 体系保持一致。 */
    private final String userId;
    /** 用户登录名（唯一），前端用于 @ 提及或跳转个人主页。 */
    private final String username;
    /** 用户展示名（可重复、可含中文/emoji），用于群聊列表/头像旁的展示。 */
    private final String displayName;
    /** 用户头像 URL（已转完整 OSS URL），可能为 null（前端需降级到默认头像）。 */
    private final String avatarUrl;
    /** 成员在房间内的角色（如 OWNER / MEMBER），决定是否可踢人、改设置。 */
    private final String role;
    /** 成员加入状态（如 ACTIVE / MUTED），前端据此过滤发言或显示标记。 */
    private final String status;
    /** 加入时间，便于前端按"最近加入"排序或显示入群时间。 */
    private final Instant joinedAt;

    /**
     * 从持久化实体构造响应 DTO。
     *
     * @param member   聊天室成员实体，必须已加载关联的 User（避免在 Controller 层触发懒加载异常）。
     * @param resolver 把 avatarUrl(相对 key 或外网)转成完整 OSS URL。
     */
    public RoomMemberResponse(RoomMember member, ImageUrlResolver resolver) {
        this.userId = member.getUser().getId().toString();
        this.username = member.getUser().getUsername();
        this.displayName = member.getUser().getDisplayName();
        this.avatarUrl = resolver.resolve(member.getUser().getAvatarUrl());
        this.role = member.getRole();
        this.status = member.getStatus();
        this.joinedAt = member.getJoinedAt();
    }

    /**
     * 兼容旧调用方(无 resolver)的便捷构造,内部不转 URL,调用方负责后续处理。
     * 业务代码请改用 {@link #RoomMemberResponse(RoomMember, ImageUrlResolver)}。
     */
    @Deprecated
    public RoomMemberResponse(RoomMember member) {
        // 调用方未传 resolver 时跳过 URL 转换,直接用 DB 原始字符串。
        // 这条路径只可能在遗留代码中触发,新代码请用 (RoomMember, ImageUrlResolver) 构造器。
        this.userId = member.getUser().getId().toString();
        this.username = member.getUser().getUsername();
        this.displayName = member.getUser().getDisplayName();
        this.avatarUrl = member.getUser().getAvatarUrl();
        this.role = member.getRole();
        this.status = member.getStatus();
        this.joinedAt = member.getJoinedAt();
    }
}
