package com.ideaparty.repository;

import com.ideaparty.entity.MessageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 消息事件（隐式反馈）的数据访问层。
 * 配合 {@link com.ideaparty.service.MessageEventService} 使用：Service 在用户「点踩/复制/重试」
 * 等隐式行为触发时调用本接口写库，并在聚合阶段按消息 ID 拉取事件序列，用于让 Moderator
 * 在编排下一轮发言时考虑「哪个角色刚才被点踩了」之类的信号。
 */
@Repository
public interface MessageEventRepository extends JpaRepository<MessageEvent, UUID> {

    /**
     * 按消息 ID 升序拉取该消息的全部隐式事件。
     * 入参 messageId 必须非空；返回的事件序列按 createdAt 升序，时间线与 UI 上消息的展示顺序一致，
     * 便于 Service 在前端做「事件流回放」或在 Moderator 编排时按时间窗口聚合。
     */
    List<MessageEvent> findByMessageIdOrderByCreatedAtAsc(String messageId);

    /**
     * 统计某条消息下指定事件类型的次数（例如：某条 AI 消息被点踩 N 次）。
     * 入参 messageId + eventType 必须同时指定；返回值是 long 而非 List，
     * 是为了避免把整张 MessageEvent 行拉回内存（事件 payload 可能包含较大的 metadata JSON），
     * 让数据库在索引层直接出 count，提升 Moderator 实时编排时的聚合性能。
     */
    long countByMessageIdAndEventType(String messageId, MessageEvent.EventType eventType);
}
