package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EntityProfileService 测试（需要本地 Redis）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EntityProfileServiceTest {

    private static EntityProfileService profile;
    private static StringRedisTemplate redis;
    private static final Long USER_ID = 9999L;

    @BeforeAll
    static void init() {
        try {
            var config = new RedisStandaloneConfiguration("localhost", 6379);
            config.setPassword("redis123");
            config.setDatabase(2);
            var factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();
            redis.getConnectionFactory().getConnection().ping();
            profile = new EntityProfileService(redis);
            System.out.println("[Redis] OK");
        } catch (Exception e) {
            System.out.println("[Redis] FAIL: " + e.getMessage());
        }
    }

    @BeforeEach
    void clean() {
        if (redis != null) redis.delete("user:profile:" + USER_ID);
    }

    @Test @Order(1) @DisplayName("存储和读取单条事实")
    void putAndGet() {
        profile.put(USER_ID, "name", "张三");
        assertEquals("张三", profile.get(USER_ID, "name"));
    }

    @Test @Order(2) @DisplayName("批量存储")
    void putAll() {
        profile.putAll(USER_ID, Map.of("name", "李四", "fear", "狗", "preference", "川菜"));
        var all = profile.getAll(USER_ID);
        assertEquals("李四", all.get("name"));
        assertEquals("狗", all.get("fear"));
        assertEquals("川菜", all.get("preference"));
    }

    @Test @Order(3) @DisplayName("不存在的类别返回 null")
    void getNonExist() {
        assertNull(profile.get(USER_ID, "nonexist"));
    }

    @Test @Order(4) @DisplayName("null 参数不报错")
    void nullParams() {
        assertDoesNotThrow(() -> profile.put(null, "a", "b"));
        assertDoesNotThrow(() -> profile.put(1L, null, "b"));
        assertDoesNotThrow(() -> profile.put(1L, "a", null));
        assertDoesNotThrow(() -> profile.putAll(null, Map.of()));
        assertNull(profile.get(null, "a"));
    }

    @Test @Order(5) @DisplayName("提取害怕事实")
    void extractFear() {
        var facts = profile.extractFactsWithKeywords("我怕狗");
        assertEquals("狗", facts.get("fear"));
    }

    @Test @Order(6) @DisplayName("提取喜欢事实")
    void extractLike() {
        var facts = profile.extractFactsWithKeywords("我最爱吃川菜");
        assertEquals("川菜", facts.get("preference"));
    }

    @Test @Order(7) @DisplayName("提取名字事实")
    void extractName() {
        var facts = profile.extractFactsWithKeywords("我叫张三");
        assertEquals("张三", facts.get("name"));
    }

    @Test @Order(8) @DisplayName("提取位置事实")
    void extractLocation() {
        var facts = profile.extractFactsWithKeywords("我住在北京");
        assertEquals("北京", facts.get("location"));
    }

    @Test @Order(9) @DisplayName("复杂句子中提取")
    void extractComplex() {
        var facts = profile.extractFactsWithKeywords("大家好，我叫李四，我住在上海。我最爱吃火锅，但是怕辣。");
        assertTrue(facts.containsKey("name"));
        assertTrue(facts.containsKey("location"));
        assertTrue(facts.containsKey("preference") || facts.containsKey("fear"));
    }

    @Test @Order(10) @DisplayName("格式化画像为提示文本")
    void formatProfile() {
        profile.putAll(USER_ID, Map.of("name", "王五", "fear", "蛇"));
        String formatted = profile.formatProfile(USER_ID);
        assertTrue(formatted.contains("name: 王五"));
        assertTrue(formatted.contains("fear: 蛇"));
        System.out.println("[Profile] 格式化输出:\n" + formatted);
    }
}
