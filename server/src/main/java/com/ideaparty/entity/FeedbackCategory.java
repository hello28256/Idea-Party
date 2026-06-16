package com.ideaparty.entity;

/**
 * 反馈原因分类：仅当 FeedbackType = DISLIKE 时使用。
 * label 字段是面向前端展示的中文标签。
 */
public enum FeedbackCategory {
    IRRELEVANT("答非所问"),
    INACCURATE("事实不准"),
    UNSAFE("不安全/不当"),
    STYLE_BAD("风格差"),
    OTHER("其他");

    private final String label;

    FeedbackCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
