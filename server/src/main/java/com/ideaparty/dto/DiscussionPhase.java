package com.ideaparty.dto;

/**
 * 讨论室的有限状态机枚举，用于描述一次群聊对话在某一时刻所处的阶段。
 * 与房间会话状态机配合：Moderator Agent 根据当前阶段决定下一步动作
 * （例如 IDLE 时不调度、SPEAKING 时暂停接收新消息、PAUSED 时只读不调度）。
 */
public enum DiscussionPhase {
    /** 初始 / 已结束状态：当前没有进行中的讨论，房间可被复用或关闭。 */
    IDLE,           // 无讨论
    /** Moderator Agent 正在分析上下文并决定下一位发言角色，期间用户消息进入排队。 */
    MODERATING,    // Moderator 分析中
    /** 被选中的 AI 角色正在生成并推送流式回复，前端展示其消息气泡。 */
    SPEAKING,       // AI 角色发言中
    /** 一次轮次结束，等待用户输入下一条消息以触发下一轮 Moderator 调度。 */
    WAITING_FOR_USER, // 等待用户输入
    /** 用户主动暂停：保留上下文但不调度 Moderator / 角色发言，仅响应恢复操作。 */
    PAUSED          // 用户手动暂停
}
