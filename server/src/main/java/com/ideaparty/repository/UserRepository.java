package com.ideaparty.repository;

import com.ideaparty.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户表的数据访问层：负责按 email / username / 主键 等维度查询 User 实体。
 * 由 Spring Data JPA 在运行时自动生成实现，供 Service 层（如 AuthService、UserService）依赖注入使用。
 * 用户名/邮箱在本系统中作为登录凭证的唯一标识，因此需要提供多种存在性校验与不区分大小写的匹配能力。
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * 按邮箱精确匹配查询用户：用于「忘记密码」、邮箱激活等场景，需保证邮箱字段格式/大小写与持久化时一致。
     * 调用方：AuthService 在发送重置链接前定位用户、Admin 后台按邮箱检索账号。
     */
    Optional<User> findByEmail(String email);

    /**
     * 判断邮箱是否已被注册：在用户注册/修改邮箱时做唯一性前置校验，避免到保存阶段才暴露数据库唯一约束异常。
     * 由 Service 层在事务内调用，配合 findByEmail 使用以提供更友好的错误信息。
     */
    boolean existsByEmail(String email);

    /**
     * 按用户名精确匹配查询用户：用于「我的资料」回显、后台按用户名搜索、以及需要区分大小写的展示场景。
     * 登录主流程请使用 findByUsernameOrEmail 以支持大小写不敏感。
     */
    Optional<User> findByUsername(String username);

    /**
     * 判断用户名是否已被占用：注册时防止重名，注册失败时立即给出可读性更高的错误信息而非依赖 DB 约束。
     * 调用方：AuthService.register、UserService.updateProfile。
     */
    boolean existsByUsername(String username);

    // 更新资料时排除自身：避免「用户名未变更却因唯一约束报错」的误判，仅用于编辑场景。
    boolean existsByUsernameAndIdNot(String username, UUID id);

    // 同上，邮箱在更新时也需排除当前用户自身的记录。
    boolean existsByEmailAndIdNot(String email, UUID id);

    /**
     * 登录场景的统一入口：用户既可能用用户名也可能用邮箱登录。
     * 使用 LOWER(...) = LOWER(:keyword) 实现大小写不敏感匹配，避免用户因大小写差异登录失败；
     * 不在数据库列上加函数索引是因为登录 QPS 有限，权衡下保持 schema 简单。
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:keyword) OR LOWER(u.email) = LOWER(:keyword)")
    Optional<User> findByUsernameOrEmail(@Param("keyword") String keyword);
}
