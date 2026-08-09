/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */

package com.example.smartassistant.consumer.service.admin;

import com.example.smartassistant.common.db.DatabaseDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceFaqImportTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DatabaseDialect dialect;

    private AdminService service;

    @BeforeEach
    void setUp() {
        service = new AdminService(jdbcTemplate, dialect);
    }

    @Test
    void importsNewItemsAndOverwritesExistingItemsWithProvenance() {
        when(jdbcTemplate.queryForList(
                startsWith("SELECT id FROM admin_faq"), eq(Long.class), eq("Existing question")))
                .thenReturn(List.of(7L));
        when(jdbcTemplate.queryForList(
                startsWith("SELECT id FROM admin_faq"), eq(Long.class), eq("New question")))
                .thenReturn(List.of());

        AdminService.FaqImportResult result = service.importFaqs(
                "partner-kb.json",
                "json",
                true,
                List.of(
                        Map.of(
                                "category", "order",
                                "question", "Existing question",
                                "answer", "Updated answer",
                                "keywords", "existing"),
                        Map.of(
                                "category", "general",
                                "question", "New question",
                                "answer", "New answer",
                                "keywords", "new")));

        assertEquals(new AdminService.FaqImportResult(2, 1, 1, 0), result);
        verify(jdbcTemplate).update(
                startsWith("UPDATE admin_faq SET category"),
                eq("order"), eq("Existing question"), eq("Updated answer"), eq("existing"),
                eq("partner-kb.json"), eq("json"), eq(7L));
        verify(jdbcTemplate).update(
                startsWith("INSERT INTO admin_faq"),
                eq("general"), eq("New question"), eq("New answer"), eq("new"),
                eq("partner-kb.json"), eq("json"));
    }

    @Test
    void skipsExistingAndRepeatedQuestionsWhenOverwriteIsDisabled() {
        when(jdbcTemplate.queryForList(
                startsWith("SELECT id FROM admin_faq"), eq(Long.class), eq("Existing question")))
                .thenReturn(List.of(7L));

        AdminService.FaqImportResult result = service.importFaqs(
                "partner.csv",
                "csv",
                false,
                List.of(
                        Map.of("question", "Existing question", "answer", "A"),
                        Map.of("question", "existing QUESTION", "answer", "B")));

        assertEquals(new AdminService.FaqImportResult(2, 0, 0, 2), result);
    }

    @Test
    void rejectsUnsupportedSourcesAndOversizedBatches() {
        assertThrows(IllegalArgumentException.class, () -> service.importFaqs(
                "remote.txt", "txt", false,
                List.of(Map.of("question", "Q", "answer", "A"))));

        List<Map<String, String>> oversized = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> Map.of("question", "Q" + index, "answer", "A"))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> service.importFaqs(
                "remote.json", "json", false, oversized));
    }
}
