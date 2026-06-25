package com.ideaparty.repository;

import com.ideaparty.entity.UserScenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户私有场景（UserScenario）的持久化层。
 *
 * 与 {@link CharacterRepository} 同构，但查询语义更严格：
 * 所有按 id 查找的方法都强制携带 ownerId，避免"用 A 的 id 改/删 B 的场景"越权。
 * Service 层直接复用本接口的 findByIdAndOwnerId / existsByIdAndOwnerId，
 * Controller 不必再做 owner 校验。
 */
@Repository
public interface UserScenarioRepository extends JpaRepository<UserScenario, UUID> {

    /**
     * 列出某用户全部私有场景，按更新时间倒序（最近编辑在前）。
     * 用于"我的场景"列表展示。
     */
    List<UserScenario> findByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

    /**
     * 按 id + ownerId 联合定位单条场景。
     * 防止越权访问他人私有场景，是 update / delete / detail 接口的安全边界。
     */
    Optional<UserScenario> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * 同 owner + 同 title 的场景查重接口。
     * 用于 create 路径去重：避免并发点击"保存"创建出多条同名场景。
     * 命中时直接返回已存在那条，让 create 接口天然幂等。
     */
    Optional<UserScenario> findFirstByOwnerIdAndTitle(UUID ownerId, String title);

    /**
     * 轻量存在性判断（id + ownerId），用于删除前的权限校验。
     */
    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);
}
