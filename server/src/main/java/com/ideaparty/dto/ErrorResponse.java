package com.ideaparty.dto;

import java.time.LocalDateTime;

/**
 * 统一错误响应体，作为全局异常处理器（{@code @RestControllerAdvice}）返回给前端的标准化错误结构。
 * 为什么存在：避免各 Controller/Service 自行拼装错误 JSON，统一字段命名（status / error / message / timestamp），
 * 配合前端 axios 拦截器可一致地展示提示并写入日志。
 * 配合方：{@code GlobalExceptionHandler}（server/.../exception/）以及任何手动抛错并希望走标准格式的 Service 层。
 */
public class ErrorResponse {
    /** HTTP 状态码（如 400/401/404/500），由 GlobalExceptionHandler 在 catch 时按异常类型映射填入。 */
    private int status;
    /** 短错误码/错误类别（如 "Bad Request"、"Unauthorized"），便于前端按 code 走分支或埋点统计。 */
    private String error;
    /** 面向用户/开发者的可读描述信息，是给"人"看的；不要塞敏感数据或堆栈。 */
    private String message;
    /** 错误发生时刻的服务器本地时间，便于排查时区问题与对账日志；构造时一次性写入，后续不再变更。 */
    private LocalDateTime timestamp;

    /**
     * 构造一个错误响应。
     * 调用方：{@code GlobalExceptionHandler} 在捕获异常后 new 出来；timestamp 在此处固化，避免序列化时漂移。
     * @param status HTTP 状态码（建议对齐异常语义，如 ValidationException→400）
     * @param error  错误类别短名（与 HTTP reason phrase 对齐即可）
     * @param message 人类可读的错误说明
     */
    public ErrorResponse(int status, String error, String message) {
        this.status = status;
        this.error = error;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }

    // getters

    /**
     * 返回 HTTP 状态码。被 Jackson 序列化进 JSON 响应体，供前端 axios 拦截器或日志系统读取。
     */
    public int getStatus() { return status; }

    /**
     * 返回错误类别短名（如 "Not Found"）。前端可基于该字段做粗粒度分支判断或埋点上报。
     */
    public String getError() { return error; }

    /**
     * 返回面向用户的错误描述。由异常处理器从异常 message 透传，前端 toast/dialog 直接展示。
     */
    public String getMessage() { return message; }

    /**
     * 返回错误发生的时间戳。用于排查跨时区/跨服务调用链路时与后端日志对齐。
     */
    public LocalDateTime getTimestamp() { return timestamp; }
}
