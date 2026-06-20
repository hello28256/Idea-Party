package com.ideaparty.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 修改密码请求体 DTO。
 *
 * 用于用户主动修改密码的场景（通常对应「设置 / 安全」页面的修改密码表单），
 * 由 Controller 层接收后交给 AuthService 完成校验与持久化。
 * 字段以明文密码形式在 HTTPS 链路上传输，由后端立即做 BCrypt 校验与哈希写入，永不落库明文。
 */
@Data
// Lombok：生成无参构造器，供 Jackson 反序列化无默认值的请求体使用。
@NoArgsConstructor
// Lombok：生成全参构造器，便于 Service/测试代码直接以 (current, new) 形式构造请求体。
@AllArgsConstructor
public class ChangePasswordRequest {
    /**
     * 用户当前密码（明文，仅在本次 HTTP 请求生命周期内存在）。
     * 后端需先用此字段与数据库中存储的 BCrypt 哈希做匹配，校验通过后才允许更新，
     * 防止会话被盗时攻击者直接重置密码绕过原密码。
     */
    private String currentPassword;

    /**
     * 用户希望设置的新密码（明文，仅在本次 HTTP 请求生命周期内存在）。
     * 进入 Service 后会先做强度校验（长度、复杂度），再以 BCrypt 哈希形式写入数据库。
     * 永不明文持久化、永不回显到响应体。
     */
    private String newPassword;
}
