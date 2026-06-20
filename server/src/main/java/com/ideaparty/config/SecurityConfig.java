package com.ideaparty.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Spring Security 总配置：定义无状态的 JWT 鉴权链路、公开端点白名单与 CORS 规则。
 * 与 {@link JwtAuthenticationFilter} 配合完成基于 Bearer Token 的身份注入；
 * 前端为 Vue SPA，使用 Cookie 之外的 token 方案，因此禁用 CSRF 并采用 STATELESS 会话策略。
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    /**
     * 注册密码编码器 Bean：使用 BCrypt（Spring Security 默认推荐算法），
     * 供 UserService 注册/改密时单向哈希；强度默认值足以抵御离线暴力破解。
     *
     * @return BCryptPasswordEncoder 单例，供依赖注入容器全局复用
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 装配鉴权过滤链：白名单内端点（含登录、健康检查、WebSocket、Swagger、上传静态资源）放行，
     * 其余一律要求认证；未认证请求统一以 JSON 401 响应，便于前端 axios 拦截器识别。
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/api/upload/avatars/**").permitAll()
                        .requestMatchers("/uploads/**").permitAll()
                        .requestMatchers("/api-docs/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
                        })
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // 允许的跨域来源：优先读取配置属性，再回退到环境变量；默认包含本地开发常用的 5 个 Vite 端口（5173-5177），覆盖多开调试场景
    // 注入顺序：application.yml 的 app.cors.allowed-origins → 环境变量 APP_CORS_ALLOWED_ORIGINS → 内置默认值
    @Value("${app.cors.allowed-origins:${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:5174,http://localhost:5175,http://localhost:5176,http://localhost:5177}}")
    private String corsAllowedOrigins;

    /**
     * 构造 CORS 配置源：把 {@link #corsAllowedOrigins} 解析为列表后写入 {@link CorsConfiguration}，
     * 允许常用 REST 方法与任意请求头，凭证支持开启（前端带 Cookie 的场景），
     * 预检缓存 3600 秒降低 OPTIONS 请求频率。
     *
     * @return 返回一个按请求动态取配置的 CorsConfigurationSource（此处实际对所有请求返回同一份配置）
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // 解析逗号分隔的 origins，空白和空项自动跳过
        java.util.List<String> origins = java.util.Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(java.util.List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        return request -> configuration;
    }

    /**
     * JWT 鉴权过滤器：每个请求解析一次 Authorization 头中的 Bearer Token，
     * 校验通过则把 userId 写入 SecurityContext；不通过则返回 401 短路响应，避免被下游视为越权 403。
     */
    @Component
    @Slf4j
    public static class JwtAuthenticationFilter extends OncePerRequestFilter {

        // 由构造器根据 jwt.secret 派生的对称签名密钥：单例复用，避免每个请求重新计算
        private final SecretKey secretKey;

        /**
         * 构造器：从配置/环境变量读取 JWT 密钥字符串，并在启动期强校验长度与占位符；
         * 校验失败立即抛 IllegalStateException，让 Spring 启动失败而不是带病上线。
         *
         * @param jwtSecret        用于 HS256 签名的原始密钥（应通过 JWT_SECRET 环境变量注入）
         * @param jwtSecretMinLength 最低字节长度阈值，默认 32 字节（HS256 RFC 最小建议值）
         */
        public JwtAuthenticationFilter(@Value("${jwt.secret}") String jwtSecret, @Value("${jwt.secret.min-length:32}") int jwtSecretMinLength) {
            // 启动期校验密钥强度：避免部署弱密钥 + 解析失败时给出明确日志
            if (jwtSecret == null || jwtSecret.isBlank()
                    || jwtSecret.startsWith("CHANGE_ME")
                    || jwtSecret.getBytes(StandardCharsets.UTF_8).length < jwtSecretMinLength) {
                throw new IllegalStateException(
                        "jwt.secret 未配置或长度不足 HS256 要求（>= " + jwtSecretMinLength + " 字节），请通过 JWT_SECRET 环境变量注入");
            }
            this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * OncePerRequestFilter 钩子：从 Authorization 头解析 Bearer Token，验签后将 userId 注入 SecurityContextHolder；
         * 若 Token 非法/过期，立即 401 短路并清理上下文，避免后续业务因"半鉴权"状态出现 403 误判。
         *
         * @param request     当前 HTTP 请求
         * @param response    当前 HTTP 响应（Token 非法时直接写入 401 JSON 体）
         * @param filterChain 过滤器链，用于放行已鉴权请求
         */
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {

            String authHeader = request.getHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);

                try {
                    Claims claims = Jwts.parser()
                            .verifyWith(secretKey)
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();

                    String userId = claims.getSubject();
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                } catch (Exception e) {
                    log.warn("JWT authentication failed: {}", e.getMessage());
                    SecurityContextHolder.clearContext();
                    // 已提供 Token 但不合法 —— 直接返回 401 而非让其变成 403
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid or expired token\"}");
                    return;
                }
            }

            filterChain.doFilter(request, response);
        }
    }
}
