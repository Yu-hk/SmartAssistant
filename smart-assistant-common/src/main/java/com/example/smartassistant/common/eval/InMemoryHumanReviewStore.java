/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 内存版人工复核存储 — 适用于单进程评测；分布式场景替换为 Redis/PG 实现。
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public class InMemoryHumanReviewStore implements HumanReviewStore {

    private final Map<String, HumanReviewTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(HumanReviewTask task) {
        tasks.put(task.taskId(), task);
    }

    @Override
    public List<HumanReviewTask> pending() {
        return tasks.values().stream()
                .filter(t -> t.status() == HumanReviewTask.Status.PENDING)
                .collect(Collectors.toCollection(CopyOnWriteArrayList::new));
    }

    @Override
    public void resolve(String taskId, HumanReviewTask.Status decision, String note) {
        HumanReviewTask t = tasks.get(taskId);
        if (t != null) {
            tasks.put(taskId, new HumanReviewTask(
                    t.taskId(), t.caseId(), t.agentName(), t.input(), t.actualResponse(),
                    t.ruleScore(), t.llmScore(), t.reason(), t.createdAt(),
                    decision, note == null ? "" : note));
        }
    }
}
