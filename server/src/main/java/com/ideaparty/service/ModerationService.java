package com.ideaparty.service;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户发言内容审核服务。
 * 在消息进入 AI 角色聊天室之前做轻量级内容过滤，承担"前置哨兵"角色：拦截明显违规输入、避免不必要的大模型调用成本。
 * 与 ChatRoom / WebSocket 消息入口配合使用，输出 {@link ModerationResult} 而非抛异常，方便调用方按业务决定是丢弃、降级还是提示用户。
 */
@Service
public class ModerationService {

    /**
     * 黑名单子串清单。命中任一模式即视为违规。
     * 覆盖三类风险：广告/垃圾（spam、advertisement）、外链引流（http(s)://）、XSS 攻击载荷（<script>、javascript:），以及常见邮箱后缀（防止用户私聊绕开平台）。
     * 邮箱白名单/黑名单按业务变化较频繁，所以直接以"高频域名为后缀"为策略，保持规则简单可读。
     */
    private static final List<String> BLOCKED_PATTERNS = Arrays.asList(
        "spam",
        "advertisement",
        "http://",
        "https://",
        "<script>",
        "javascript:",
        "@gmail.com",
        "@yahoo.com",
        "@hotmail.com"
    );

    /**
     * 通用邮箱正则。用于兜底拦截清单之外的邮箱（如自建域、企业邮箱）。
     * 与 BLOCKED_PATTERNS 互补：清单是高命中低成本快查，正则负责"尽量少漏网"，二者均通过才放行。
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
    );

    /** 单条消息最大长度。取值与 LLM 上下文窗口、DB 字段长度（TEXT 64KB 内）取保守交集，防止单条消息压垮后续链路。 */
    private static final int MAX_MESSAGE_LENGTH = 2000;
    /** 单条消息最小有效长度。1 是冗余保护，主要防御 null/全空白绕过 trim 后被误判为"有内容"的情况。 */
    private static final int MIN_MESSAGE_LENGTH = 1;

    /**
     * 对单条用户消息做内容审核。
     * 入参：原始消息字符串（允许为 null/空白）。
     * 返回：{@link ModerationResult}，allowed=false 时 reason 给出拒绝原因，可直接透传给前端作为提示语。
     * 副作用：无（纯函数式检查，不持久化、不调用外部服务，便于在 WebSocket 入口同步执行）。
     */
    public ModerationResult moderate(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new ModerationResult(false, "Message cannot be empty");
        }

        String trimmed = content.trim();

        if (trimmed.length() < MIN_MESSAGE_LENGTH) {
            return new ModerationResult(false, "Message is too short");
        }

        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            return new ModerationResult(false, "Message exceeds maximum length of " + MAX_MESSAGE_LENGTH + " characters");
        }

        String lowerContent = trimmed.toLowerCase();

        for (String pattern : BLOCKED_PATTERNS) {
            if (lowerContent.contains(pattern.toLowerCase())) {
                return new ModerationResult(false, "Message contains prohibited content");
            }
        }

        if (EMAIL_PATTERN.matcher(trimmed).find()) {
            return new ModerationResult(false, "Email addresses are not allowed");
        }

        return new ModerationResult(true, null);
    }

    /**
     * 审核结果值对象。不可变（字段 final、无 setter），由 {@link #moderate(String)} 返回。
     * 作为审核服务的对外契约，避免调用方依赖异常或布尔值 + out 参数；reason 在 allowed=true 时为 null，前端可直接展示。
     */
    public static class ModerationResult {
        /** 是否通过审核。true 表示放行，false 表示被拒。调用方按此布尔决定是否继续后续业务流。 */
        private final boolean allowed;
        /** 拒绝原因。allowed=true 时为 null；allowed=false 时为面向用户的友好提示文案，可直接展示给最终用户。 */
        private final String reason;

        /**
         * 全量构造函数，由 {@link ModerationService#moderate(String)} 内部调用。
         * 显式提供构造而非 Lombok，原因是值对象字段少、可读性优先，且审核路径对启动时类加载顺序敏感（避免与 Lombok processor 冲突）。
         */
        public ModerationResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        /**
         * 读取审核是否通过。
         * 调用方：WebSocket 消息入口、Controller 校验层；典型用法是 if (!result.isAllowed()) 走拒绝分支。
         */
        public boolean isAllowed() {
            return allowed;
        }

        /**
         * 读取拒绝原因文案（仅在 allowed=false 时有值）。
         * 调用方：前端 Toast / 错误提示组件，用作 i18n 前的兜底展示文本。
         */
        public String getReason() {
            return reason;
        }
    }
}
