package com.ideaparty.dto;

import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitFeedbackRequest {

    @NotNull(message = "Feedback type is required")
    private FeedbackType type;

    /** DISLIKE 时必填，LIKE 时忽略 */
    private FeedbackCategory category;

    @Size(max = 1000, message = "Comment must not exceed 1000 characters")
    private String comment;
}
