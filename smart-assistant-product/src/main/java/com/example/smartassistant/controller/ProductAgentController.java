package com.example.smartassistant.controller;

import com.example.smartassistant.service.agent.StreamingProductAgentService;
import com.example.smartassistant.service.graph.ProductGraphService;
import com.example.smartassistant.spi.ProductBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Router 统一 Agent HTTP 契约的 Product 实现。
 * 明确的商品价格/库存/颜色查询直接读取业务数据，未命中商品时再进入生成式 Agent。
 */
@RestController
@RequestMapping("/api/order/agent")
public class ProductAgentController {

    private static final Logger log = LoggerFactory.getLogger(ProductAgentController.class);

    private final ProductBackend productBackend;
    private final ProductGraphService productGraphService;
    private final StreamingProductAgentService streamingAgentService;

    public ProductAgentController(ProductBackend productBackend,
                                  ProductGraphService productGraphService,
                                  StreamingProductAgentService streamingAgentService) {
        this.productBackend = productBackend;
        this.productGraphService = productGraphService;
        this.streamingAgentService = streamingAgentService;
    }

    @PostMapping("/process")
    public String processQuestion(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return "问题不能为空";
        }

        String productCode = productGraphService.matchProduct(question);
        if (productCode != null && isStructuredProductQuery(question)) {
            log.info("[ProductAgent] 结构化商品查询直返: code={}, requestId={}",
                    productCode, request.get("requestId"));
            return productBackend.queryProductInfo(productCode);
        }
        return streamingAgentService.execute(question);
    }

    private boolean isStructuredProductQuery(String question) {
        return question.contains("价格") || question.contains("多少钱") || question.contains("库存")
                || question.contains("颜色") || question.contains("规格")
                || question.contains("商品") || question.contains("产品");
    }
}
