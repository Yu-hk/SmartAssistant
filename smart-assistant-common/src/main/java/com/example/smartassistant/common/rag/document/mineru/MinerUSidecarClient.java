/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MinerU sidecar 客户端——v1 进程协议实现（sidecar CLI + stdin/stdout JSON）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>常驻进程池</b>：启动时按 {@code warmInstances} 预热 spawn 多个 sidecar 子进程，
 *       模型仅加载一次；后续请求复用进程，避免每次重建模型开销。</li>
 *   <li><b>并发控制</b>：以 {@link ArrayBlockingQueue} 进程槽 + 单槽锁限制并发，天然将并发
 *       上限钳制在 {@code warmInstances}，无需额外信号量。</li>
 *   <li><b>JSON over stdio</b>：每个请求写一行 JSON，读一行 JSON；以 {@code request_id}
 *       关联请求与响应（单槽串行，天然不串扰）。</li>
 *   <li><b>超时 + 重试</b>：单次 {@code timeoutMs} 超时，失败重建进程并重试 1 次；仍失败抛出
 *       {@link DocumentParseException}，由上层（{@link PdfParserRouter}）按
 *       {@code fallbackToPdfbox} 回退 PDFBox。</li>
 * </ul>
 * 客户端为 Spring Bean 时实现 {@link AutoCloseable}，容器销毁时自动回收进程池。
 * </p>
 */
