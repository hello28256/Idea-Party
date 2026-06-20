package com.ideaparty.repository;

import com.ideaparty.entity.MessageObservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * AI 消息反馈观察表（message_observations）的 JPA 仓储。
 * 一行 = 一条 AI 消息的反馈聚合快照（含 like / dislike / 最近反馈时间等），由
 * Moderator Agent 调度链路或后台聚合任务在 AI 消息落库后 upsert 写入。
 * 提供 Specification 分页查询能力，供管理后台「AI 消息反馈观测」页按角色/聊天室/反馈量等维度过滤。
 */
@Repository
public interface MessageObservationRepository
        extends JpaRepository<MessageObservation, String>, JpaSpecificationExecutor<MessageObservation> {

    /**
     * 按动态 Specification 条件分页查询 AI 消息观察记录。
     * 入参：spec 为组装好的过滤条件（roomId / characterId / feedbackCount 区间等），pageable 控制分页与排序。
     * 副作用：无；纯查询。
     * 返回值：分页包装的 MessageObservation 列表（含 totalElements / totalPages），供后台表格直接渲染。
     * 调用方：AdminObservationService 等后台观测服务，避免在 Service 层硬编码多 if-else 查询方法。
     */
    Page<MessageObservation> findAll(Specification<MessageObservation> spec, Pageable pageable);
}
