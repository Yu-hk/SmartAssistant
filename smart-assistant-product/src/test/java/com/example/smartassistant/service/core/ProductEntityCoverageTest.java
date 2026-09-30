package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.*;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Balanced synthetic capability/safety corpus. Outcome counts are NOT real traffic coverage. */
class ProductEntityCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test void reportsDirectClarifiedWrongLinkedAndUnhandledSeparately() throws Exception {
        JsonNode dataset;
        try (var stream = getClass().getResourceAsStream("/product-entity-coverage.json")) {
            dataset = JSON.readTree(Objects.requireNonNull(stream));
        }
        var backend = mock(ProductBackend.class);
        var identities = List.of(
                new ProductIdentity("AP", "AirPods Pro（第二代）", List.of("苹果降噪耳机"), "Apple", "AirPods", "AirPods Pro", "第二代", "Pro", "", "fixture"),
                new ProductIdentity("MB", "MacBook Air M3", List.of("苹果轻薄本")),
                new ProductIdentity("WH", "Sony WH-1000XM5", List.of("XM5")),
                new ProductIdentity("WF", "Sony WF-1000XM5", List.of("XM5")),
                new ProductIdentity("NO-DATA", "资料缺失耳机", List.of()));
        when(backend.listProductIdentities()).thenReturn(identities);
        when(backend.lookupFacts(anyString())).thenReturn(new ProductBackend.FactLookup(List.of(), false));
        fact(backend, "AP", "AirPods Pro（第二代）", "1999", "5.3", "白色");
        fact(backend, "MB", "MacBook Air M3", "8999", "1240", "银色");
        when(backend.lookupFacts("NO-DATA")).thenReturn(new ProductBackend.FactLookup(List.of(
                new ProductBackend.ProductFact("NO-DATA", "资料缺失耳机", null, "", "", "")), false));
        var service = new ProductFactQueryService(backend, new ProductEntityResolver(backend));
        List<Map<String,Object>> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<String,Integer> counts = new LinkedHashMap<>();
        for (String kind : List.of("DIRECT", "CLARIFY", "WRONG_MATCH", "UNHANDLED")) counts.put(kind, 0);
        Map<String,Map<String,Integer>> categories = new LinkedHashMap<>();
        for (var gold : dataset.get("cases")) {
            String id = gold.path("id").asText(), expected = gold.path("expectedOutcome").asText();
            String question = gold.path("question").asText();
            var response = service.query(question, strings(gold.path("history")), "coverage-" + id);
            boolean handled = Boolean.TRUE.equals(response.data().get("handled"));
            boolean clarify = Boolean.TRUE.equals(response.data().get("clarificationRequired"));
            @SuppressWarnings("unchecked")
            List<ProductEntityResolver.Mention> entities = (List<ProductEntityResolver.Mention>) response.data().getOrDefault("entityResolutions", List.of());
            Set<String> actualCodes = new LinkedHashSet<>();
            entities.stream().filter(e -> e.status() == ProductEntityResolver.Status.RESOLVED).forEach(e -> actualCodes.add(e.code()));
            Set<String> expectedCodes = new LinkedHashSet<>(strings(gold.path("expectedCodes")));
            boolean wrongLink = actualCodes.stream().anyMatch(c -> !expectedCodes.contains(c));
            boolean unknownEvidence = JSON.valueToTree(response.data().getOrDefault("productEvidence", List.of()))
                    .findValues("known").stream().anyMatch(value -> value.isBoolean() && !value.asBoolean());
            String outcome = wrongLink ? "WRONG_MATCH" : !handled ? "UNHANDLED" : clarify || unknownEvidence ? "CLARIFY" : "DIRECT";
            List<String> reasons = new ArrayList<>();
            if (!outcome.equals(expected)) reasons.add("outcome expected=" + expected + " actual=" + outcome);
            if (!actualCodes.equals(expectedCodes)) reasons.add("codes expected=" + expectedCodes + " actual=" + actualCodes);
            for (String value : strings(gold.path("contains"))) if (!response.answer().contains(value)) reasons.add("missing answer fragment: " + value);
            for (String value : strings(gold.path("notContains"))) if (response.answer().contains(value)) reasons.add("forbidden answer fragment: " + value);
            if (!expected.equals("DIRECT") && response.data().containsKey("orderQuote")) reasons.add("non-direct result exposed an order quote");
            @SuppressWarnings("unchecked")
            List<ProductSemanticQueryPlan.Task> tasks = (List<ProductSemanticQueryPlan.Task>) response.data().getOrDefault("entityTasks", List.of());
            for (var task : tasks) {
                String code = task.entity().code();
                if (gold.path("fields").has(code)) {
                    var fields = new HashSet<>(task.fields().stream().map(Enum::name).toList());
                    if (!fields.equals(new HashSet<>(strings(gold.path("fields").get(code))))) reasons.add("field binding: " + code);
                }
                if (gold.path("quantities").has(code) && task.quantity() != gold.path("quantities").get(code).asInt()) reasons.add("quantity binding: " + code);
            }
            if (response.status() != AgentExecutionResponse.Status.SUCCEEDED) reasons.add("unexpected infrastructure status: " + response.status());
            counts.merge(outcome, 1, Integer::sum);
            var category = categories.computeIfAbsent(gold.path("category").asText(), ignored -> new LinkedHashMap<>());
            category.merge("cases", 1, Integer::sum);
            category.merge("contractPassed", reasons.isEmpty() ? 1 : 0, Integer::sum);
            if (!reasons.isEmpty()) failures.add(id + ": " + String.join("; ", reasons));
            rows.add(Map.of("id", id, "question", question, "expectedOutcome", expected, "actualOutcome", outcome,
                    "codes", actualCodes, "contractPassed", reasons.isEmpty(), "violations", reasons, "answer", response.answer()));
        }
        int size = dataset.get("cases").size();
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("dataset", dataset.path("dataset").asText());
        report.put("provenance", dataset.path("provenance").asText());
        report.put("limitations", dataset.path("limitations").asText());
        report.put("cases", size); report.put("outcomes", counts); report.put("categories", categories);
        report.put("contractPassed", size - failures.size()); report.put("contractPassRate", (double)(size - failures.size()) / size);
        report.put("wrongLinkRate", (double)counts.get("WRONG_MATCH") / size);
        report.put("caseResults", rows);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/product-entity-coverage.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(size).isGreaterThanOrEqualTo(60);
        assertThat(failures).describedAs("Synthetic coverage contract violations (full report in target)").isEmpty();
    }
    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>(); if (node.isArray()) node.forEach(n -> values.add(n.asText())); return values;
    }
    private static void fact(ProductBackend backend, String code, String name, String price, String weight, String color) {
        when(backend.lookupFacts(code)).thenReturn(new ProductBackend.FactLookup(List.of(new ProductBackend.ProductFact(code, name,
                new BigDecimal(price), "充足", "目录规格", color, new ProductFeatures(new BigDecimal(weight), new BigDecimal("6"),
                "audio_anc_on", true, "synthetic-fixture", "2026-09-30"))), false));
    }
}
