package com.example.smartassistant.common.jev;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/** Versioned, domain-owned decision questions; no routing vocabulary is embedded in Java. */
public final class JevQuestionCatalog {
    private JevQuestionCatalog() {}

    public static Map<String, Object> load(Class<?> owner, String resource) {
        try (var input = owner.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing Jev question catalog: " + resource);
            Map<String, Object> questions = new ObjectMapper().readValue(input, new TypeReference<>() {});
            if (questions == null || questions.isEmpty())
                throw new IllegalStateException("Empty Jev question catalog: " + resource);
            return Map.copyOf(questions);
        } catch (IOException error) {
            throw new IllegalStateException("Invalid Jev question catalog: " + resource, error);
        }
    }
}
