package com.ideaparty;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@Slf4j
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class IdeaPartyApplication {

    public static void main(String[] args) {
        // Load .env file from project root
        try {
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
