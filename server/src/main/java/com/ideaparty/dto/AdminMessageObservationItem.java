package com.ideaparty.dto;

import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 管理员消息概览的单行数据。表示一条 AI 消息，
 * 与是否有人评分无关。当查看后台的用户尚未对该消息评分时，feedbackStatus 为 null。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminMessageObservationItem {

    /** 管理员列表中正在查看的 AI 消息行的稳定标识符。 */
    private String messageId;
    /** 该 AI 消息所属聊天室 ID；管理员 UI 用它深链回聊天室。 */
    private String roomId;
    /** 与消息一同展示的、人类可读的聊天室名称，避免管理员手动根据 ID 解析。 */
    private String roomName;
    /** 生成该消息的 AI 角色 ID；下钻筛选时需要用到。 */
    private String characterId;
    /** AI 角色展示名；直接显示在管理员表格中以便快速浏览。 */
    private String characterName;
    /** 反馈作者的用户 ID；当尚无人评分时为 null。 */
    private String userId;          // 反馈作者（未评时为 null）
    /** 反馈作者的登录名；与 userId 配合用于展示，未评时为 null。 */
    private String username;
    /** 反馈作者的首选展示名；未评或未设置时为 null。 */
    private String displayName;

    /** AI 消息正文的截断版本，让管理员无需打开详情即可预览内容。 */
    private String messagePreview;
    /** AI 消息入库的时间戳；用于管理员表格按时间倒序排序。 */
    private LocalDateTime messageCreatedAt;
    /** COMPLETE / EMPTY / FAILED —— 历史数据行时为 null。 */
    private String streamStatus;

    /** 触发该 AI 回复的最近一条用户消息，可能为 null。 */
    private String userPrompt;
    /** 触发该回复的用户消息的创建时间；便于管理员将延迟与请求时间相关联。 */
    private LocalDateTime userPromptAt;
    /** 触发该 AI 回复的用户 ID；当 prompt 上下文缺失时为 null。 */
    private String promptUserId;
    /** 触发用户的登录名；展示给管理员以了解上下文（谁问了什么）。 */
    private String promptUsername;
    /** 触发用户的展示名；在管理员 UI 中存在时优先于 username 显示。 */
    private String promptDisplayName;

    // 汇总所有用户的反馈
    /** 来自所有用户对该消息的反馈总条数；用于计算点赞 / 点踩比例的主要分母。 */
    private int feedbackCount;
    /** 汇总自所有用户的点赞数；用于一眼可见的质量信号。 */
    private int likeCount;
    /** 汇总自所有用户的点踩数；高数值标记出需要分诊处理的问题消息。 */
    private int dislikeCount;
    /** 该消息最近一次反馈的时间；用于优先处理新近被标记的内容。 */
    private Instant lastFeedbackAt;

    /** 反馈状态："RATED"（当前用户已评）、"UNRATED"（无人评价）、"AGGREGATED"（其他用户评过但当前用户未评）。 */
    private String status;

    /** 若当前用户已评价则为详情，否则为 null。 */
    /** 当前查看的管理员用户给出的 LIKE / DISLIKE 选择；当状态为 UNRATED 或 AGGREGATED 时为 null。 */
    private FeedbackType feedbackType;
    /** 查看用户选择的可选分类桶（如语气、准确性）；未分类时为 null。 */
    private FeedbackCategory feedbackCategory;
    /** 查看用户留下的自由文本评论；未填写时为 null。 */
    private String feedbackComment;
    /** 查看用户提交其自身反馈的时间；该用户尚未评价时为 null。 */
    private Instant userFeedbackAt;
}
