package com.ideaparty.repository;

import com.ideaparty.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
// 聊天室消息的数据访问层。聊天室加载/流式回放需要按房间取消息并附带角色信息（展示发言头像），
// 管理后台需要按角色统计引用数与查找上下文，故在 Spring Data JPA 之上补充少量 JPQL。
public interface MessageRepository extends JpaRepository<Message, UUID> {

    // JOIN FETCH 角色：消息列表展示需要角色名称/头像，单独查会出现 N+1。
    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.character WHERE m.room.id = :roomId ORDER BY m.createdAt ASC")
    List<Message> findByRoomIdWithCharacter(@Param("roomId") UUID roomId);

    // 按创建时间升序取房间全量消息：给聊天室的「加载历史」或导出接口使用，无需分页。
    List<Message> findByRoomIdOrderByCreatedAtAsc(UUID roomId);

    // 按创建时间倒序分页取房间消息：管理后台或搜索场景下倒序展示，配合 Pageable 控制页大小避免一次性加载过多。
    Page<Message> findByRoomIdOrderByCreatedAtDesc(UUID roomId, Pageable pageable);

    /**
     * Message.id is a String (declared via @GeneratedValue UUID but field type is String).
     * JpaRepository's built-in findById accepts only the generic ID type, so we add a
     * String overload that delegates via a JPQL query. Existing callers can keep using
     * findById(UUID) when they already have a UUID; this method accepts the raw id.
     */
    @Query("SELECT m FROM Message m WHERE m.id = :id")
    Optional<Message> findByIdString(@Param("id") String id);

    // default 方法委托给 findByIdString：当调用方拿到的是 String 类型 ID（如前端路由参数）时，
    // 可直接调用 findById(String)，避免各业务点重复写 findByIdString，保持调用侧接口一致。
    default Optional<Message> findById(String id) {
        return findByIdString(id);
    }

    /**
     * The most recent USER message in the same room strictly before the given
     * (CHARACTER) message. Used to show admins "what the user asked" alongside
     * a feedback detail. Returns empty when the AI message is the first in
     * the room or no prior USER message exists.
     */
    @Query("""
        SELECT m FROM Message m
        WHERE m.room.id = :roomId
          AND m.senderType = com.ideaparty.entity.Message.SenderType.USER
          AND m.createdAt < :before
        ORDER BY m.createdAt DESC
        """)
    List<Message> findPriorUserMessages(@Param("roomId") UUID roomId,
                                        @Param("before") java.time.LocalDateTime before,
                                        org.springframework.data.domain.Pageable pageable);

    // 在删除 AI 角色前调用：返回该角色在历史消息中仍被引用的条数。
    // 用 COUNT 而非 EXISTS 是因为业务上要在删除确认弹窗里直接展示「仍有 N 条消息引用」等具体数字提示。
    @Query("SELECT COUNT(m) FROM Message m WHERE m.character.id = :characterId")
    long countByCharacterId(@Param("characterId") UUID characterId);
}
