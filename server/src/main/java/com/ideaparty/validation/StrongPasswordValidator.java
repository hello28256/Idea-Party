package com.ideaparty.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/**
 * {@link StrongPassword} 的校验实现：长度下限 + 字符类型 + 常见弱密码黑名单。
 *
 * 校验顺序：长度 → 字符类型 → 黑名单，命中任一规则立即返回 false 并写入对应的中文 message。
 * 前端 {@code client/src/composables/usePasswordStrength.ts} 复用同一套规则，避免"前端说通过、后端拒"的体验割裂。
 *
 * 关于黑名单：
 *   - 仅 30 条最常见弱密码；长度阈值 + 字符类型已能拦截大部分自动扫描攻击，黑名单是补充。
 *   - 匹配时调用 {@code toLowerCase()}，避免 {@code Admin123} 这类大小写变体绕过。
 *   - 放在静态 {@code Set.of(...)} 里：不可变、无 NPE，30 条内存占用可忽略。
 *   - 暂不做配置文件化（{@code application.yml}）：常量硬编码更易审计；如未来需远程下发，再迁。
 */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    /** 长度下限，与前端 usePasswordStrength.ts 同步 */
    private static final int MIN_LENGTH = 8;

    /**
     * 常见弱密码黑名单。
     * 仅收录 "Top 常见" 静态列表，不依赖远程下发；后续如发现新的高频泄露密码再补。
     */
    private static final Set<String> COMMON_PASSWORDS = Set.of(
        "password", "12345678", "123456789", "1234567890",
        "qwerty", "qwerty123", "11111111", "00000000",
        "admin", "admin123", "admin1234", "administrator",
        "letmein", "welcome", "monkey", "dragon",
        "iloveyou", "princess", "football", "baseball",
        "sunshine", "master", "shadow", "superman",
        "trustno1", "abc12345", "abcd1234", "asdf1234",
        "qazwsx", "zxcvbnm", "1q2w3e4r"
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        // null / 空串交给 @NotBlank，本注解不重复报错
        if (value == null || value.isEmpty()) {
            return true;
        }

        // 1. 长度不足
        if (value.length() < MIN_LENGTH) {
            return setMessage(ctx, "密码长度至少 8 位");
        }

        // 2. 字符类型：必须同时含字母与数字
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
            // 短路优化：两项都满足则可提前退出
            if (hasLetter && hasDigit) break;
        }
        if (!hasLetter || !hasDigit) {
            return setMessage(ctx, "密码必须同时包含字母和数字");
        }

        // 3. 黑名单：大小写不敏感
        if (COMMON_PASSWORDS.contains(value.toLowerCase())) {
            return setMessage(ctx, "密码过于简单，请换一个");
        }

        return true;
    }

    /**
     * 用 ctx 动态替换默认 message，使前端能拿到场景化提示（"长度不足" vs "弱密码"）。
     * 不调这个方法时，ConstraintValidator 会使用注解的 default message。
     */
    private boolean setMessage(ConstraintValidatorContext ctx, String message) {
        ctx.disableDefaultConstraintViolation();
        ctx.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}