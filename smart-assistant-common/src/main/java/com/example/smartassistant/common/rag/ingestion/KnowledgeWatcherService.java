/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * ⭐ 知识库自动更新服务 — 监听目录文件变化，自动触发知识摄入。
 * <p>
 * 使用 Java {@link WatchService} 实时监听文件新增/修改事件，
 * 配合 {@link #checkDirectory()} 定时兜底扫描（防 WatchService 丢事件）。
 * </p>
 *
 * <p>使用方式（需配置）：</p>
 * <pre>
 * knowledge.watch.enabled=true
 * knowledge.watch.directory=/data/knowledge
 * knowledge.watch.scan-interval-seconds=300
 * </pre>
 */
@Service
public class KnowledgeWatcherService implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeWatcherService.class);

    @Value("${knowledge.watch.enabled:false}")
    private boolean enabled;

    @Value("${knowledge.watch.directory:./data/knowledge}")
    private String watchDirectory;

    @Value("${knowledge.watch.scan-interval-seconds:300}")
    private int scanIntervalSeconds;

    /** 知识摄入服务（延迟注入） */
    private KnowledgeIngestionService ingestionService;

    /** WatchService 实例 */
    private WatchService watchService;

    /** 已注册的目录 */
    private final Set<Path> registeredDirs = ConcurrentHashMap.newKeySet();

    /** 最近处理的文件时间戳（防重复触发） */
    private final ConcurrentHashMap<String, Long> processedFiles = new ConcurrentHashMap<>();

    public KnowledgeWatcherService() {}

    public void setIngestionService(KnowledgeIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!enabled) {
            log.info("[KnowledgeWatcher] 未启用，跳过");
            return;
        }
        startWatching();
    }

    @Override
    public void destroy() throws Exception {
        if (watchService != null) {
            watchService.close();
            log.info("[KnowledgeWatcher] WatchService 已关闭");
        }
    }

    private void startWatching() {
        Path dir = Paths.get(watchDirectory);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("[KnowledgeWatcher] 创建监控目录: {}", dir.toAbsolutePath());
            } catch (IOException e) {
                log.warn("[KnowledgeWatcher] 无法创建监控目录: {}", e.getMessage());
                return;
            }
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            registeredDirs.add(dir);

            // 启动监控线程
            Thread watcherThread = Thread.ofVirtual()
                    .name("knowledge-watcher")
                    .start(this::processWatchEvents);

            log.info("[KnowledgeWatcher] 已启动: dir={}, virtual-thread={}",
                    dir.toAbsolutePath(), watcherThread.threadId());
        } catch (IOException e) {
            log.error("[KnowledgeWatcher] 启动失败: {}", e.getMessage());
        }
    }

    /**
     * 处理 WatchService 事件（虚拟线程，持续运行）。
     */
    private void processWatchEvents() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == OVERFLOW) continue;

                    Path fileName = (Path) event.context();
                    Path fullPath = (Path) key.watchable();
                    Path filePath = fullPath.resolve(fileName);

                    handleFileEvent(kind, filePath);
                }

                if (!key.reset()) {
                    log.warn("[KnowledgeWatcher] WatchKey 失效，停止监控");
                    break;
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /**
     * 定时兜底扫描（防 WatchService 丢事件）。
     */
    @Scheduled(fixedDelayString = "${knowledge.watch.scan-interval-seconds:300}000")
    public void checkDirectory() {
        if (!enabled || ingestionService == null) return;

        Path dir = Paths.get(watchDirectory);
        if (!Files.exists(dir)) return;

        try (var files = Files.walk(dir, 1)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".pdf")
                            || f.toString().endsWith(".docx")
                            || f.toString().endsWith(".md"))
                    .forEach(this::checkAndIngest);
        } catch (IOException e) {
            log.warn("[KnowledgeWatcher] 定时扫描失败: {}", e.getMessage());
        }
    }

    private void handleFileEvent(WatchEvent.Kind<?> kind, Path filePath) {
        if (ingestionService == null) return;

        String ext = getExtension(filePath.toString());
        if (!ingestionService.supports(filePath.toString())) {
            log.debug("[KnowledgeWatcher] 不支持的文件类型: {}", filePath);
            return;
        }

        if (kind == ENTRY_DELETE) {
            log.info("[KnowledgeWatcher] 文件删除: {}", filePath);
            processedFiles.remove(filePath.toString());
            return;
        }

        // CREATE / MODIFY
        checkAndIngest(filePath);
    }

    private void checkAndIngest(Path filePath) {
        if (ingestionService == null) return;

        String path = filePath.toString();
        long lastModified = filePath.toFile().lastModified();

        // 防重复：同一文件 30 秒内不重复处理
        Long lastProcessed = processedFiles.get(path);
        if (lastProcessed != null && (System.currentTimeMillis() - lastProcessed) < 30_000) {
            return;
        }

        processedFiles.put(path, System.currentTimeMillis());
        log.info("[KnowledgeWatcher] 触发知识摄入: file={}", path);

        try {
            var result = ingestionService.parseAndIngest(path);
            log.info("[KnowledgeWatcher] 摄入完成: file={}, docs={}, elapsed={}ms",
                    path, result.docCount(), result.elapsedMs());
        } catch (Exception e) {
            log.error("[KnowledgeWatcher] 摄入失败: file={}, error={}", path, e.getMessage());
        }
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }
}
