package com.ideaparty.dto;

import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户提交反馈的请求体。
 * 用于前端「聊天反馈」弹窗收集用户对单条 AI 消息的评价，
 * 由 FeedbackController 接收并交给 FeedbackService 落库与后续分析。
 */
@Data
// Lombok：为反序列化/校验失败时 Jackson 与 Validator 提供无参构造
@NoArgsConstructor
// Lombok：一行构造所有字段，方便测试与内部代码直接构造 DTO
@AllArgsConstructor
public class SubmitFeedbackRequest {

    /**
     * 反馈类型（点赞 / 点踩），必填。
     * 决定后续是否需要 category 字段以及该反馈在后台的统计口径。
     */
    @NotNull(message = "Feedback type is required")
    private FeedbackType type;

    /** DISLIKE 时必填，LIKE 时忽略 */
    private FeedbackCategory category;

    /**
     * 用户填写的可选文字说明，上限 1000 字符以避免落库与传输压力过大。
     */
    @Size(max = 1000, message = "Comment must not exceed 1000 characters")
    private String comment;
}
