package com.ideaparty.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录请求载体：同时支持用户名和邮箱登录，因此用 identifier 统一字段名以避免前端区分传参。
 * 由 AuthController 在 /auth/login 接收，配合 Spring Validation 在进入业务逻辑前完成非空校验。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    // 故意不区分 username / email：登录态统一通过 identifier 识别，后端自行决定走哪种匹配策略
    @NotBlank(message = "Username or email is required")
    private String identifier;

    // 明文密码仅在本层短暂存在，由 AuthService 立即与数据库 hash 比对，不做持久化
    @NotBlank(message = "Password is required")
    private String password;
}
