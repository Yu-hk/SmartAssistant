package com.example.smartassistant.service.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.smartassistant.service.core.MultiProductQueryPlan.*;

class ProductFieldCoverageTest {
    private static MultiProductQueryPlan plan() {
        return new MultiProductQueryPlan("甲和乙价格与重量", List.of("甲", "乙"),
                EnumSet.of(Field.PRICE, Field.WEIGHT), Relation.INDEPENDENT, "", null, BudgetScope.NONE);
    }
    private static Map<String, Object> row(String name, String code, Map<String, Object> fields) {
        return Map.of("requestedProduct", name, "productCode", code, "status", "RESOLVED", "fields", fields);
    }
    private static Map<String, Object> known(String text) { return Map.of("known", true, "evidence", text); }
    @Test void missingOtherProductCannotBeHiddenByFirstProduct() {
        var result = ProductFieldCoverage.inspect(plan(), null, List.of(
                row("甲", "A", Map.of("PRICE", known("10元"), "WEIGHT", known("20克")))));
        assertEquals(2, result.known()); assertEquals(2, result.unresolved()); assertFalse(result.complete());
    }
    @Test void explicitUnknownIsTerminalNotMissing() {
        var result = ProductFieldCoverage.inspect(plan(), null, List.of(
                row("乙", "B", Map.of("PRICE", known("30元"), "WEIGHT", Map.of("known", false, "evidence", ""))),
                row("甲", "A", Map.of("PRICE", known("10元"), "WEIGHT", known("20克")))));
        assertEquals(3, result.known()); assertEquals(1, result.unknown()); assertEquals(0, result.missing());
        assertTrue(result.complete());
    }
    @Test void emptyKnownOrContradictoryUnknownCannotPass() {
        var result = ProductFieldCoverage.inspect(plan(), null, List.of(
                row("甲", "A", Map.of("PRICE", known(""), "WEIGHT", Map.of("known", false, "evidence", "100克"))),
                row("乙", "B", Map.of("PRICE", known("20元")))));
        assertEquals(3, result.missing()); assertFalse(result.complete());
    }
    @Test void duplicateEvidenceDoesNotPretendToResolveIdentity() {
        var row = row("甲", "A", Map.of("PRICE", known("10元")));
        var result = ProductFieldCoverage.inspect(plan(), null, List.of(row, row));
        assertEquals(4, result.unresolved());
    }
    @Test void perEntityFieldsNeverUseUnionAsRequiredFields() {
        var a = new ProductEntityResolver.Mention("甲", 0, 1, ProductEntityResolver.Status.RESOLVED,
                "A", List.of(), "CATALOG", null);
        var b = new ProductEntityResolver.Mention("乙", 2, 3, ProductEntityResolver.Status.RESOLVED,
                "B", List.of(), "CATALOG", null);
        var semantic = new ProductSemanticQueryPlan(plan(), List.of(
                new ProductSemanticQueryPlan.Task(a, Set.of(Field.WEIGHT), 1, List.of()),
                new ProductSemanticQueryPlan.Task(b, Set.of(Field.PRICE), 1, List.of())), "");
        var result = ProductFieldCoverage.inspect(plan(), semantic, List.of(
                row("甲", "A", Map.of("WEIGHT", known("20克"))), row("乙", "B", Map.of("PRICE", known("30元")))));
        assertEquals(2, result.requested()); assertTrue(result.complete());
    }
    @Test void wrongIdentityIsNotCovered() {
        var mention = new ProductEntityResolver.Mention("甲", 0, 1, ProductEntityResolver.Status.RESOLVED,
                "EXPECTED", List.of(), "CATALOG", null);
        var single = new MultiProductQueryPlan("甲价格", List.of("甲"), EnumSet.of(Field.PRICE),
                Relation.INDEPENDENT, "", null, BudgetScope.NONE);
        var semantic = new ProductSemanticQueryPlan(single, List.of(
                new ProductSemanticQueryPlan.Task(mention, Set.of(Field.PRICE), 1, List.of())), "");
        assertEquals(1, ProductFieldCoverage.inspect(single, semantic, List.of(
                row("甲", "WRONG", Map.of("PRICE", known("10元"))))).unresolved());
    }
}
