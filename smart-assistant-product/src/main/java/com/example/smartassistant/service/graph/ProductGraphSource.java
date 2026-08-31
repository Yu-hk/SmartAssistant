package com.example.smartassistant.service.graph;

import java.util.List;

/** Source-of-truth adapter for product graph nodes and curated relations. */
public interface ProductGraphSource {

    GraphSnapshot load();

    record ProductNode(String code, String name, String category, String brand) {
    }

    record ProductRelation(String sourceCode, String targetCode,
                           String relationType, double weight) {
    }

    record GraphSnapshot(List<ProductNode> nodes, List<ProductRelation> relations) {
        public GraphSnapshot {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            relations = relations == null ? List.of() : List.copyOf(relations);
        }

        public static GraphSnapshot empty() {
            return new GraphSnapshot(List.of(), List.of());
        }
    }
}
