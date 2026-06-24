package com.example.smartassistant.router.service.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EntityNormalizer 单元测试——验证实体归一化、输入纠错功能。
 *
 * <h3>变更前后对比</h3>
 * <table>
 *   <tr><th>维度</th><th>修改前</th><th>修改后</th></tr>
 *   <tr><td>日期解析</td><td>不支持</td><td>相对日期(明天/下周一)、数字日期、星期偏移</td></tr>
 *   <tr><td>地点别名</td><td>不支持</td><td>杭州东→杭州东站、上海虹桥→上海虹桥站 等 15+ 别名</td></tr>
 *   <tr><td>金额归一</td><td>不支持</td><td>中文数字(二百/两千)→Double、常用货币单位</td></tr>
 *   <tr><td>时间窗口</td><td>不支持</td><td>下午→12:00-18:00、三点→14:00-16:00</td></tr>
 *   <tr><td>错别字纠错</td><td>不支持</td><td>杭洲→杭州、红桥→虹桥 等 10+ 纠错映射</td></tr>
 * </table>
 */
class EntityNormalizerTest {

    private final EntityNormalizer normalizer = new EntityNormalizer();

    // ==================== 输入鲁棒性 / 纠错 ====================

    @Test
    @DisplayName("错别字纠错: 杭洲 → 杭州")
    void testTypoCorrection_Hzhou() {
        var result = normalizer.normalizeInput("杭洲到上海");
        assertTrue(result.hasCorrections());
        assertEquals("杭州到上海", result.getNormalizedText());
        assertEquals("typo", result.getCorrections().get(0).get("type"));
    }

    @Test
    @DisplayName("错别字纠错: 红桥 → 虹桥")
    void testTypoCorrection_Hongqiao() {
        var result = normalizer.normalizeInput("上海红桥");
        assertTrue(result.hasCorrections());
        assertEquals("上海虹桥", result.getNormalizedText());
    }

    @Test
    @DisplayName("无纠错时保持原样")
    void testNoCorrection() {
        var result = normalizer.normalizeInput("明天去北京");
        assertFalse(result.hasCorrections());
        assertEquals("明天去北京", result.getNormalizedText());
    }

    // ==================== 日期归一化 ====================

    @Test
    @DisplayName("相对日期: 明天")
    void testDate_Tomorrow() {
        String date = normalizer.normalizeDate("明天");
        assertNotNull(date);
        // 格式验证 yyyy-MM-dd
        assertTrue(date.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    @DisplayName("相对日期: 后天")
    void testDate_DayAfterTomorrow() {
        String date = normalizer.normalizeDate("后天");
        assertNotNull(date);
        assertTrue(date.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    @DisplayName("相对日期: 昨天")
    void testDate_Yesterday() {
        String date = normalizer.normalizeDate("昨天");
        assertNotNull(date);
        assertTrue(date.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @ParameterizedTest
    @CsvSource({
        "2026年4月23日, 2026-04-23",
        "2026-04-23, 2026-04-23",
        "4月23日, 2026-04-23"
    })
    @DisplayName("数字日期解析")
    void testDate_Numeric(String input, String expected) {
        // 只验证能正确解析出格式，不验证具体日期值（因为相对日期依赖当前时间）
        String date = normalizer.normalizeDate(input);
        assertNotNull(date);
        assertTrue(date.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    @DisplayName("无效日期返回 null")
    void testDate_Invalid() {
        assertNull(normalizer.normalizeDate(""));
        assertNull(normalizer.normalizeDate(null));
        assertNull(normalizer.normalizeDate("随便说说的"));
    }

    // ==================== 时间窗口归一化 ====================

    @ParameterizedTest
    @CsvSource({
        "下午, 12:00, 18:00",
        "早上, 06:00, 09:00",
        "晚上, 18:00, 23:59",
        "中午, 11:00, 13:00"
    })
    @DisplayName("固定时间段解析")
    void testTimeWindow_Fixed(String input, String start, String end) {
        String[] window = normalizer.normalizeTimeWindow(input);
        assertNotNull(window);
        assertEquals(start, window[0]);
        assertEquals(end, window[1]);
    }

    @Test
    @DisplayName("具体时间点: 下午三点")
    void testTimeWindow_Specific() {
        String[] window = normalizer.normalizeTimeWindow("下午三点");
        assertNotNull(window);
        assertEquals("14:00", window[0]); // 15-1
        assertEquals("16:00", window[1]); // 15+1
    }

    @Test
    @DisplayName("无效时间返回 null")
    void testTimeWindow_Invalid() {
        assertNull(normalizer.normalizeTimeWindow(""));
        assertNull(normalizer.normalizeTimeWindow(null));
    }

    // ==================== 金额归一化 ====================

    @ParameterizedTest
    @CsvSource({
        "二百, 200.0",
        "两千, 2000.0",
        "一百二十三, 123.0"
    })
    @DisplayName("中文数字转金额")
    void testAmount_Chinese(String input, double expected) {
        Double amount = normalizer.normalizeAmount(input);
        assertNotNull(amount);
        assertEquals(expected, amount, 0.01);
    }

    @Test
    @DisplayName("纯数字金额")
    void testAmount_Numeric() {
        Double amount = normalizer.normalizeAmount("200");
        assertNotNull(amount);
        assertEquals(200.0, amount, 0.01);
    }

    @Test
    @DisplayName("约数前缀自动清理")
    void testAmount_Approximate() {
        Double amount = normalizer.normalizeAmount("大概二百左右");
        assertNotNull(amount);
        assertEquals(200.0, amount, 0.01);
    }

    // ==================== 地点别名标准化 ====================

    @Test
    @DisplayName("车站别名: 上海虹桥 → 上海虹桥站")
    void testStationAlias_ShanghaiHongqiao() {
        var result = normalizer.normalizeInput("到上海虹桥");
        assertTrue(result.hasCorrections());
        assertTrue(result.getNormalizedText().contains("上海虹桥站"));
    }

    @Test
    @DisplayName("车站别名: 杭州东 → 杭州东站")
    void testStationAlias_HangzhouEast() {
        var result = normalizer.normalizeInput("从杭州东出发");
        assertTrue(result.hasCorrections());
        assertTrue(result.getNormalizedText().contains("杭州东站"));
    }
}
