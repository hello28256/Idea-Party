package com.ideaparty.entity;

/**
 * 反馈原因分类：仅当 FeedbackType = DISLIKE 时使用。
 * label 字段是面向前端展示的中文标签。
 *
 * 为什么存在：用户点"踩"时仅记录 "DISLIKE" 太粗糙，无法定位问题（是答非所问、事实错误还是风格差？），
 * 运营/算法需要可统计的下钻维度来观察模型弱点，因此用枚举收敛可选原因，避免脏数据污染分析。
 *
 * 与谁配合：作为 {@link FeedbackMessage#category}（或同名字段）的取值来源；
 * 前端在"点踩"弹窗里直接遍历本枚举渲染选项，后端落库时校验取值合法性。
 */
public enum FeedbackCategory {
    /** 答非所问：模型回复与用户提问/上下文不匹配，是最常见的"无用"反馈。 */
    IRRELEVANT("答非所问"),
    /** 事实不准：模型给出错误事实或编造信息（hallucination），对可靠性场景尤为关键。 */
    INACCURATE("事实不准"),
    /** 不安全/不当：涉及违规、敏感、政治、暴力、色情等内容，需要安全团队关注。 */
    UNSAFE("不安全/不当"),
    /** 风格差：内容方向正确但语气、长度、格式不讨喜，多用于对话体验调优。 */
    STYLE_BAD("风格差"),
    /** 其他：兜底分类，避免用户被迫选一个不准确的标签，保证反馈通道畅通。 */
    OTHER("其他");

    /**
     * 面向前端的展示文案。
     * 用 final 而非 @Getter(Lazy) 因为枚举值不可变，构建期一次性绑定即可，
     * 这样 Jackson 序列化时不会反射调用 getter，性能更稳。
     */
    private final String label;

    /**
     * 唯一构造器：在枚举常量声明时强制传入中文标签，避免出现"枚举存在但无 label"的非法状态。
     *
     * @param label 前端展示用中文文案，不允许为空（由枚举声明侧的字面量保证）
     */
    FeedbackCategory(String label) {
        this.label = label;
    }

    /**
     * 供前端直接消费的展示文案。
     * 调用方：FeedbackController 列表接口 / 前端点踩弹窗渲染。
     *
     * @return 与枚举常量绑定好的中文标签，整个生命周期内不可变
     */
    public String getLabel() {
        return label;
    }
}
