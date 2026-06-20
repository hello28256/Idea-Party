package com.ideaparty.dto;

import com.ideaparty.entity.MessageFeedback;

import java.time.LocalDateTime;

/**
 * 管理员反馈详情 DTO，在列表项基础上展开被举报消息的完整内容、所属聊天室与角色上下文。
 * 与 AdminFeedbackListItem 配合：列表页用列表项省带宽，详情页用本类呈现整段消息与上下文。
 * 由 AdminFeedbackService 在管理员点击某条反馈时按需组装，避免一次性把全部详情塞进列表响应。
 */
public class AdminFeedbackDetail extends AdminFeedbackListItem {

    /** 被举报 AI 消息的完整正文（列表只展示预览 80 字，详情需要全文以便管理员判断违规）。 */
    private String messageContent;
    /** 被举报消息本身在聊天室的生成时间，用于在时间线上定位它在对话中的位置。 */
    private LocalDateTime messageCreatedAt;
    /** 消息所属聊天室的 ID，前端详情页可直接跳转或深链打开该房间。 */
    private String roomId;
    /** 聊天室名称，管理员可能同时处理多个房间的反馈，名称可减少反复切换房间核对的成本。 */
    private String roomName;
    /** 触发该消息的 AI 角色 ID；若消息来自用户则为空，因此允许为 null。 */
    private String characterId;
    /** AI 角色名称，仅当角色存在时有值，UI 据此决定是否渲染「来源角色」一栏。 */
    private String characterName;
    /** 触发该 AI 回复的最近一条用户消息原文；用于管理员理解上下文，可能为 null（用户首条发言时没有前置 prompt）。 */
    private String userPrompt;
    /** 对应 userPrompt 的发送时间，与 userPrompt 配套出现以便在时间线上对齐。 */
    private LocalDateTime userPromptAt;

    /** Jackson 反序列化所需的无参构造；保留 public 以兼容框架默认实例化路径。 */
    public AdminFeedbackDetail() {}

    /**
     * 从 MessageFeedback 实体装配详情 DTO：先拷贝列表项通用字段，再追加详情专有字段。
     * 输入约束：fb 必须为已加载 message/room/user 关联的实体，否则懒加载会抛异常；character 允许为空。
     * 副作用：纯函数，不修改入参实体；返回新实例。
     */
    public static AdminFeedbackDetail fromEntity(MessageFeedback fb) {
        AdminFeedbackDetail dto = new AdminFeedbackDetail();
        // 拷贝列表项字段
        dto.setId(fb.getId().toString());
        dto.setMessageId(fb.getMessage().getId());
        String content = fb.getMessage().getContent();
        dto.setMessagePreview(content != null && content.length() > 80
                ? content.substring(0, 80) + "..."
                : content);
        dto.setType(fb.getType());
        dto.setCategory(fb.getCategory());
        dto.setComment(fb.getComment());
        dto.setUserId(fb.getUser().getId().toString());
        dto.setUsername(fb.getUser().getUsername());
        dto.setDisplayName(fb.getUser().getDisplayName());
        dto.setCreatedAt(fb.getCreatedAt());

        // 仅详情需要的字段
        dto.setMessageContent(content);
        dto.setMessageCreatedAt(fb.getMessage().getCreatedAt());
        dto.setRoomId(fb.getMessage().getRoom().getId().toString());
        dto.setRoomName(fb.getMessage().getRoom().getName());
        if (fb.getMessage().getCharacter() != null) {
            dto.setCharacterId(fb.getMessage().getCharacter().getId().toString());
            dto.setCharacterName(fb.getMessage().getCharacter().getName());
        }
        return dto;
    }

    /** 返回被举报消息全文，供管理员审核；调用方为详情接口序列化。 */
    public String getMessageContent() { return messageContent; }
    /** 写入被举报消息全文；通常仅由 fromEntity 调用，避免外部直接修改。 */
    public void setMessageContent(String messageContent) { this.messageContent = messageContent; }
    /** 返回被举报消息的生成时间；管理员按时间线核对对话上下文。 */
    public LocalDateTime getMessageCreatedAt() { return messageCreatedAt; }
    /** 写入被举报消息的生成时间，由 fromEntity 从实体拷贝。 */
    public void setMessageCreatedAt(LocalDateTime messageCreatedAt) { this.messageCreatedAt = messageCreatedAt; }
    /** 返回最近一条用户 prompt 原文，用于呈现 AI 回复的前置上下文。 */
    public String getUserPrompt() { return userPrompt; }
    /** 写入最近一条用户 prompt 原文，由 Service 在查询相邻消息时填充。 */
    public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }
    /** 返回 userPrompt 对应的发送时间，与 userPrompt 共同组成上下文快照。 */
    public LocalDateTime getUserPromptAt() { return userPromptAt; }
    /** 写入 userPrompt 对应的发送时间，确保上下文按时间顺序展示。 */
    public void setUserPromptAt(LocalDateTime userPromptAt) { this.userPromptAt = userPromptAt; }
    /** 返回被举报消息所属聊天室 ID，前端用于跳转或深链。 */
    public String getRoomId() { return roomId; }
    /** 写入被举报消息所属聊天室 ID，由 fromEntity 从实体拷贝。 */
    public void setRoomId(String roomId) { this.roomId = roomId; }
    /** 返回聊天室名称，便于管理员在不跳转的情况下识别来源房间。 */
    public String getRoomName() { return roomName; }
    /** 写入聊天室名称，由 fromEntity 从实体拷贝。 */
    public void setRoomName(String roomName) { this.roomName = roomName; }
    /** 返回 AI 角色 ID；消息若来自用户则为 null，调用方需做空判断。 */
    public String getCharacterId() { return characterId; }
    /** 写入 AI 角色 ID；仅在角色存在时由 fromEntity 调用。 */
    public void setCharacterId(String characterId) { this.characterId = characterId; }
    /** 返回 AI 角色名称，用于详情页「来源角色」展示。 */
    public String getCharacterName() { return characterName; }
    /** 写入 AI 角色名称，与 characterId 配套由 fromEntity 在角色存在时填充。 */
    public void setCharacterName(String characterName) { this.characterName = characterName; }
}
