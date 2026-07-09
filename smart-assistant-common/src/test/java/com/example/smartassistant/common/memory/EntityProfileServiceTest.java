package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EntityProfileService 单元测试：用内存版 HashOperations 模拟 Redis，
 * 无需依赖外部 Redis 服务即可确定性运行。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EntityProfileServiceTest {

    private EntityProfileService profile;
    private StringRedisTemplate redis;
    private final Long USER_ID = 9999L;

    @BeforeEach
    void init() {
        // 用内存 Map 模拟 Redis Hash，避免依赖外部 Redis 服务
        Map<String, Map<Object, Object>> store = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

        doAnswer(inv -> {
            String h = inv.getArgument(0);
            Object hk = inv.getArgument(1);
            Object hv = inv.getArgument(2);
            store.computeIfAbsent(h, k -> new ConcurrentHashMap<>()).put(hk, hv);
            return null;
        }).when(hashOps).put(anyString(), any(), any());

        doAnswer(inv -> {
            String h = inv.getArgument(0);
            Map<?, ?> m = inv.getArgument(1);
            store.computeIfAbsent(h, k -> new ConcurrentHashMap<>()).putAll(m);
            return null;
        }).when(hashOps).putAll(anyString(), anyMap());

        when(hashOps.get(anyString(), any())).thenAnswer(inv -> {
            String h = inv.getArgument(0);
            Object hk = inv.getArgument(1);
            Map<Object, Object> m = store.get(h);
            return m == null ? null : m.get(hk);
        });

        when(hashOps.entries(anyString())).thenAnswer(inv -> {
            String h = inv.getArgument(0);
            Map<Object, Object> m = store.get(h);
            return m == null ? Map.of() : new LinkedHashMap<>(m);
        });

        redis = mock(StringRedisTemplate.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.expire(anyString(), anyLong(), any())).thenReturn(true);
        when(redis.delete(anyString())).thenAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return Boolean.TRUE;
        });

        profile = new EntityProfileService(redis);
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
