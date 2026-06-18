package com.ideaparty.exception;

import com.ideaparty.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 全局异常处理器：作为 REST 层统一的错误出口，把分散抛出的异常规整成一致的
// ErrorResponse 响应体，避免每个 Controller 各自 try-catch，也让前端拿到稳定的错误结构。
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理 @Valid 校验失败：把所有字段错误拼成单条字符串返回，方便前端一次性展示。
     * 之所以不用 BindingResult 的默认错误页，是因为本项目是纯 JSON API，不需要 HTML 错误页。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        ErrorResponse error = new ErrorResponse(400, "Bad Request", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * 处理业务层手动抛出的 IllegalArgumentException（例如参数校验失败）。
     * 选用 400 而不是 500，是因为这类异常代表客户端调用错误，不是服务端故障。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.error("[DEBUG] IllegalArgumentException: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(400, "Bad Request", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * 处理 Spring Security 抛出的鉴权失败异常，对应无权限访问受保护资源的场景。
     * 用 warn 而非 error 级别，因为拒绝访问属于预期内的安全事件，不是系统错误。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("[DEBUG] AccessDenied: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(403, "Forbidden", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * 兜底处理器：捕获未被前面更具体处理器拦截的异常，避免堆栈直接暴露给客户端。
     * 故意把异常类名 + message 一并返回，是为了开发期排查方便；上线前需要评估是否对外屏蔽内部细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("[DEBUG] Unexpected error: ", ex);
        String detailedMessage = ex.getMessage() != null ? ex.getMessage() : "Unknown error";
        // Return more detailed error message for debugging
        ErrorResponse error = new ErrorResponse(500, "Internal Server Error",
            ex.getClass().getSimpleName() + ": " + detailedMessage);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
