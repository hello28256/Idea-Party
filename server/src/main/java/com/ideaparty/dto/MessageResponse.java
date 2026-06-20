package com.ideaparty.dto;

import com.ideaparty.entity.Message;
import java.time.LocalDateTime;

// 聊天消息的对外响应载体：把 Message 实体（含 Room/User/Character 关联）扁平化成前端可直接消费的字段，
// 避免 JPA 懒加载与实体内部结构直接暴露到 API 层；配合 Controller 返回给前端群聊视图渲染。
public class MessageResponse {

    private String id;
    private String content;
    // 枚举转字符串（USER/CHARACTER），前端按字符串分支渲染发言者气泡，保持 API 与枚举解耦。
    private String senderType;
    // 仅在 senderType=USER 时填充；CHARACTER 消息不关联 user，避免前端误判归属。
    private String userId;
    // 仅在 senderType=CHARACTER 时填充；USER 消息不发 AI 角色字段，前端按 null 隐藏头像。
    private String characterId;
    private String characterName;
    private String characterAvatar;
    private String roomId;
    private LocalDateTime createdAt;

    // Jackson 反序列化与 new + setter 链式赋值所必需；fromEntity 也通过它构造空对象再逐字段填充。
    public MessageResponse() {}

    // 实体到 DTO 的映射入口：处理 senderType 枚举→字符串、关联 ID Long→String，并在关联为 null 时跳过填充，
    // 调用方需保证 message 与其 room 关联在事务内可访问，避免懒加载在 DTO 阶段抛 LazyInitializationException。
    public static MessageResponse fromEntity(Message message) {
        MessageResponse response = new MessageResponse();
        response.setId(message.getId());
        response.setContent(message.getContent());
        response.setSenderType(message.getSenderType().name());
        response.setRoomId(message.getRoom().getId().toString());
        response.setCreatedAt(message.getCreatedAt());

        if (message.getCharacter() != null) {
            response.setCharacterId(message.getCharacter().getId().toString());
            response.setCharacterName(message.getCharacter().getName());
            response.setCharacterAvatar(message.getCharacter().getAvatarUrl());
        }

        if (message.getUser() != null) {
            response.setUserId(message.getUser().getId().toString());
        }

        return response;
    }

    // 消息唯一标识；前端用它做列表 key 与去重，前端群聊流渲染时依赖该字段做 React/Vue 节点 diff。
    public String getId() { return id; }
    // 由 fromEntity 从 Message 实体主键映射写入；外部测试或反序列化场景（如 Redis 缓存重建）也可能直接调用。
    public void setId(String id) { this.id = id; }

    // 消息正文；前端群聊气泡按 Markdown 渲染（保留换行与代码块），是用户/AI 发言的核心展示内容。
    public String getContent() { return content; }
    // 主要由 fromEntity 写入 AI 生成的回复文本；保留 setter 以支持未来编辑消息或消息体本地化场景。
    public void setContent(String content) { this.content = content; }

    // 返回 USER/CHARACTER 字符串；前端按它决定气泡左右位置、是否显示 AI 头像与重试按钮。
    public String getSenderType() { return senderType; }
    // 由 fromEntity 从枚举 name() 写入；保留 setter 便于测试桩数据手动构造。
    public void setSenderType(String senderType) { this.senderType = senderType; }

    // 仅 USER 消息填充；前端用它高亮"我"的气泡、@提及与撤回权限判断。
    public String getUserId() { return userId; }
    // 由 fromEntity 在 message.getUser() 非空时写入；CHARACTER 消息保持 null。
    public void setUserId(String userId) { this.userId = userId; }

    // 仅 CHARACTER 消息填充；前端用它定位角色卡、跳转角色详情与重新生成指定角色回复。
    public String getCharacterId() { return characterId; }
    // 由 fromEntity 在 message.getCharacter() 非空时写入；USER 消息保持 null 以避免歧义。
    public void setCharacterId(String characterId) { this.characterId = characterId; }

    // 角色展示名；冗余于 characterId 是为了前端渲染时不必再查角色表，降低列表渲染复杂度。
    public String getCharacterName() { return characterName; }
    // 由 fromEntity 从 Character.getName() 写入；与 characterId 同时存在或同时为 null。
    public void setCharacterName(String characterName) { this.characterName = characterName; }

    // 角色头像 URL；冗余存储避免前端群聊气泡逐条请求角色信息，减少 N+1 渲染开销。
    public String getCharacterAvatar() { return characterAvatar; }
    // 由 fromEntity 从 Character.getAvatarUrl() 写入；可能为 null（角色未设置头像）由前端回退默认占位图。
    public void setCharacterAvatar(String characterAvatar) { this.characterAvatar = characterAvatar; }

    // 所属聊天室 ID；前端用它在订阅 WebSocket 频道时校验消息归属，避免跨房间串台显示。
    public String getRoomId() { return roomId; }
    // 由 fromEntity 从 Message.Room.getId() 写入；非空，所有消息都必须挂在某个 Room 下。
    public void setRoomId(String roomId) { this.roomId = roomId; }

    // 消息发送时间；前端按它排序、显示时间分隔符（如"5 分钟前"）与日期分组。
    public LocalDateTime getCreatedAt() { return createdAt; }
    // 由 fromEntity 从 Message.createdAt 原样拷贝；保持 LocalDateTime 而非时间戳便于前端按本地时区直接格式化。
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
