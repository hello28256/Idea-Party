package com.ideaparty.entity;

/**
 * 评分类型：用户对 AI 消息的反馈方向。
 * LIKE = 满意，DISLIKE = 不满意（必须附带 FeedbackCategory）。
 */
public enum FeedbackType {
    /** 用户对 AI 回复表示满意，正向反馈，用于统计模型质量与用户体验满意度。 */
    LIKE,
    /** 用户对 AI 回复表示不满意，负向反馈；按业务规约必须与 FeedbackCategory 共同记录原因。 */
    DISLIKE
}
