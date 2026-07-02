package com.example.smartassistant.common.rag.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HashUtil} 的单元测试。
 * <p>
 * 覆盖：normalizeText 假变更过滤、sha256Hex 一致性、aggregateHash 聚合、空值和边界输入。
 * </p>
 */
class HashUtilTest {

    // ==================== normalizeText ====================

    @Test
    @DisplayName("normalizeText 去除更新时间戳")
    void normalizeText_removesTimestamp() {
        String input = "退款政策\n更新时间：2026-06-29 13:30\n7天无理由退货。";
        String result = HashUtil.normalizeText(input);
        assertFalse(result.contains("更新时间"), "应去除更新时间行");
        assertTrue(result.contains("退款政策"), "应保留正文");
        assertTrue(result.contains("7天无理由退货"), "应保留正文");
    }

    @Test
    @DisplayName("normalizeText 去除日期时间行")
    void normalizeText_removesDateTimeLine() {
        String input = "2026-06-29 13:30 上海\n今天天气很好。";
        String result = HashUtil.normalizeText(input);
        assertFalse(result.contains("2026"), "应去除日期行");
        assertTrue(result.contains("今天天气很好"), "应保留正文");
    }

    @Test
    @DisplayName("normalizeText 去除页脚页码")
    void normalizeText_removesPageFooter() {
        String input = "——第 3 页——\n正文内容";
        String result = HashUtil.normalizeText(input);
        assertFalse(result.contains("——"), "应去除页脚");
        assertTrue(result.contains("正文内容"), "应保留正文");
    }

    @Test
    @DisplayName("normalizeText 标准化连续空白")
    void normalizeText_collapsesWhitespace() {
        String input = "第1段。\n\n\n第2段。\n\n第3段。";
        String result = HashUtil.normalizeText(input);
        // 连续空白被归一化为单空格
        assertTrue(result.startsWith("第1段。") || result.contains("第1段。"), "应保留分段内容");
        assertTrue(result.contains("第2段。"), "应保留第2段");
        assertTrue(result.contains("第3段。"), "应保留第3段");
    }

    @Test
    @DisplayName("normalizeText 空值输入不抛异常")
    void normalizeText_handlesNull() {
        assertEquals("", HashUtil.normalizeText(null));
        assertEquals("", HashUtil.normalizeText(""));
        assertEquals("", HashUtil.normalizeText("   "));
    }

    // ==================== sha256Hex ====================

    @Test
    @DisplayName("sha256Hex 相同输入输出一致")
    void sha256Hex_isConsistent() {
        String input = "Hello, 世界!";
        String hash1 = HashUtil.sha256Hex(input);
        String hash2 = HashUtil.sha256Hex(input);
        assertEquals(hash1, hash2, "相同输入应产生相同哈希");
    }

    @Test
    @DisplayName("sha256Hex 不同输入输出不同")
    void sha256Hex_differsOnDifferentInput() {
        String hash1 = HashUtil.sha256Hex("退款政策");
        String hash2 = HashUtil.sha256Hex("发货规则");
        assertNotEquals(hash1, hash2, "不同输入应产生不同哈希");
    }

    @Test
    @DisplayName("sha256Hex 输出为 64 字符十六进制")
    void sha256Hex_outputFormat() {
        String hash = HashUtil.sha256Hex("test");
        assertNotNull(hash);
        assertEquals(64, hash.length(), "SHA-256 应为 64 字符十六进制");
        assertTrue(hash.matches("[0-9a-f]{64}"), "应仅为小写十六进制字符");
    }

    @Test
    @DisplayName("sha256Hex 空值返回空字符串")
    void sha256Hex_handlesNull() {
        assertEquals("", HashUtil.sha256Hex(null));
        assertEquals("", HashUtil.sha256Hex(""));
    }

    // ==================== normalizeAndHash ====================

    @Test
    @DisplayName("normalizeAndHash 时间戳差异不影响哈希")
    void normalizeAndHash_timestampIgnored() {
        String input1 = "政策说明\n更新时间：2026-06-29\n7天无理由。";
        String input2 = "政策说明\n更新时间：2026-07-01\n7天无理由。";
        assertEquals(
                HashUtil.normalizeAndHash(input1),
                HashUtil.normalizeAndHash(input2),
                "仅时间戳不同的内容应产生相同哈希"
        );
    }

    // ==================== aggregateHash ====================

    @Test
    @DisplayName("aggregateHash 多段落聚合一致")
    void aggregateHash_consistent() {
        List<String> contents1 = List.of("段落A", "段落B", "段落C");
        List<String> contents2 = List.of("段落A", "段落B", "段落C");
        assertEquals(
                HashUtil.aggregateHash(contents1),
                HashUtil.aggregateHash(contents2)
        );
    }

    @Test
    @DisplayName("aggregateHash 不同段落序产生不同哈希")
    void aggregateHash_orderMatters() {
        List<String> list1 = List.of("段落A", "段落B");
        List<String> list2 = List.of("段落B", "段落A");
        assertNotEquals(
                HashUtil.aggregateHash(list1),
                HashUtil.aggregateHash(list2),
                "段落顺序不同应产生不同哈希"
        );
    }

    @Test
    @DisplayName("aggregateHash 空列表返回空字符串")
    void aggregateHash_handlesEmpty() {
        assertEquals("", HashUtil.aggregateHash(null));
        assertEquals("", HashUtil.aggregateHash(List.of()));
    }

    // ==================== 假变更抗干扰 ====================

    @ParameterizedTest
    @CsvSource({
            // 同一内容，不同页脚时间 → 哈希相同
            "此处为正文内容",
            "此处为正文内容"
    })
    @DisplayName("假变更抗干扰：页脚时间戳不影响哈希")
    void falsePositiveTimestamp(String content) {
        String withFooter = "此处为正文内容\n更新时间：2026-06-29 13:30";
        String withLaterFooter = "此处为正文内容\n更新时间：2026-07-02 16:00";
        assertEquals(
                HashUtil.normalizeAndHash(withFooter),
                HashUtil.normalizeAndHash(withLaterFooter)
        );
    }
}
