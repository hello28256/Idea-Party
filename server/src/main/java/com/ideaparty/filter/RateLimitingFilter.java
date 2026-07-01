package com.ideaparty.filter;

import com.ideaparty.config.RateLimiterConfig;
import io.github.bucket4j.Bucket;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 基于 Bucket4j 的 IP 级限流过滤器，在进入业务 Controller 之前拦截滥用流量。
 * 与 {@link RateLimiterConfig} 配合：配置类负责按 clientKey 维护令牌桶，本类负责放行/拒绝。
 * 设计为 Spring Filter 而不是拦截器，是为了同时覆盖 Servlet 入口（包括非 Controller 资源）并通过 @Order 控制在鉴权链路中的位置。
 */
@Component
@Order(2)
public class RateLimitingFilter implements Filter {

    /**
     * 持有按 clientKey 解析/创建令牌桶的工厂，由 Spring 注入，
     * 避免在过滤器内部直接 new 出有状态对象，保证桶的生命周期由配置层统一管理。
     */
    private final RateLimiterConfig rateLimiterConfig;

    /**
     * 构造注入配置工厂，仅在 Spring 容器初始化时调用一次，
     * 之后过滤器的每次 doFilter 都复用同一个 rateLimiterConfig 引用，因此该过滤器本身是无状态的。
     */
    public RateLimitingFilter(RateLimiterConfig rateLimiterConfig) {
        this.rateLimiterConfig = rateLimiterConfig;
    }

    /**
     * 主过滤逻辑：对每个 HTTP 请求计算 clientKey、消耗一个令牌；
     * 成功则放行下游 chain，失败则直接返回 429 并写入 JSON 错误体，避免进入业务层造成更重的资源开销。
     * 注意：此处不抛异常，因此下游 chain.doFilter 的异常不会被本过滤器吞掉，仍按原链路向上传播。
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();
        // 登录/注册等鉴权接口故意不走限流：登录失败本身可能因频繁重试触发限流，会让真正的用户在封禁期无法登录，反而放大问题
        if (path.startsWith("/api/auth")) {
            chain.doFilter(request, response);
            return;
        }

        // clientKey 决定限流维度（IP 维度），同一 IP 共享一个令牌桶，从而防止单点暴力调用
        String clientKey = resolveClientKey(httpRequest);
        Bucket bucket = rateLimiterConfig.resolveBucket(clientKey);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json");
            // 固定返回结构化 JSON 错误体，前端可据此展示倒计时/提示，而不是依赖 Servlet 默认错误页
            httpResponse.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Try again later.\"}");
        }
    }

    /**
     * 优先取 X-Forwarded-For 的第一段作为客户端真实 IP，仅在反代/网关环境下有效；
     * 若请求未经过反代（直连或本地调试），则回退到 remoteAddr，确保任何部署形态都能拿到一个稳定 key。
     */
    private String resolveClientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
