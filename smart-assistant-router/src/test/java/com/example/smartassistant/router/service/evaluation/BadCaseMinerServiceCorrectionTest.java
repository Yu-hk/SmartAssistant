/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.evaluation;

import com.example.smartassistant.common.correction.CorrectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BadCaseMinerService ⭐ P5-B 用户纠正信号闭环测试：
 * recordCorrection() 命中纠正信号时应①写 BadCase ②持久化到 CorrectionService。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BadCaseMinerServiceCorrectionTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private CorrectionService correctionService;

    private BadCaseMinerService service;

    private static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPush(anyString(), anyString())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(1L);

        service = new BadCaseMinerService(redisTemplate, objectMapper());
        service.setEnabled(true);
        service.setCorrectionService(correctionService);
    }

    private BadCaseMinerService.RoutingDecision decision(String question, String agent) {
        return new BadCaseMinerService.RoutingDecision(
                question, "travel", 0.9, agent, "session-1", 42L);
    }

    @Test
    void correctionSignalPersistsAndWritesBadCase() {
        service.recordCorrection(decision("你刚才说错了，应该是上海", "travel_agent"));

        // ① 写 BadCase
        verify(listOps, atLeastOnce()).leftPush(eq("a2a:bad-cases"), anyString());
        // ② 持久化纠正到 CorrectionService
        verify(correctionService).appendCorrection(
                eq("travel_agent"), anyString(), anyString(), anyString(), eq(42L));
    }

    @Test
    void nonCorrectionDoesNothing() {
        service.recordCorrection(decision("帮我查一下北京天气", "travel_agent"));

        verify(listOps, never()).leftPush(anyString(), anyString());
        verify(correctionService, never()).appendCorrection(
                anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void agreementIsNotCorrection() {
        service.recordCorrection(decision("你说得对，就是这样", "travel_agent"));

        verify(listOps, never()).leftPush(anyString(), anyString());
        verify(correctionService, never()).appendCorrection(
                anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void nullCorrectionServiceOnlyWritesBadCase() {
        BadCaseMinerService noCorr = new BadCaseMinerService(redisTemplate, objectMapper());
        noCorr.setEnabled(true);
        noCorr.recordCorrection(decision("你搞错了，不是这个套餐", "food_agent"));

        verify(listOps, atLeastOnce()).leftPush(eq("a2a:bad-cases"), anyString());
        // 没有注入 CorrectionService，不应调用
        verify(correctionService, never()).appendCorrection(
                anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
