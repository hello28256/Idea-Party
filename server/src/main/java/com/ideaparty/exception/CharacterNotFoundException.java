package com.ideaparty.exception;

/**
 * 业务异常：当根据 ID 或名称查找 Character 实体未命中时抛出。
 * 由 Service 层抛出，最终交由 GlobalExceptionHandler 统一转换为 404 响应体，
 * 避免把"找不到"这种预期内的业务情况混入系统级 500 错误。
 */
public class CharacterNotFoundException extends RuntimeException {

    /**
     * 仅携带可读消息的构造器。
     * 适用于单纯的查不到场景：调用方已经把"为什么找不到"组织成了一句人话。
     */
    public CharacterNotFoundException(String message) {
        super(message);
    }

    /**
     * 携带根因的构造器。
     * 当底层仓储/数据库访问本身也抛了异常，需要把原始 cause 串起来以便排障时使用。
     */
    public CharacterNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
