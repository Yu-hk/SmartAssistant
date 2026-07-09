/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.graph.EntityNode;
import com.example.smartassistant.common.rag.graph.EntityRelation;
import com.example.smartassistant.common.rag.graph.KnowledgeGraphService;
import com.example.smartassistant.common.rag.graph.NoopEntityExtractor;
import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LightRagSearchHandler 单元测试。
 *
 * <p>直接构造内存图谱（Noop 抽取器，无 LLM 调用），验证图检索路径的
 * 三元组抽取、中英文分词召回、空图降级与上限保护。</p>
 */
class LightRagSearchHandlerTest {

    private static KnowledgeGraphService graphWith(List<EntityNode> nodes, List<EntityRelation> relations) {
        KnowledgeGraphService g = new KnowledgeGraphService();
        g.setExtractor(new NoopEntityExtractor()); // 无 LLM
        g.addNodes(nodes);
        g.addRelations(relations);
        return g;
    }

    @Test
    @DisplayName("图检索：命中实体并扩展 1 跳关系三元组")
    void retrievesTriplesFromGraph() {
        EntityNode phone = new EntityNode("ent:phone", "手机数码", "category", "商品大类", null, null);
        EntityNode iphone = new EntityNode("ent:iphone", "iPhone 15", "product", "苹果手机", null, null);
        EntityNode warranty = new EntityNode("ent:warranty", "保修", "policy", "三包保修", null, null);

        EntityRelation r1 = new EntityRelation("rel:1", "ent:iphone", "ent:phone", "属于", "品类归属", 0.9, null);
        EntityRelation r2 = new EntityRelation("rel:2", "ent:warranty", "ent:phone", "适用", "手机享保修", 0.8, null);

        KnowledgeGraphService graph = graphWith(
                List.of(phone, iphone, warranty), List.of(r1, r2));

        LightRagSearchHandler handler = new LightRagSearchHandler(graph);
        List<String> items = handler.retrieve("手机保修");

        assertFalse(items.isEmpty(), "应返回召回条目");
        assertTrue(items.get(0).contains("实体关系图检索"), "首行应为标题");
        // 应覆盖「手机数码 属于 iPhone 15」这条关系
        boolean hasBelong = items.stream().anyMatch(s -> s.contains("属于") && s.contains("iPhone 15"));
        assertTrue(hasBelong, "应抽取出『手机数码 属于 iPhone 15』三元组");
        // 应覆盖「保修 适用 手机数码」
        boolean hasApply = items.stream().anyMatch(s -> s.contains("适用") && s.contains("保修"));
        assertTrue(hasApply, "应抽取出『保修 适用 手机数码』三元组");
    }

    @Test
    @DisplayName("图检索：英文/数字 token 也能召回")
    void retrievesByEnglishToken() {
        EntityNode iphone = new EntityNode("ent:iphone", "iPhone 15", "product", "苹果手机", null, null);
        EntityNode price = new EntityNode("ent:price", "价格", "policy", "价格政策", null, null);
        EntityRelation r = new EntityRelation("rel:9", "ent:price", "ent:iphone", "关联", "iPhone 有价格", 0.7, null);

        KnowledgeGraphService graph = graphWith(List.of(iphone, price), List.of(r));
        LightRagSearchHandler handler = new LightRagSearchHandler(graph);

        List<String> items = handler.retrieve("iPhone 价格");
        assertFalse(items.isEmpty());
        assertTrue(items.stream().anyMatch(s -> s.contains("关联") && s.contains("iPhone 15")));
    }

    @Test
    @DisplayName("空图降级：不产出召回路径，且不抛异常")
    void degradesOnEmptyGraph() {
        KnowledgeGraphService empty = new KnowledgeGraphService(); // nodeCount == 0
        LightRagSearchHandler handler = new LightRagSearchHandler(empty);

        assertTrue(handler.retrieve("任意查询").isEmpty(), "空图应返回空结果");

        RagSearchContext ctx = new RagSearchContext("任意查询");
        handler.handle(ctx);
        assertTrue(ctx.getPathResults().isEmpty(), "空图时不应写入任何路径结果");
    }

    @Test
    @DisplayName("图未注入时以空操作运行，不抛异常")
    void degradesWhenGraphNotInjected() {
        LightRagSearchHandler handler = new LightRagSearchHandler(null);
        RagSearchContext ctx = new RagSearchContext("手机保修");
        assertDoesNotThrow(() -> handler.handle(ctx));
        assertTrue(ctx.getPathResults().isEmpty());
    }

    @Test
    @DisplayName("三元组数量受上限保护")
    void respectsTripleCap() {
        // 一个 hub 实体挂 20 条关系，验证三元组不超过上限
        EntityNode hub = new EntityNode("ent:hub", "hub", "category", "中心实体", null, null);
        var nodes = new java.util.ArrayList<EntityNode>();
        var relations = new java.util.ArrayList<EntityRelation>();
        nodes.add(hub);
        for (int i = 0; i < 20; i++) {
            EntityNode leaf = new EntityNode("ent:leaf" + i, "leaf" + i, "product", "叶子", null, null);
            nodes.add(leaf);
            relations.add(new EntityRelation("rel:h" + i, "ent:hub", "ent:leaf" + i, "关联", "desc", 0.5, null));
        }
        KnowledgeGraphService graph = graphWith(nodes, relations);
        LightRagSearchHandler handler = new LightRagSearchHandler(graph);

        List<String> items = handler.retrieve("hub");
        // items 含 1 行标题 + N 条三元组，三元组数应 <= 上限
        int triples = items.size() - 1;
        assertTrue(triples <= 15, "三元组数量应受上限保护，实际=" + triples);
        assertEquals(15, triples, "满关系时应为上限值");
    }

    @Test
    @DisplayName("Order 置于 Graph(50) 与 RrfFusion(100) 之间")
    void orderIsBetweenGraphAndFusion() {
        LightRagSearchHandler handler = new LightRagSearchHandler(new KnowledgeGraphService());
        assertEquals(60, handler.getOrder());
    }
}
