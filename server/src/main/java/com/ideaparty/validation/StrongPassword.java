package com.ideaparty.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 密码强度校验注解：要求字段值满足项目内置的强密码策略（长度、字符类型、常见弱密码黑名单）。
 *
 * 设计为字段级约束，可叠加在 {@code @NotBlank} 之后：
 *   - null / 空串：由 {@code @NotBlank} 处理，本注解返回 true（不重复报错）。
 *   - 非空但强度不足：由 ConstraintValidator 设置具体的中文 message，方便前端直接展示给用户。
 *
 * 不引入 zxcvbn / Passay 等第三方库：规则简单可控（8 字符 + 字母 + 数字 + 30 条黑名单），
 * 自实现比引入依赖更轻量、更易审计。
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {
    String message() default "密码强度不足：需至少 8 位、含字母和数字，且不能是常见弱密码";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}