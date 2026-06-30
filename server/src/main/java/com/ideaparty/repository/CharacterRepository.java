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
     * 全量角色按 createdAt 降序：服务层 findAll() 调用，
     * 让「我的角色库」页拿到的就是新创建的角色排最前的列表，
     * 前端再按 ownerId 过滤出当前用户的私有副本。
     */
    List<Character> findAllByOrderByCreatedAtDesc();

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
     * 同 owner + 同名的角色查重接口，限定非预设角色。
     * 用于 create 路径去重：避免"点 N 次推荐角色就建 N 条毛泽东"这类历史 bug。
     * 命中时直接返回已存在那条，让前端 clone 流程天然幂等。
     * 返回 Optional 而非 List：同名是异常状态，多条同名才是数据脏数据。
     */
    Optional<Character> findFirstByOwnerIdAndNameAndIsPresetFalse(UUID ownerId, String name);

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

    /**
     * 取全部预设角色，按 name 升序稳定输出（中文按 utf8mb4 字符序）。
     * DataLoader.seedCharactersIfMissing 按固定顺序写入 18 位历史人物，
     * 此处按 name 排序是次优解：汉字按 Unicode code point 排序，结果与 DataLoader
     * 写入顺序大致一致（哲/宗/科学/政治/文化等领域内基本保持稳定）。
     * 真正"按写入顺序"的方案需要给 Character 加一个显式 sort_order 字段并写入，
     * 当前为了避免 schema 改动，暂用 name 排序。
     */
    List<Character> findByIsPresetTrueOrderByNameAsc();

    // 注：原 findByIsPresetTrueAndCategoryOrderByNameAsc 已在 V10 之后失效（preset 改走
    // PresetCharacterCache 内存缓存 + Service.stream filter）。且 Character.category
    // 改 Set<CharacterCategory> 多值集合后，原单字段查询方法名会启动失败（No property 'category'），
    // 故彻底删除。若未来要从 DB 重新启用分类筛选，需要改写为 JOIN character_categories 的派生查询。
}
