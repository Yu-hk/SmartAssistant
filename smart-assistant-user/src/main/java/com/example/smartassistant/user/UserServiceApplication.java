package com.example.smartassistant.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.example.smartassistant.user.mapper")
public class UserServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
        System.out.println("===========================================");
        System.out.println("  User Service Started Successfully!");
        System.out.println("  Port: 8085");
        System.out.println("  Nacos: http://localhost:8848");
        System.out.println("===========================================");
    }
}
