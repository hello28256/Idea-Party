package com.ideaparty.dto;

import com.ideaparty.validation.StrongPassword;
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

    // 密码：非空 → 不超长 → 无空格 → 满足强度（长度/字符/黑名单），
    // 四道关卡由 jakarta validation 在 Controller 入口一次性完成，Service 层不再重复校验。
    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码长度不能超过 64 位")
    @Pattern(regexp = "^\\S+$", message = "密码不能包含空格")
    @StrongPassword(message = "密码强度不足：需至少 8 位、含字母和数字，且不能是常见弱密码")
    private String password;

    // 限定字母数字下划线：避免后续用于 @ 提及、URL slug、文件路径时出现需要转义的字符
    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 20, message = "Username must be 3-20 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username can only contain letters, numbers, and underscores")
    private String username;
}