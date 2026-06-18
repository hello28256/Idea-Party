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
    @Value("${app.cors.allowed-origins:${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:5174,http://localhost:5175,http://localhost:5176,http://localhost:5177}}")
    private String corsAllowedOrigins;

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

        private final SecretKey secretKey;

        public JwtAuthenticationFilter(
                @Value("${jwt.secret}") String jwtSecret,
                @Value("${jwt.secret.min-length:32}") int jwtSecretMinLength) {
            // 启动期校验密钥强度：避免部署弱密钥 + 解析失败时给出明确日志
            if (jwtSecret == null || jwtSecret.isBlank()
                    || jwtSecret.startsWith("CHANGE_ME")
                    || jwtSecret.getBytes(StandardCharsets.UTF_8).length < jwtSecretMinLength) {
                throw new IllegalStateException(
                        "jwt.secret 未配置或长度不足 HS256 要求（>= " + jwtSecretMinLength + " 字节），请通过 JWT_SECRET 环境变量注入");
            }
            this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        }

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
                    // Token was provided but invalid — return 401 instead of letting it become a 403
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
