package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskPlannerServiceTest {

    @Test
    void preservesOriginalScenarioForModelAssignedProductNode() {
        var product = new IntentGraph.IntentNode(
                "t1", "查询当前热门商品列表", "product_agent", List.of(), "返回目录商品");
        var order = new IntentGraph.IntentNode(
                "t2", "说明订单操作", "order_agent", List.of(), "返回操作说明");
        String original = "查询热门商品，但目录证据不足时不要宣称适合视频会议";

        List<IntentGraph.IntentNode> scoped = TaskPlannerService.preserveProductContext(
                List.of(product, order), original);

        assertTrue(scoped.getFirst().getDescription().contains(original));
        assertTrue(scoped.getFirst().getDescription().contains("用户原始商品需求与约束"));
        assertEquals("说明订单操作", scoped.get(1).getDescription());
    }
}
