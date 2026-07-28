/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PiiScrubber 单元测试——验证入库前敏感信息脱敏。
 */
class PiiScrubberTest {

    private final PiiScrubber scrubber = new PiiScrubber();

    @Test
    void scrubMobilePhone() {
        String out = scrubber.scrub("联系客服 13812345678 即可办理");
        assertTrue(out.contains("[PHONE]"));
        assertFalse(out.contains("13812345678"));
    }

    @Test
    void scrubIdCard() {
        String out = scrubber.scrub("身份证号 11010519491231002X 已核验");
        assertTrue(out.contains("[ID_CARD]"));
        assertFalse(out.contains("11010519491231002X"));
    }

    @Test
    void scrubEmail() {
        String out = scrubber.scrub("邮件 alice@example.com 查收");
        assertTrue(out.contains("[EMAIL]"));
    }

    @Test
    void scrubInternalIp() {
        String out = scrubber.scrub("内网地址 10.20.30.40 可访问");
        assertTrue(out.contains("[INTERNAL_IP]"));
        // 公网 IP 不应被脱敏
        assertFalse(scrubber.scrub("8.8.8.8 公共DNS").contains("[INTERNAL_IP]"));
    }

    @Test
    void scrubEmployeeId() {
        String out = scrubber.scrub("工号 E12345 负责跟进");
        assertTrue(out.contains("工号[EMP_ID]"));
    }

    @Test
    void scrubBankCard() {
        String out = scrubber.scrub("工资卡 6222021234567890123 已激活");
        assertTrue(out.contains("[BANK_CARD]"));
        assertFalse(out.contains("6222021234567890123"));
        // 19 位卡也应命中
        assertTrue(scrubber.scrub("卡号 1234567890123456789 可用").contains("[BANK_CARD]"));
    }

    @Test
    void scrubAddress() {
        String out = scrubber.scrub("收件地址：北京市海淀区中关村大街1号3栋502室");
        assertTrue(out.contains("[ADDRESS]"));
        assertFalse(out.contains("502室"));
        // 孤立省市区（公开地理信息）不应脱敏
        assertEquals("北京市", scrubber.scrub("北京市"));
    }

    @Test
    void scrubNameWithLabel() {
        String out = scrubber.scrub("联系人：张三，请尽快回电");
        assertTrue(out.contains("联系人：[NAME]"));
        assertFalse(out.contains("张三"));
        // 收货人标签（姓名后接非汉字即界定；后接汉字的「李四已」属规则级已知漏杀边界）
        assertTrue(scrubber.scrub("收货人李四。").contains("[NAME]"));
        assertTrue(scrubber.scrub("持卡人：王五 已支付").contains("[NAME]"));
    }

    @Test
    void nameWithoutLabelNotScrubbed() {
        // 无显式标签的自由文本姓名不处理（规则级方案的已知边界）
        String text = "张三的订单已发货";
        assertEquals(text, scrubber.scrub(text));
    }

    @Test
    void idempotentScrub() {
        String once = scrubber.scrub("手机 13812345678 邮箱 a@b.com");
        // 二次脱敏应无变化（占位符不被再次匹配）
        assertEquals(once, scrubber.scrub(once));
    }

    @Test
    void noPiiUntouched() {
        String text = "退款政策于2025年更新，审核周期3-7个工作日";
        assertEquals(text, scrubber.scrub(text));
        assertFalse(scrubber.containsPii(text));
    }

    @Test
    void containsPiiTrue() {
        assertTrue(scrubber.containsPii("请拨打 13900001111 咨询"));
    }

    @Test
    void nullSafe() {
        assertTrue(scrubber.scrub(null) == null);
    }
}
