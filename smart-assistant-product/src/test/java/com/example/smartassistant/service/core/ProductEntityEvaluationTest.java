package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Small deterministic entity golden suite; deliberately not a Ragas answer-quality score. */
class ProductEntityEvaluationTest {
    private record Gold(String question, List<String> mentions, List<String> codes, List<Set<MultiProductQueryPlan.Field>> fields) { }
    @Test void writesIndependentMentionLinkAndFieldBindingMetrics() throws Exception {
        var backend = mock(ProductBackend.class);
        when(backend.listProductIdentities()).thenReturn(List.of(
                new ProductIdentity("AP", "AirPods Pro（第二代）", List.of("苹果耳机")),
                new ProductIdentity("MB", "MacBook Air M3", List.of("苹果轻薄本"))));
        var resolver = new ProductEntityResolver(backend);
        var price = Set.of(MultiProductQueryPlan.Field.PRICE);
        var weight = Set.of(MultiProductQueryPlan.Field.WEIGHT);
        List<Gold> suite = List.of(
                new Gold("AirPods Pro价格？", List.of("AirPods Pro"), List.of("AP"), List.of(price)),
                new Gold("苹果耳机重量？", List.of("苹果耳机"), List.of("AP"), List.of(weight)),
                new Gold("airpodspro多少钱？", List.of("airpodspro"), List.of("AP"), List.of(price)),
                new Gold("想了解AirPods Pro重量和MacBook Air M3价格？", List.of("AirPods Pro", "MacBook Air M3"), List.of("AP", "MB"), List.of(weight, price)),
                new Gold("AirPods Pro和MacBook Air M3合计多少钱？", List.of("AirPods Pro", "MacBook Air M3"), List.of("AP", "MB"), List.of(price, price)),
                new Gold("QA未知耳机和AirPods Pro价格？", List.of("QA未知耳机", "AirPods Pro"), List.of("", "AP"), List.of(price, price)),
                new Gold("AirPods Pro和QA未知耳机价格？", List.of("AirPods Pro", "QA未知耳机"), List.of("AP", ""), List.of(price, price)),
                new Gold("AirPods Pro Max价格？", List.of("AirPods Pro Max"), List.of(""), List.of(price)),
                new Gold("ＡｉｒＰｏｄｓ　Ｐｒｏ重量？", List.of("ＡｉｒＰｏｄｓ　Ｐｒｏ"), List.of("AP"), List.of(weight)),
                new Gold("苹果轻薄本价格？", List.of("苹果轻薄本"), List.of("MB"), List.of(price)));
        int found = 0, expected = 0, matched = 0, links = 0, bindings = 0;
        for (var gold : suite) {
            var actual = resolver.resolve(gold.question(), List.of(), "golden");
            found += actual.size(); expected += gold.mentions().size();
            var plan = ProductSemanticQueryPlan.parse(gold.question(), actual).orElseThrow();
            for (int i = 0; i < gold.mentions().size(); i++) {
                String mention = gold.mentions().get(i);
                int start = gold.question().indexOf(mention);
                var entity = actual.stream().filter(m -> m.start() == start && m.end() == start + mention.length()).findFirst();
                if (entity.isPresent()) {
                    matched++;
                    if (entity.get().code().equals(gold.codes().get(i))) links++;
                    var task = plan.tasks().stream().filter(t -> t.entity().equals(entity.get())).findFirst().orElseThrow();
                    if (task.fields().equals(gold.fields().get(i))) bindings++;
                }
            }
        }
        double precision = (double) matched / found, recall = (double) matched / expected;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("dataset", "synthetic-catalog-entity-v1"); metrics.put("questions", suite.size()); metrics.put("mentions", expected);
        metrics.put("mentionPrecision", precision); metrics.put("mentionRecall", recall);
        metrics.put("mentionF1", 2 * precision * recall / (precision + recall));
        metrics.put("entityLinkAccuracy", (double) links / expected); metrics.put("fieldBindingAccuracy", (double) bindings / expected);
        metrics.put("limitations", "small synthetic set; no live Jev accuracy, Ragas metrics or production generalization claim");
        Path target = Path.of("target", "product-entity-metrics.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
        assertThat(precision).isEqualTo(1); assertThat(recall).isEqualTo(1);
        assertThat(links).isEqualTo(expected); assertThat(bindings).isEqualTo(expected);
    }
}
