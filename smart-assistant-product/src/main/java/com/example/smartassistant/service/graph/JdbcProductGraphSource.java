package com.example.smartassistant.service.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** Loads the product graph from the live catalog and product_relations table. */
@Component
public class JdbcProductGraphSource implements ProductGraphSource {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductGraphSource.class);

    private final JdbcTemplate jdbcTemplate;
    private final List<String> excludedPrefixes;

    public JdbcProductGraphSource(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                                  @Value("${product.catalog.excluded-code-prefixes:LOAD-PROD-,E2E-PROD-}")
                                  String excludedPrefixes) {
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        this.excludedPrefixes = Arrays.stream(excludedPrefixes.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    @Override
    public GraphSnapshot load() {
        if (jdbcTemplate == null) {
            log.warn("[ProductGraph] 数据库未配置，返回空图而不是示例商品");
            return GraphSnapshot.empty();
        }
        try {
            List<ProductNode> nodes = jdbcTemplate.query("""
                    SELECT product_code, product_name,
                           COALESCE(to_jsonb(p)->>'category', '') AS category,
                           COALESCE(to_jsonb(p)->>'brand', '') AS brand
                      FROM products p
                     ORDER BY product_code
                    """, (rs, rowNum) -> new ProductNode(
                    rs.getString("product_code"), rs.getString("product_name"),
                    rs.getString("category"), rs.getString("brand"))).stream()
                    .filter(node -> excludedPrefixes.stream()
                            .noneMatch(prefix -> node.code().toUpperCase().startsWith(prefix.toUpperCase())))
                    .toList();

            List<ProductRelation> relations;
            try {
                relations = jdbcTemplate.query("""
                        SELECT source_product_code, target_product_code, relation_type, weight
                          FROM product_relations
                         WHERE enabled = TRUE
                         ORDER BY source_product_code, target_product_code
                        """, (rs, rowNum) -> new ProductRelation(
                        rs.getString("source_product_code"), rs.getString("target_product_code"),
                        rs.getString("relation_type"), rs.getDouble("weight")));
            } catch (RuntimeException relationError) {
                log.info("[ProductGraph] 关系表尚无可用数据，商品目录仍正常加载: {}",
                        relationError.getMessage());
                relations = List.of();
            }
            return new GraphSnapshot(nodes, relations);
        } catch (RuntimeException e) {
            log.warn("[ProductGraph] 实时商品图加载失败，返回空图: {}", e.getMessage());
            return GraphSnapshot.empty();
        }
    }
}
