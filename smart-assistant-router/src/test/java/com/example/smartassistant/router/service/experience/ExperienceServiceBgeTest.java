/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.experience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExperienceService} BGE 余弦相似度与语义合并逻辑单元测试。
 * <p>
 * 覆盖不需要 Redis/ONNX/Spring 上下文的纯逻辑方法：
 * <ul>
 *   <li>{@code cosineSimilarity(float[], float[])} — BGE 归一化向量点积</li>
 *   <li>向量边界条件（null/空/长度不等/全零/正负值）</li>
 * </ul>
 * </p>
 */
@DisplayName("ExperienceService BGE 语义合并测试")
class ExperienceServiceBgeTest {

    /** 通过反射调用 private static cosineSimilarity。 */
    private static double cosineSimilarity(float[] a, float[] b) {
        try {
            Method m = ExperienceService.class
                    .getDeclaredMethod("cosineSimilarity", float[].class, float[].class);
            m.setAccessible(true);
            return (double) m.invoke(null, a, b);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }

    @Nested @DisplayName("cosineSimilarity 余弦相似度")
    class CosineSimilarityTest {

        @Test @DisplayName("相同向量 → 1.0")
        void identicalVectors() {
            float[] v = {1, 0, 0};
            assertEquals(1.0, cosineSimilarity(v, v), 1e-9);
        }

        @Test @DisplayName("正交向量 → 0.0")
        void orthogonalVectors() {
            assertEquals(0.0, cosineSimilarity(
                    new float[]{1, 0, 0},
                    new float[]{0, 1, 0}), 1e-9);
        }

        @Test @DisplayName("反向向量（归一化后）→ -1.0")
        void oppositeVectors() {
            assertEquals(-1.0, cosineSimilarity(
                    new float[]{1, 0},
                    new float[]{-1, 0}), 1e-9);
        }

        @Test @DisplayName("归一化后同方向→cosine≈1")
        void sameDirectionNormalized() {
            float[] a = {3, 4};    // 长度 5
            float[] b = {6, 8};    // 长度 10
            double sim = cosineSimilarity(a, b);
            assertTrue(sim > 0.99, "同方向归一化向量 cosine 应 ≈ 1: " + sim);
        }

        @Test @DisplayName("相似但不相同的向量 → 0 < cosine < 1")
        void similarVectors() {
            double sim = cosineSimilarity(
                    new float[]{1, 0, 0},
                    new float[]{0.9f, 0.1f, 0});
            assertTrue(sim > 0 && sim < 1, "相似向量 cosine 应在 0~1 之间: " + sim);
        }

        @Test @DisplayName("null 输入 → 返回 0")
        void nullInput_returns0() {
            assertEquals(0.0, cosineSimilarity(null, new float[]{1, 0}));
            assertEquals(0.0, cosineSimilarity(new float[]{1, 0}, null));
        }

        @Test @DisplayName("长度不等 → 返回 0")
        void mismatchedLength_returns0() {
            assertEquals(0.0, cosineSimilarity(
                    new float[]{1, 0, 0},
                    new float[]{1, 0}));
        }

        @Test @DisplayName("全零向量 → 返回 0")
        void zeroVector_returns0() {
            assertEquals(0.0, cosineSimilarity(
                    new float[]{0, 0, 0},
                    new float[]{1, 0, 0}));
        }

        @Test @DisplayName("正负值混合 → 正确计算")
        void mixedSignVectors() {
            double sim = cosineSimilarity(
                    new float[]{0.5f, -0.5f, 0.5f},
                    new float[]{-0.5f, 0.5f, -0.5f});
            assertTrue(sim < 0, "反向向量 cosine 应为负: " + sim);
        }

        @Test @DisplayName("两个 null 输入 → 返回 0")
        void bothNull_returns0() {
            assertEquals(0.0, cosineSimilarity(null, null));
        }
    }
}
