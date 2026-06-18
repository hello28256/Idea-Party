package com.ideaparty;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@Slf4j
// 排除 Spring Security 默认的 UserDetailsService 自动装配：项目使用自定义 JWT 认证体系，
// 避免自动生成的 in-memory 用户与我们的 SecurityConfig 产生 bean 冲突。
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class IdeaPartyApplication {

    public static void main(String[] args) {
        // .env 加载必须在 SpringApplication.run 之前完成：通过 System.setProperty 注入，
        // 才能让后续的 @Value / application.yml 占位符解析到本地密钥（DeepSeek、JWT 等）。
        // ignoreIfMissing + try-catch 保证生产环境用真实环境变量时不会因无 .env 而启动失败。
        // Load .env file from project root
        try {
            // 指向 ../ 是因为运行时 cwd 是 server/，需要回到仓库根目录找 .env（与 client/ 共享同一份密钥）。
            Dotenv dotenv = Dotenv.configure()
                    .directory("../")
                    .ignoreIfMissing()
                    .load();
            dotenv.entries().forEach(e ->
                System.setProperty(e.getKey(), e.getValue())
            );
            log.info("Loaded .env from project root");
        } catch (Exception e) {
            log.warn("Could not load .env file: {}", e.getMessage());
        }

        SpringApplication.run(IdeaPartyApplication.class, args);
    }
}
