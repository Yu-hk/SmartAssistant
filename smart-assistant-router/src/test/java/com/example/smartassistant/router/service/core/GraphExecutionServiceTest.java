/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.SubTaskResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GraphExecutionService} 核心逻辑单元测试。
 * <p>
 * 覆盖三个基路径：ErrorType 分类、可重试/不可重试路径、重规划触发条件。
 * </p>
 *
 * @see GraphExecutionService#classifyException(Throwable)
 */
@DisplayName("GraphExecutionService 错误分类与重试逻辑")
class GraphExecutionServiceTest {

    // ==================== ErrorType 分类 ====================

    @Nested
    @DisplayName("classifyException — ErrorType 分类")
    class ClassifyExceptionTest {

        // ---------- 场景 1：RETRYABLE_FAILED ----------

        @Test
        @DisplayName("TimeoutException → RETRYABLE_FAILED")
        void timeoutException_isRetryable() {
            assertEquals(SubTaskResult.ErrorType.RETRYABLE_FAILED,
                    GraphExecutionService.classifyException(new TimeoutException("timed out")));
        }

        @Test
        @DisplayName("SocketTimeoutException → RETRYABLE_FAILED")
        void socketTimeout_isRetryable() {
            assertEquals(SubTaskResult.ErrorType.RETRYABLE_FAILED,
                    GraphExecutionService.classifyException(new SocketTimeoutException("read timed out")));
        }

        @Test
        @DisplayName("IOException 含 'timeout' → RETRYABLE_FAILED")
        void ioExceptionTimeout_isRetryable() {
            assertEquals(SubTaskResult.ErrorType.RETRYABLE_FAILED,
                    GraphExecutionService.classifyException(new IOException("connection timeout after 30s")));
        }

        @Test
        @DisplayName("IOException 含 'connect' → RETRYABLE_FAILED")
        void ioExceptionConnect_isRetryable() {
            assertEquals(SubTaskResult.ErrorType.RETRYABLE_FAILED,
                    GraphExecutionService.classifyException(new IOException("Connection refused: connect")));
        }

        @Test
        @DisplayName("IOException 含 'refused' → RETRYABLE_FAILED")
        void ioExceptionRefused_isRetryable() {
            assertEquals(SubTaskResult.ErrorType.RETRYABLE_FAILED,
                    GraphExecutionService.classifyException(new IOException("connect refused")));
        }

        @Test
        @DisplayName("IOException 含 'reset' → RETRYABLE_FAILED")
        void ioExceptionReset_isRetryable() {
            assertEquals(SubTaskResult.ErrorType.RETRYABLE_FAILED,
                    GraphExecutionService.classifyException(new IOException("Connection reset by peer")));
        }

        // ---------- 场景 2：FATAL_FAILED ----------

        @Test
        @DisplayName("null 异常 → FATAL_FAILED")
        void nullThrowable_isFatal() {
            assertEquals(SubTaskResult.ErrorType.FATAL_FAILED,
                    GraphExecutionService.classifyException(null));
        }

        @Test
        @DisplayName("IOException 无关键词 → FATAL_FAILED")
        void ioExceptionNoKeyword_isFatal() {
            assertEquals(SubTaskResult.ErrorType.FATAL_FAILED,
                    GraphExecutionService.classifyException(new IOException("File not found")));
        }

        @Test
        @DisplayName("IOException 无 message → FATAL_FAILED")
        void ioExceptionNullMessage_isFatal() {
            assertEquals(SubTaskResult.ErrorType.FATAL_FAILED,
                    GraphExecutionService.classifyException(new IOException((String) null)));
        }

        @Test
        @DisplayName("RuntimeException → FATAL_FAILED")
        void runtimeException_isFatal() {
            assertEquals(SubTaskResult.ErrorType.FATAL_FAILED,
                    GraphExecutionService.classifyException(new RuntimeException("unexpected")));
        }

        @Test
        @DisplayName("NullPointerException → FATAL_FAILED")
        void nullPointer_isFatal() {
            assertEquals(SubTaskResult.ErrorType.FATAL_FAILED,
                    GraphExecutionService.classifyException(new NullPointerException("agent returned null")));
        }

        // ---------- 场景 3：cause 链递归 ----------

        @Test
        @DisplayName("包装 TimeoutException（cause 链）→ RETRYABLE_FAILED")
        void wrappedTimeoutInCause_isRetryable() {
            assertEquals(SubTaskResult.ErrorType.RETRYABLE_FAILED,
                    GraphExecutionService.classifyException(
                            new RuntimeException("agent failed", new TimeoutException("timed out"))));
        }

        @Test
        @DisplayName("包装 IOException timeout（cause 链）→ RETRYABLE_FAILED")
        void wrappedIoTimeoutInCause_isRetryable() {
            assertEquals(SubTaskResult.ErrorType.RETRYABLE_FAILED,
                    GraphExecutionService.classifyException(
                            new RuntimeException("agent failed", new IOException("connection timeout"))));
        }

        @Test
        @DisplayName("多层 cause 链 → 正确递归到 RETRYABLE")
        void deepCauseChain_isRetryable() {
            assertEquals(SubTaskResult.ErrorType.RETRYABLE_FAILED,
                    GraphExecutionService.classifyException(
                            new RuntimeException("outer",
                                    new RuntimeException("mid",
                                            new SocketTimeoutException("read timed out")))));
        }

        @Test
        @DisplayName("多层 cause 链无可重试异常 → FATAL_FAILED")
        void deepCauseChainNoRetryable_isFatal() {
            assertEquals(SubTaskResult.ErrorType.FATAL_FAILED,
                    GraphExecutionService.classifyException(
                            new RuntimeException("outer",
                                    new RuntimeException("mid",
                                            new IOException("File not found")))));
        }

        // ---------- 场景 4：边界条件 ----------

        @Test
        @DisplayName("cause 指向自身 → 不递归，返回 FATAL_FAILED")
        void selfReferencingCause_isFatal() {
            Throwable ex = new RuntimeException("self");
            // 设置 getCause() 返回自身（模拟极端情况）
            Throwable self = new Throwable("self") {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };
            assertEquals(SubTaskResult.ErrorType.FATAL_FAILED,
                    GraphExecutionService.classifyException(self));
        }
    }
}
