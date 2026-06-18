package com.ideaparty.repository;

import com.ideaparty.entity.Character;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 角色（Character）的持久化层。
 * 作为 Spring Data JPA 仓储，承担用户自定义角色与平台预设角色的 CRUD；
 * 同时对外暴露按归属、按预设、按使用热度等查询能力，供 Service 层编排发言 / 推荐场景使用。
 */
@Repository
public interface CharacterRepository extends JpaRepository<Character, UUID> {

    /**
     * 查询某用户拥有的全部角色（含用户私有与用户自建的预设之外角色）。
     * 用于“我的角色”列表场景，调用方需自行处理空集合。
     */
    List<Character> findByOwnerId(UUID ownerId);

    /**
     * 查询平台预置角色（ownerId 为 NULL 的官方角色）。
     * 供新用户“开箱即用”展示，与用户自建角色解耦。
     */
    List<Character> findByIsPresetTrue();

    /**
     * 按 id + ownerId 联合定位单条角色。
     * 防止用户越权访问他人私有角色，是删除 / 更新 / 详情接口的安全边界。
     */
    Optional<Character> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * 轻量存在性判断，避免先查全字段再判断带来的不必要 IO。
     * 用在权限校验、加入聊天室前的归属校验等热路径。
     */
    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * 按使用热度倒序取前 N 个角色（推荐位用）。
     * 使用原生 SQL 而非 JPQL：因为 JPQL 不支持跨聚合列的 ORDER BY 引用别名，
     * LEFT JOIN 保留未被引用的角色（usage_count=0），避免冷启动时新角色直接消失。
     */
    @Query(value = """
        SELECT c.*, COUNT(rc.room_id) as usage_count
        FROM characters c
        LEFT JOIN room_characters rc ON c.id = rc.character_id
        GROUP BY c.id
        ORDER BY usage_count DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Character> findTopByUsageCount(int limit);
}