public class MinerUSidecarClient implements MinerUClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MinerUSidecarClient.class);

    private final MinerUProperties properties;
    private final ObjectMapper mapper;

    private volatile boolean started = false;
    private volatile boolean closed = false;

    private List<ProcessSlot> slots;
    private BlockingQueue<ProcessSlot> slotQueue;
    private ExecutorService readExecutor;

    public MinerUSidecarClient(MinerUProperties properties) {
        this(properties, new ObjectMapper());
    }

    public MinerUSidecarClient(MinerUProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    // ==================== MinerUClient ====================

    @Override
    public MinerUParseResponse parse(MinerUParseRequest req) throws DocumentParseException {
        if (req == null || req.getPdf() == null || req.getPdf().isBlank()) {
            throw new DocumentParseException("MinerU 请求缺少 pdf 路径");
        }
        ensureStarted();
        ProcessSlot slot = acquireSlot();
        try {
            return doParseWithRetry(req, slot);
        } finally {
            releaseSlot(slot);
        }
    }

    // ==================== 内部实现 ====================

    /** 原尝试 + 失败重试 1 次（每次重试前重建该进程槽） */
    private MinerUParseResponse doParseWithRetry(MinerUParseRequest req, ProcessSlot slot)
            throws DocumentParseException {
        DocumentParseException last = null;
        int attempts = 2;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                return doParseOnce(req, slot);
            } catch (DocumentParseException e) {
                last = e;
                log.warn("[MinerU] 第 {} 次解析失败: {}", attempt + 1, e.getMessage());
            }
            if (attempt < attempts - 1) {
                try {
                    respawnSlot(slot);
                } catch (DocumentParseException e) {
                    last = e;
                    break;
                }
            }
        }
        throw last != null ? last : new DocumentParseException("MinerU 解析失败（未知原因）");
    }

    /** 单次解析：写请求 → 读响应（带超时）→ 校验 */
    private MinerUParseResponse doParseOnce(MinerUParseRequest req, ProcessSlot slot)
            throws DocumentParseException {
        synchronized (slot.lock) {
            if (slot.process == null || !slot.process.isAlive()) {
                throw new DocumentParseException("MinerU sidecar 进程已退出，需重建");
            }
            try {
                String json = mapper.writeValueAsString(req);
                slot.writer.write(json);
                slot.writer.write('\n');
                slot.writer.flush();
            } catch (IOException e) {
                throw new DocumentParseException("MinerU 写入请求失败: " + e.getMessage(), e);
            }

            String line;
            try {
                line = readLineWithTimeout(slot, properties.getTimeoutMs());
            } catch (TimeoutException e) {
                throw new DocumentParseException("MinerU 解析超时（" + properties.getTimeoutMs() + "ms）");
            } catch (Exception e) {
                throw new DocumentParseException("MinerU 读取响应失败: " + e.getMessage(), e);
            }

            if (line == null) {
                throw new DocumentParseException("MinerU 返回空响应（sidecar 可能已退出）");
            }
            line = line.trim();
            if (line.isEmpty()) {
                throw new DocumentParseException("MinerU 返回空行");
            }
            try {
                MinerUParseResponse resp = mapper.readValue(line, MinerUParseResponse.class);
                if (!resp.isOk()) {
                    throw new DocumentParseException("MinerU 解析返回非 ok 状态: "
                            + resp.getStatus()
                            + (resp.getMessage() != null ? " - " + resp.getMessage() : ""));
                }
                if (req.getRequestId() != null && resp.getRequestId() != null
                        && !req.getRequestId().equals(resp.getRequestId())) {
                    throw new DocumentParseException("MinerU 响应 request_id 不匹配: 期望 "
                            + req.getRequestId() + " 实际 " + resp.getRequestId());
                }
                if (resp.getPages() == null || resp.getPages().isEmpty()) {
                    throw new DocumentParseException("MinerU 返回空页面列表");
                }
                return resp;
            } catch (JsonProcessingException e) {
                throw new DocumentParseException("MinerU 响应 JSON 解析失败: " + e.getMessage(), e);
            }
        }
    }

    /** 在独立守护线程中按超时读取一行响应（超时则打断读线程） */
    private String readLineWithTimeout(ProcessSlot slot, long timeoutMs)
            throws InterruptedException, ExecutionException, TimeoutException {
        Future<String> future = readExecutor.submit(() -> {
            try {
                return slot.reader.readLine();
            } catch (IOException e) {
                return null;
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /** 懒加载预热进程池（首次 parse 时触发；若命令不可用则抛异常，由上层回退） */
    private void ensureStarted() throws DocumentParseException {
        if (started) return;
        synchronized (this) {
            if (started) return;
            if (closed) throw new DocumentParseException("MinerU sidecar 客户端已关闭");
            try {
                int warm = Math.max(1, properties.getWarmInstances());
                slots = new ArrayList<>(warm);
                for (int i = 0; i < warm; i++) {
                    slots.add(new ProcessSlot(startProcess()));
                }
                slotQueue = new ArrayBlockingQueue<>(slots.size());
                slotQueue.addAll(slots);
                readExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
                    private int n = 0;

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "mineru-sidecar-reader-" + (++n));
                        t.setDaemon(true);
                        return t;
                    }
                });
                started = true;
                log.info("[MinerU] sidecar 常驻进程池预热完成: instances={}", slots.size());
            } catch (Exception e) {
                started = false;
                closeQuietly();
                throw new DocumentParseException("MinerU sidecar 进程池预热失败: " + e.getMessage(), e);
            }
        }
    }

    /** 启动一个 sidecar 子进程 */
    private Process startProcess() throws IOException {
        String command = properties.getSidecarCommand();
        if (command == null || command.isBlank()) {
            throw new IOException("MinerU sidecarCommand 未配置");
        }
        String[] cmd = splitCommand(command);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 错误流直接丢弃，避免子进程因 stderr 缓冲而阻塞；启动期异常由调用方在 read 阶段感知
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Map<String, String> env = pb.environment();
        if (properties.getImagesTempDir() != null) {
            env.put("MINERU_IMAGES_DIR", properties.getImagesTempDir());
        }
        return pb.start();
    }

    /** 简单按空白切分命令（v1 不支持含空格路径，部署文档注明） */
    private static String[] splitCommand(String command) {
        return command.trim().split("\\s+");
    }

    /** 重建单个进程槽（销毁旧进程，拉起新进程并重连 stdio） */
    private void respawnSlot(ProcessSlot slot) throws DocumentParseException {
        synchronized (slot.lock) {
            if (slot.process != null) {
                try {
                    slot.process.destroyForcibly();
                } catch (Exception ignore) {
                    // 忽略销毁异常
                }
            }
            try {
                Process p = startProcess();
                slot.process = p;
                slot.reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                slot.writer = new BufferedWriter(
                        new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new DocumentParseException("MinerU sidecar 进程重建失败: " + e.getMessage(), e);
            }
        }
    }

    /** 从进程池获取一个空闲槽（受 timeoutMs 限制） */
    private ProcessSlot acquireSlot() throws DocumentParseException {
        ProcessSlot slot;
        try {
            slot = slotQueue.poll(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentParseException("MinerU 等待进程槽被中断", e);
        }
        if (slot == null) {
            throw new DocumentParseException("MinerU 进程池耗尽，等待超时（"
                    + properties.getTimeoutMs() + "ms）");
        }
        return slot;
    }

    /** 归还进程槽 */
    private void releaseSlot(ProcessSlot slot) {
        if (slotQueue != null) {
            slotQueue.offer(slot);
        }
    }

    /** 静默回收所有资源（不抛异常） */
    private void closeQuietly() {
        if (readExecutor != null) {
            try {
                readExecutor.shutdownNow();
            } catch (Exception ignore) {
                // 忽略
            }
        }
        if (slots != null) {
            for (ProcessSlot s : slots) {
                try {
                    if (s.process != null) s.process.destroyForcibly();
                } catch (Exception ignore) {
                    // 忽略
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            started = false;
        }
        closeQuietly();
        log.info("[MinerU] sidecar 进程池已关闭");
    }

    /** 进程槽：常驻子进程 + 其 stdin/stdout 读写器 + 单槽串行锁 */
    private static final class ProcessSlot {
        final Object lock = new Object();
        volatile Process process;
        volatile BufferedReader reader;
        volatile BufferedWriter writer;

        ProcessSlot(Process process) throws IOException {
            this.process = process;
            this.reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }
    }
}
