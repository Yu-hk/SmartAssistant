/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 版本化记忆存储（对标文章①「长短记忆 - 冲突更新机制 - 版本留存」）。
 *
 * <p>设计要点：</p>
 * <ul>
 *     <li><b>冲突判定</b>：相同 {@code category + key} 且取值不同即视为冲突。</li>
 *     <li><b>更新优先级</b>：来源优先级（EXPLICIT > FACT > INFERRED）；
 *         同来源则时间优先（后写入者覆盖先写入者）。</li>
 *     <li><b>版本留存</b>：冲突时不物理删除旧记忆，旧版本标记为
 *         {@link MemoryStatus#SUPERSEDED}（记录 supersededAt / supersededBy），
 *         支持回溯审计。</li>
 * </ul>
 *
 * <p>存储：{@code {basePath}/{userId}/memories.json}（JSON 数组）。</p>
 */
@Service
public class MemoryVersionStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryVersionStore.class);
    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Value("${app.data.dir:data/users}")
    private String basePath;

    // ==================== 写入 ====================

    /**
     * 新增或冲突更新一条记忆。
     *
     * @return 最终生效（ACTIVE）条目的 ID
     */
    public String add(Long userId, String category, String key, String value, MemorySource source) {
        if (userId == null || category == null || key == null || value == null) {
            throw new IllegalArgumentException("userId/category/key/value 均不可为 null");
        }
        List<MemoryEntry> entries = load(userId);
        String memoryKey = category + "|" + key;

        // 查找当前 ACTIVE 的同键条目
        MemoryEntry existing = entries.stream()
                .filter(e -> e.status() == MemoryStatus.ACTIVE && e.memoryKey().equals(memoryKey))
                .findFirst().orElse(null);

        if (existing == null) {
            // 无冲突：新建 ACTIVE v1
            MemoryEntry created = newEntry(null, category, key, value, source, 1);
            entries.add(created);
            save(userId, entries);
            log.info("[Memory] 新增记忆: userId={}, key={}, value={}, source={}, v1",
                    userId, memoryKey, value, source);
            return created.id();
        }

        // 取值相同：仅更新来源（取更高优先级）与时间，不产生新版本
        if (existing.value().equals(value)) {
            MemorySource mergedSource = source.rank() >= existing.source().rank() ? source : existing.source();
            MemoryEntry updated = existing.withSource(mergedSource).withUpdatedAt(LocalDateTime.now());
            replace(entries, updated);
            save(userId, entries);
            return updated.id();
        }

        // 取值冲突：按优先级判定胜负
        boolean newWins = resolveNewWins(existing, source);
        if (!newWins) {
            // 旧记忆优先级更高：保留旧 ACTIVE，丢弃本次写入（不污染历史）
            log.info("[Memory] 冲突保留旧记忆: userId={}, key={}, 旧值='{}'({}) 优先于 新值='{}'({})",
                    userId, memoryKey, existing.value(), existing.source(), value, source);
            return existing.id();
        }

        // 新记忆胜出：旧记忆标记为 SUPERSEDED，新记忆 ACTIVE 且 version+1
        String newId = "mem_" + UUID.randomUUID();
        MemoryEntry superseded = existing.withStatus(MemoryStatus.SUPERSEDED)
                .withSupersededAt(LocalDateTime.now())
                .withSupersededBy(newId);
        replace(entries, superseded);

        MemoryEntry winner = newEntry(newId, category, key, value, source, existing.version() + 1, existing.lineageId());
        entries.add(winner);
        save(userId, entries);
        log.info("[Memory] 冲突更新(版本留存): userId={}, key={}, 旧值='{}'→SUPERSEDED, 新值='{}' v{}",
                userId, memoryKey, existing.value(), value, winner.version());
        return newId;
    }

    /**
     * 冲突胜负判定：来源优先级高者胜；同优先级则时间优先（后写入的新记忆胜）。
     */
    private boolean resolveNewWins(MemoryEntry existing, MemorySource newSource) {
        int newRank = newSource.rank();
        int oldRank = existing.source().rank();
        if (newRank != oldRank) {
            return newRank > oldRank;
        }
        // 来源相同：时间优先（incoming 更新）→ 新记忆胜
        return true;
    }

    // ==================== 读取 ====================

    /** 获取某用户所有当前生效（ACTIVE）记忆；category 为 null 时返回全部 */
    public List<MemoryEntry> getActive(Long userId, String category) {
        List<MemoryEntry> entries = load(userId);
        List<MemoryEntry> result = new ArrayList<>();
        for (MemoryEntry e : entries) {
            if (e.status() == MemoryStatus.ACTIVE && (category == null || e.category().equals(category))) {
                result.add(e);
            }
        }
        return result;
    }

    /** 获取某条记忆血缘的全部版本（含已失效的 SUPERSEDED），用于回溯审计 */
    public List<MemoryEntry> getAllVersions(Long userId, String lineageId) {
        if (lineageId == null) return List.of();
        List<MemoryEntry> result = new ArrayList<>();
        for (MemoryEntry e : load(userId)) {
            if (lineageId.equals(e.lineageId())) result.add(e);
        }
        return result;
    }

    /** 获取全部记忆（ACTIVE + SUPERSEDED），用于审计 */
    public List<MemoryEntry> getHistory(Long userId) {
        return load(userId);
    }

    // ==================== 文件 I/O ====================

    private Path memoryPath(Long userId) {
        return Paths.get(basePath, String.valueOf(userId), "memories.json");
    }

    private List<MemoryEntry> load(Long userId) {
        Path path = memoryPath(userId);
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            List<MemoryEntry> list = objectMapper.readValue(json, new TypeReference<List<MemoryEntry>>() {});
            return list != null ? list : new ArrayList<>();
        } catch (IOException e) {
            log.warn("[Memory] 加载失败: userId={}, error={}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void save(Long userId, List<MemoryEntry> entries) {
        try {
            Path dir = Paths.get(basePath, String.valueOf(userId));
            Files.createDirectories(dir);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
            Files.writeString(memoryPath(userId), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[Memory] 保存失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    private void replace(List<MemoryEntry> entries, MemoryEntry updated) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(updated.id())) {
                entries.set(i, updated);
                return;
            }
        }
        entries.add(updated);
    }

    private MemoryEntry newEntry(String id, String category, String key, String value,
                                 MemorySource source, int version) {
        return newEntry(id, category, key, value, source, version, "ln_" + UUID.randomUUID());
    }

    private MemoryEntry newEntry(String id, String category, String key, String value,
                                 MemorySource source, int version, String lineageId) {
        String eid = id != null ? id : "mem_" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        return new MemoryEntry(eid, lineageId, category, key, value, source, version,
                MemoryStatus.ACTIVE, now, now, null, null);
    }
}
