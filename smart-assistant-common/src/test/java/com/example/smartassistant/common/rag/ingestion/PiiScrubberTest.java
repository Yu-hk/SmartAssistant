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
