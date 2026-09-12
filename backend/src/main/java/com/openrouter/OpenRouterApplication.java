package com.openrouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OpenRouterApplication {
    public static void main(String[] args) {
        // 必须透传 args，否则 --server.port / --spring.datasource.url 等命令行属性不会生效
        SpringApplication.run(OpenRouterApplication.class, args);
    }
}
