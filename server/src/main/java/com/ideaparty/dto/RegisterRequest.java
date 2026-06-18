package com.ideaparty.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户注册请求载体。
 * 由 AuthController 注册接口接收，配合 jakarta.validation 在进入 Service 前完成格式校验，
 * 避免脏数据进入业务层（密码哈希、唯一性检查、持久化）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    // 作为账号唯一标识与登录凭据；不强制唯一性由 DB 约束兜底，这里只保证格式合法
    @Email(message = "Invalid email format")
    private String email;

    // 6 位下限是对齐早期账号迁移策略的最小复杂度；服务端再做 BCrypt 哈希，不在此处做强度策略
    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    // 限定字母数字下划线：避免后续用于 @ 提及、URL slug、文件路径时出现需要转义的字符
    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 20, message = "Username must be 3-20 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username can only contain letters, numbers, and underscores")
    private String username;
}
