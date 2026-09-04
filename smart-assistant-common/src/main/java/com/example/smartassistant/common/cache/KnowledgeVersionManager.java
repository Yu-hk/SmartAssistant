/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Shared knowledge-document version used to invalidate document-bound caches.
 *
 * <p>The version is stored in Redis so every Consumer instance observes the same
 * value. Successful document ingestion increments it atomically; cached
 * business answers are reusable only while their captured version matches.</p>
 */
public class KnowledgeVersionManager {

    public static final String VERSION_KEY = "a2a:knowledge:version";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVersionManager.class);
    private static final long LOCAL_CACHE_MS = 5_000L;

    private final Supplier<Long> versionReader;
    private final LongSupplier versionIncrementer;
    private volatile long cachedVersion = -1L;
    private volatile long lastFetchTime;

    public KnowledgeVersionManager(Supplier<Long> versionReader,
                                   LongSupplier versionIncrementer) {
        this.versionReader = versionReader;
        this.versionIncrementer = versionIncrementer;
    }

    public long getCurrentVersion() {
        long now = System.currentTimeMillis();
        if (cachedVersion < 0 || now - lastFetchTime > LOCAL_CACHE_MS) {
            refreshCurrentVersion();
        }
        return Math.max(cachedVersion, 0L);
    }

    /** Read Redis immediately, bypassing the five-second local snapshot. */
    public synchronized long refreshCurrentVersion() {
        try {
            Long value = versionReader.get();
            cachedVersion = value != null ? value : 0L;
            lastFetchTime = System.currentTimeMillis();
        } catch (Exception error) {
            log.warn("[KnowledgeVersion] Failed to read shared version: {}", error.getMessage());
            if (cachedVersion < 0) cachedVersion = 0L;
        }
        return cachedVersion;
    }

    /** Atomically advance the shared version after a successful document change. */
    public synchronized long incrementVersion() {
        try {
            long version = versionIncrementer.getAsLong();
            cachedVersion = Math.max(version, 0L);
            lastFetchTime = System.currentTimeMillis();
            log.info("[KnowledgeVersion] Knowledge document version advanced to v{}", cachedVersion);
        } catch (Exception error) {
            log.error("[KnowledgeVersion] Failed to advance shared version", error);
            throw error;
        }
        return cachedVersion;
    }
}
