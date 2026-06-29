package com.ideaparty.dto;

import jakarta.validation.constraints.Size;

/**
 * 聊天室主题更新请求 DTO。
 * 用于 PATCH /api/rooms/{id}/topic 端点：只增量修改聊天室主题，避免 PUT 全量更新时把其它字段也覆盖掉。
 * topic 可空：null 或空串在 Service 中归一为 null（清空主题）；上限 500 字符与 Room.topic 列长度一致。
 */
public class UpdateRoomTopicRequest {

    /**
     * 聊天室新主题。
     * 可空（前端空 textarea 提交空串 → Service 归一为 null → DB 写入 NULL）。
     * 上限 500 字符，与 Room 实体的 topic 列长度一致。
     */
    @Size(max = 500, message = "topic must be at most 500 characters")
    private String topic;

    public String getTopic() { return topic; }

    public void setTopic(String topic) { this.topic = topic; }
}