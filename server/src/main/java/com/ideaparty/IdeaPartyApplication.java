package com.ideaparty;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Spring Boot 应用的启动入口类。
 * <p>
 * 存在原因：聚合 @SpringBootApplication + ComponentScan，并托管 .env 加载等
 * 必须在 Spring 上下文初始化之前完成的副作用，与 application.yml、JWT 过滤器、
 * SecurityConfig 等模块共同构成后端启动链路。
 */
// 排除 Spring Security 默认的 UserDetailsService 自动装配：项目使用自定义 JWT 认证体系，
// 避免自动生成的 in-memory 用户与我们的 SecurityConfig 产生 bean 冲突。
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
// @Slf4j Lombok 注解：编译期生成 static final org.slf4j.Logger log 字段，
// 给 main() 的 .env 加载结果提供统一日志通道（与业务模块日志格式一致）。
@Slf4j
public class IdeaPartyApplication {

    /**
     * JVM 启动入口：先加载 .env，再启动 Spring 容器。
     * <p>
     * 副作用：读取仓库根目录 ../.env 并把每个键值对写入 System.setProperty，
     * 供后续 @Value / application.yml 占位符解析；任何异常都被吞掉并以 warn
     * 形式记录，避免本地无 .env 时阻断生产启动（生产应使用真实环境变量）。
     *
     * @param args 由 SpringApplication 解析的标准启动参数（profile、server.port 等）
     */
    // static：JVM 入口约定；无返回值（void），由 java 启动器在类加载后直接调用。
    public static void main(String[] args) {
        // .env 加载必须在 SpringApplication.run 之前完成：通过 System.setProperty 注入，
        // 才能让后续的 @Value / application.yml 占位符解析到本地密钥（DeepSeek、JWT 等）。
        // ignoreIfMissing + try-catch 保证生产环境用真实环境变量时不会因无 .env 而启动失败。
        // Load .env file from project root
        try {
            // 指向 ../ 是因为运行时 cwd 是 server/，需要回到仓库根目录找 .env（与 client/ 共享同一份密钥）。
            // ignoreIfMissing()：缺失 .env 时不抛异常，让 try-catch 统一用 warn 处理；
            //                   这样生产只配环境变量时也能正常启动。
            // load()：阻塞读取文件并解析为内存中的条目表，后续通过 entries() 遍历。
            Dotenv dotenv = Dotenv.configure()
                    .directory("../")
                    .ignoreIfMissing()
                    .load();
            // 通过 System.setProperty 而非 System.getenv 注入：Spring 的 @Value / 占位符
            // 解析链优先看 JVM 系统属性，这样 application.yml 里的 ${DEEPSEEK_API_KEY}
            // 才能拿到 .env 中的值；getenv 在容器外是只读的，无法回写。
            dotenv.entries().forEach(e ->
                System.setProperty(e.getKey(), e.getValue())
            );
            // 仅作开发期可观测性埋点：确认本地 .env 被识别，便于排查密钥缺失类问题。
            log.info("Loaded .env from project root");
        } catch (Exception e) {
            // warn 而非 error：本地没有 .env 不算故障（生产用环境变量即可），避免 Sentry/告警噪音。
            log.warn("Could not load .env file: {}", e.getMessage());
        }

        // 真正启动 Spring 容器的入口：会触发组件扫描、自动配置、CommandLineRunner 等
        // 全部生命周期；必须放在 .env 注入之后，否则依赖密钥的 bean（如 DeepSeek 客户端、
        // JWT 配置）会拿到空值。
        SpringApplication.run(IdeaPartyApplication.class, args);
    }
}
