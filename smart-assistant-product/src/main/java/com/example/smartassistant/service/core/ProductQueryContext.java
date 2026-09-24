package com.example.smartassistant.service.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Read-only search context. Historical requests are evidence, never write authorization. */
final class ProductQueryContext {
    private ProductQueryContext() {}

    static String current(String question) {
        if (question == null) return "";
        int marker = question.indexOf("[对话上下文]");
        return marker < 0 ? question : question.substring(0, marker).trim();
    }

    static List<String> turns(String question) {
        if (question == null || question.isBlank()) return List.of();
        int marker = question.indexOf("[对话上下文]");
        if (marker < 0) return List.of(question);
        String history = question.substring(marker);
        int instructions = history.indexOf("请延续上一轮");
        if (instructions >= 0) history = history.substring(0, instructions);
        String[] entries = history.split("用户：");
        List<String> turns = new ArrayList<>();
        for (int i = Math.max(1, entries.length - 10); i < entries.length; i++) {
            if (!entries[i].isBlank()) turns.add(entries[i].trim());
        }
        turns.add(question.substring(0, marker).trim());
        return List.copyOf(turns);
    }

    static String latest(String question, Predicate<String> relevant) {
        List<String> turns = turns(question);
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (relevant.test(turns.get(i))) return turns.get(i);
        }
        return "";
    }
}
