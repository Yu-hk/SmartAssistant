package com.example.smartassistant.consumer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;  // ⭐ 启用定时任务

/**
 * A2A Consumer 启动类 (MyBatis Plus)
 * 启动后，可通过 REST API 调用远程数学计算智能体
 */
@SpringBootApplication
@EnableScheduling  // ⭐ 启用定时任务调度
@MapperScan({
        "com.example.smartassistant.consumer.mapper"  // 主 Mapper
})
@ComponentScan({
        "com.example.smartassistant.consumer",  // 主模块
        "com.example.smartassistant.common"    // ⭐ 公共模块（ChineseTokenizer 等）
})
public class A2aConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(A2aConsumerApplication.class, args);
        System.out.println("==================================================");
        System.out.println("  A2A Consumer 启动成功!");
        System.out.println("  端口: 8082");
        System.out.println("  监控面板: http://localhost:8082/dashboard");
        System.out.println("==================================================");
    }
}
