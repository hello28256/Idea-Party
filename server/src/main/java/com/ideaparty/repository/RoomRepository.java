package com.ideaparty.repository;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 聊天室数据访问层:封装对 rooms 表及 room_characters 多对多关联的查询,
 * 为 RoomService 提供权限隔离(按 owner / member)、加载策略(FETCH JOIN 避免 N+1)
 * 和角色引用检测(删除角色前必须清空关联房间)三类能力。
 *
 * 继承 JpaRepository<Room, UUID> 自动获得 CRUD 与分页能力;本接口只声明
 * 业务侧真正需要的查询,其余通用能力由 Spring Data 在运行时生成代理实现,
 * 因此本类不存在实现代码——所有方法在容器启动后由 Bean 后置处理器织入。
 */
@Repository
public interface RoomRepository extends JpaRepository<Room, UUID> {

    /**
     * 查询某用户作为 owner 的所有房间。JOIN FETCH owner 是为了在
     * 列表展示时一次性拿到房主信息,避免遍历时触发懒加载 N+1。
     */
    @Query("SELECT r FROM Room r JOIN FETCH r.owner WHERE r.owner.id = :ownerId")
    List<Room> findByOwnerId(@Param("ownerId") UUID ownerId);

    /**
     * 加载房间详情:一次性 JOIN 出 characters 和 members,供进入房间页
     * 直接渲染参与者列表与成员列表,避免后续访问触发多次 SELECT。
     * LEFT JOIN 是允许 rooms.characters / members 为空(空房间/单人房)。
     */
    @Query("SELECT r FROM Room r LEFT JOIN FETCH r.characters LEFT JOIN FETCH r.members WHERE r.id = :id")
    Optional<Room> findWithCharactersById(@Param("id") UUID id);

    /**
     * 按主键 + 房主双重条件加载房间,供 RoomService 在更新/删除前校验操作者
     * 必须是 owner,避免水平越权(非房主通过猜测 ID 改动他人房间)。
     * 不需要 JOIN FETCH,调用方已持有 user,详情渲染走 findWithCharactersById。
     */
    Optional<Room> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * 仅判断房间是否存在且归属指定 owner,用于删除/离开前的轻量权限校验,
     * 比 findByIdAndOwnerId 省去实体反序列化,Service 在不需要实体时优先调用。
     */
    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * 列出某用户作为活跃成员加入的所有房间,按最近活跃时间倒序。
     * ORDER BY 中 COALESCE(lastEnterTime, updatedAt) 优先使用房主最近进入时间,
     * 未进入过则回退到更新时间,确保"最近访问"排序稳定。
     * status='active' 过滤掉已退出/被踢的成员记录。
     */
    @Query("SELECT r FROM Room r JOIN FETCH r.owner JOIN FETCH r.members m JOIN FETCH m.user WHERE m.user.id = :userId AND m.status = 'active' ORDER BY COALESCE(r.lastEnterTime, r.updatedAt) DESC")
    List<Room> findRoomsByMemberUserId(@Param("userId") UUID userId);

    /**
     * 查找所有引用了指定角色的房间（用于删除角色前解除关联）。
     * Spring Data 由方法名自动生成 JPQL:遍历 rooms.characters 集合并匹配包含关系,
     * 调用方拿到结果后逐个解除 ManyToMany 关联,以免 FK 冲突导致角色删除失败。
     */
    List<Room> findAllByCharactersContaining(Character character);

    /**
     * 统计引用了指定角色的房间数（用于删除角色前的引用检查）。
     * 走显式 JPQL 而不是 Derived Query,因为只需要 COUNT,避免把整张 Room 行加载进内存。
     * 返回 long 给 CharacterService 做"是否仍被引用"的快速判断,数量为 0 才允许删除。
     */
    @Query("SELECT COUNT(r) FROM Room r JOIN r.characters c WHERE c.id = :characterId")
    long countByCharactersId(@Param("characterId") UUID characterId);
}
