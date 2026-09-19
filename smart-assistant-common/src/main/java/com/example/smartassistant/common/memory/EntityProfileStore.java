package com.example.smartassistant.common.memory;

import java.util.Map;

/** Storage boundary: capture before extraction, commit using that same generation. */
public interface EntityProfileStore {
    long capture(Long userId);
    void save(Long userId, long generation, Map<String, String> facts);
    Map<String, String> read(Long userId);
}
