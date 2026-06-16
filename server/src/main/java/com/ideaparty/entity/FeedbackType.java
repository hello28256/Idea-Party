package com.ideaparty.entity;

/**
 * 评分类型：用户对 AI 消息的反馈方向。
 * LIKE = 满意，DISLIKE = 不满意（必须附带 FeedbackCategory）。
 */
public enum FeedbackType {
    LIKE,
    DISLIKE
}
