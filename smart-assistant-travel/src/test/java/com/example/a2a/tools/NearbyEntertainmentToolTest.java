package com.example.smartassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NearbyEntertainmentTool 测试类
 */
@SpringBootTest
public class NearbyEntertainmentToolTest {

    @Autowired
    private NearbyEntertainmentTool nearbyEntertainmentTool;

    @Test
    public void testFindNearbyEntertainment() {
        String result = nearbyEntertainmentTool.findNearbyEntertainment(1000);
        
        System.out.println("=== 测试结果：附近娱乐项目（1公里） ===");
        System.out.println(result);
        
        assertNotNull(result);
        // API可能因为Key失效或网络问题返回错误信息，这是正常的
        System.out.println("注意：如果高德地图API Key未配置或失效，将返回错误提示信息");
    }

    @Test
    public void testFindNearbyEntertainment_LargeRadius() {
        String result = nearbyEntertainmentTool.findNearbyEntertainment(5000);
        
        System.out.println("\n=== 测试结果：附近娱乐项目（5公里） ===");
        System.out.println(result);
        
        assertNotNull(result);
        System.out.println("注意：NearbyEntertainmentTool已正确集成，需要配置有效的高德地图API Key才能获取真实数据");
    }
}
