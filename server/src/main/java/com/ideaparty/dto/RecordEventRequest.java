package com.ideaparty.dto;

import com.ideaparty.entity.MessageEvent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 入参 DTO：记录一条消息级行为埋点事件。
 *
 * <p>为什么存在：前端在用户阅读/聚焦某条消息、或产生其他交互行为时上报，由后端
 * 持久化为 {@link MessageEvent} 用于后续分析与 Moderator Agent 的节奏学习。
 *
 * <p>配合：{@link MessageEvent}（实体）、{@code MessageEventController}
 * （{@code POST /api/message-events}）以及前端消息组件的埋点上报。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordEventRequest {

    /**
     * 事件类型枚举，区分 READ_COMPLETE / FOCUS / SKIP 等。
     *
     * <p>为什么必填：业务上每条记录必须能归类，是后续分析与排序的核心维度。
     */
    @NotNull(message = "event type is required")
    private MessageEvent.EventType eventType;

    /**
     * 用户在该消息上的停留时长（毫秒），可选。
     *
     * <p>为什么有它：READ_COMPLETE / FOCUS 需要量化阅读深度来训练 Moderator；
     * 其他事件类型可不传，因此用包装类型 {@link Integer} 表示可空。
     */
    /** Optional, only meaningful for READ_COMPLETE / FOCUS. */
    @PositiveOrZero
    private Integer dwellMs;

    /**
     * 扩展元数据（JSON 字符串），用于承载后续新增的细粒度上下文。
     *
     * <p>为什么限长 4000：避免单条上报体过大撑爆数据库 TEXT 字段；同时为后续
     * schema-less 扩展保留空间，前端可塞 UA、滚动位置、来源等附加信息。
     */
    @Size(max = 4000)
    private String metadata;
}
