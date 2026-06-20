package com.ideaparty.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * 聊天室模式更新请求 DTO。
 * 用于 PATCH 接口（例如 RoomController.updateRoomMode）只增量修改聊天室的"模式"相关字段，
 * 避免 PUT 全量更新时把其它字段（如名称、角色列表）也覆盖掉。两个字段都是可选的：
 * 传入 null 表示不改该字段，仅校验非空项。
 */
public class UpdateRoomModeRequest {

    /**
     * 聊天室发言模式枚举值（字符串形式）。
     * 取值固定为 "dialogue"（自由对话）或 "discussion"（圆桌讨论，由 Moderator 编排发言顺序），
     * 由 @Pattern 在反序列化阶段直接拦截非法值，提前返回 400，避免脏数据落库。
     */
    @Pattern(regexp = "dialogue|discussion", message = "chatMode must be 'dialogue' or 'discussion'")
    private String chatMode;

    /**
     * 圆桌讨论的最大轮次（仅在 chatMode = "discussion" 时生效）。
     * 下限 1 防止轮次为 0 导致 Moderator 立即结束；上限 20 是经验值，避免用户把单次会话
     * 拉得过长导致 LLM 调用成本与响应时延失控。null 表示本次不修改。
     */
    @Min(value = 1, message = "maxDiscussionRounds must be at least 1")
    @Max(value = 20, message = "maxDiscussionRounds must be at most 20")
    private Integer maxDiscussionRounds;

    /**
     * 读取当前请求中的聊天模式。
     * 由 Controller 在校验通过后读取并交给 Service 层做差异更新（避免覆盖未传字段）。
     */
    public String getChatMode() { return chatMode; }

    /**
     * 设置请求中的聊天模式。
     * 主要由 Jackson 反序列化填充，Controller 不直接调用。
     */
    public void setChatMode(String chatMode) { this.chatMode = chatMode; }

    /**
     * 读取当前请求中的讨论最大轮次。
     * 仅在 Service 处理 "discussion" 模式时使用；"dialogue" 模式下该值被忽略。
     */
    public Integer getMaxDiscussionRounds() { return maxDiscussionRounds; }

    /**
     * 设置请求中的讨论最大轮次。
     * 主要由 Jackson 反序列化填充，校验由 @Min/@Max 在绑定阶段完成。
     */
    public void setMaxDiscussionRounds(Integer maxDiscussionRounds) { this.maxDiscussionRounds = maxDiscussionRounds; }
}
