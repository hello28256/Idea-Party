package com.ideaparty.dto;

import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.MessageFeedback;

import java.time.Instant;

/**
 * 反馈数据传输对象（DTO），用于把 {@link MessageFeedback} 实体序列化后返回给前端。
 * 之所以与实体分离：实体暴露 JPA/内部字段会带来耦合风险，而 DTO 决定哪些字段可见、字段类型（id 转为 String），
 * 配合 Jackson 在 controller 层做返回，避免内部模型泄漏到 API 契约。
 */
public class FeedbackResponse {

    /** 反馈主键，序列化为字符串避免前端处理 Long 的精度问题（JS Number 64 位不安全）。 */
    private String id;
    /** 关联消息 ID：前端用它把反馈挂回消息列表中的具体气泡。 */
    private String messageId;
    /** 反馈类型（点赞 / 点踩等），由 {@link FeedbackType} 枚举约束。 */
    private FeedbackType type;
    /** 反馈分类（质量 / 合规 / 其他），由 {@link FeedbackCategory} 枚举约束；用于后续做数据分析与质量分桶。 */
    private FeedbackCategory category;
    /** 用户填写的可选文字评论；可能为 null，前端需做空值兜底渲染。 */
    private String comment;
    /** 创建时间，前端用于排序与列表展示；遵循 Instant UTC 序列化。 */
    private Instant createdAt;
    /** 最近更新时间；与 createdAt 相等表示从未被编辑，便于前端判断是否显示"已编辑"标记。 */
    private Instant updatedAt;

    /** Jackson 反序列化需要的无参构造器；不直接被业务代码调用。 */
    public FeedbackResponse() {}

    /**
     * 实体到 DTO 的工厂方法，由 Service / Controller 调用，避免散落的手动拷贝。
     * 入参要求：{@code fb} 必须为已持久化、id/message 关联已加载的实体，否则会触发 NPE。
     * @param fb 持久层取出的反馈实体
     * @return 仅包含对外可见字段的响应 DTO
     */
    public static FeedbackResponse fromEntity(MessageFeedback fb) {
        FeedbackResponse r = new FeedbackResponse();
        r.setId(fb.getId().toString());
        r.setMessageId(fb.getMessage().getId());
        r.setType(fb.getType());
        r.setCategory(fb.getCategory());
        r.setComment(fb.getComment());
        r.setCreatedAt(fb.getCreatedAt());
        r.setUpdatedAt(fb.getUpdatedAt());
        return r;
    }

    /** 反馈主键读取；前端 GET 反馈列表/详情时使用。 */
    public String getId() { return id; }
    /** 反馈主键写入；当前未在 controller 直接调用，预留给内部转换/测试。 */
    public void setId(String id) { this.id = id; }
    /** 关联消息 ID 读取；前端按消息 ID 关联气泡展示反馈状态。 */
    public String getMessageId() { return messageId; }
    /** 关联消息 ID 写入；为 {@link #fromEntity(MessageFeedback)} 工厂方法所用。 */
    public void setMessageId(String messageId) { this.messageId = messageId; }
    /** 反馈类型读取；前端依据它切换点赞/点踩 UI 状态。 */
    public FeedbackType getType() { return type; }
    /** 反馈类型写入；与 {@link #fromEntity(MessageFeedback)} 配套使用。 */
    public void setType(FeedbackType type) { this.type = type; }
    /** 反馈分类读取；用于后台统计与质量分析。 */
    public FeedbackCategory getCategory() { return category; }
    /** 反馈分类写入；与 {@link #fromEntity(MessageFeedback)} 配套使用。 */
    public void setCategory(FeedbackCategory category) { this.category = category; }
    /** 评论内容读取；可能为 null，前端需做空值兜底。 */
    public String getComment() { return comment; }
    /** 评论内容写入；为工厂方法赋值使用。 */
    public void setComment(String comment) { this.comment = comment; }
    /** 创建时间读取；按 UTC 序列化输出。 */
    public Instant getCreatedAt() { return createdAt; }
    /** 创建时间写入；为工厂方法赋值使用。 */
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    /** 最近更新时间读取；与 createdAt 比对判断是否被编辑。 */
    public Instant getUpdatedAt() { return updatedAt; }
    /** 最近更新时间写入；为工厂方法赋值使用。 */
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
