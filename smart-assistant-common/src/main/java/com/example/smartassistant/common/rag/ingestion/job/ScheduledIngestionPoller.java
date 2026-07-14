/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import com.example.smartassistant.common.rag.properties.RagProductionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 定时扫描摄入轮询器（REQ-1 触发机制之「兜底扫描目录」）。
 * <p>
 * 按 {@code app.rag.ingest.scan-dir} 配置的周期扫描目录，将其中新增文件提交为摄取任务。
 * 同一（路径 + 租户）的活跃任务由 {@link IngestionJobManager} 去重，因此重复扫描安全。
 * 当触发方式不包含 {@code schedule} 或扫描目录为空时，本轮跳过（不报错）。
 * </p>
 */
public class ScheduledIngestionPoller {

    private static final Logger log = LoggerFactory.getLogger(ScheduledIngestionPoller.class);

    private static final List<String> SUPPORTED_EXT = List.of(
            "pdf", "docx", "doc", "html", "htm", "md", "markdown", "txt");

    private final IngestionJobManager manager;
    private final RagProductionProperties properties;

    public ScheduledIngestionPoller(IngestionJobManager manager, RagProductionProperties properties) {
        this.manager = manager;
        this.properties = properties;
    }

    /**
     * 定时轮询（默认每 30s 一次）。仅在触发方式含 {@code schedule} 且扫描目录非空时生效。
     */
    @Scheduled(initialDelayString = "15000", fixedDelayString = "30000")
    public void poll() {
        if (!triggerIncludesSchedule()) return;
        String scanDir = properties.getRag().getIngest().getScanDir();
        if (scanDir == null || scanDir.isBlank()) return;
        Path dir = Path.of(scanDir);
        if (!Files.isDirectory(dir)) {
            log.debug("[IngestPoller] 扫描目录不存在，跳过: {}", scanDir);
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .forEach(p -> {
                        try {
                            String path = p.toAbsolutePath().toString();
                            manager.submit(path, "", "v1");
                            log.info("[IngestPoller] 扫描提交摄入: {}", path);
                        } catch (Exception e) {
                            log.warn("[IngestPoller] 提交失败: {} ({})", p, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("[IngestPoller] 扫描目录异常: {}", e.getMessage());
        }
    }

    private boolean triggerIncludesSchedule() {
        String trigger = properties.getRag().getIngest().getTrigger();
        if (trigger == null) return false;
        for (String t : trigger.split(",")) {
            if ("schedule".equalsIgnoreCase(t.trim())) return true;
        }
        return false;
    }

    private boolean isSupported(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXT.contains(ext);
    }
}
