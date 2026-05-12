package com.example.smartassistant.user;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@Slf4j
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.example.smartassistant.user.mapper")
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
        log.info("===========================================");
        log.info("  User Service Started Successfully!");
        log.info("  Port: 8085");
        log.info("  Nacos: http://localhost:8848");
        log.info("===========================================");
    }
}
