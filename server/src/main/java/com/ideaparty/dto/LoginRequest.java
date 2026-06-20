package com.ideaparty.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录请求数据传输对象（DTO）。
 * <p>
 * 用途：作为 {@code POST /auth/login} 接口的请求体，承载用户登录所需的凭证信息。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>同时支持用户名和邮箱登录，故使用 {@code identifier} 统一字段名，避免前端区分传参。</li>
 *   <li>配合 Spring Validation 在进入业务逻辑前完成非空校验（{@link NotBlank}）。</li>
 *   <li>由 Lombok {@link Data} 自动生成 getter / setter / equals / hashCode / toString；
 *       {@link NoArgsConstructor} 提供 JSON 反序列化所需的默认构造器；
 *       {@link AllArgsConstructor} 便于测试与 builder 场景一次性构造。</li>
 * </ul>
 */
@Data
// Lombok：无参构造器，主要供 Jackson 反序列化 JSON 请求体时使用
@NoArgsConstructor
// Lombok：全参构造器，便于单元测试与一次性注入 username/email 与 password
@AllArgsConstructor
public class LoginRequest {

    /**
     * 登录标识符：用户名或邮箱。
     * <p>
     * 故意不区分 username / email：登录态统一通过 identifier 识别，
     * 由 AuthService 根据格式（是否包含 @）自行决定走用户名匹配还是邮箱匹配策略。
     */
    @NotBlank(message = "Username or email is required")
    private String identifier;

    /**
     * 登录密码（明文）。
     * <p>
     * 明文密码仅在本 DTO 层短暂存在，由 AuthService 立即与数据库存储的 bcrypt hash 比对，
     * 全程不做日志记录与持久化，比对完成后即随请求生命周期释放。
     */
    @NotBlank(message = "Password is required")
    private String password;
}
