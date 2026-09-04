package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.common.skill.SkillPackage;
import com.example.smartassistant.common.skill.SkillPackageManager;
import com.example.smartassistant.router.service.core.ModelRoutingService;
import com.example.smartassistant.router.service.agent.AgentPromptCatalogService;
import com.example.smartassistant.router.service.evaluation.IntentEvaluationService;
import com.example.smartassistant.router.service.prompt.RouterStageAwareService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskAnalysisPromptTest {
    @Test
    void buildsPromptWithoutRecursiveOverloadLoop() throws Exception {
        IntentRetriever retriever = mock(IntentRetriever.class);
        when(retriever.retrieve("question", 3)).thenReturn(List.of());
        when(retriever.buildIntentSection(anyList())).thenReturn("intent-section");
        TaskAnalysisService service = new TaskAnalysisService(
                mock(ModelRoutingService.class),
                mock(IntentEvaluationService.class),
                retriever,
                mock(RouterStageAwareService.class),
                null);
        Field prompt = TaskAnalysisService.class.getDeclaredField("systemPrompt");
        prompt.setAccessible(true);
        prompt.set(service, "base-prompt");
        Method method = TaskAnalysisService.class.getDeclaredMethod(
                "buildDynamicPrompt", String.class, List.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, "question", List.of());
        assertTrue(result.startsWith("base-prompt\n\n## 本次请求可用 Agent（Router 从 Nacos 动态注入）\n"
                + "- route_name=general; source=local; service_name=router-local; "
                + "capabilities=[通用问答]; examples=[\"回答通用问题\"]"));
        assertTrue(result.contains("semantic_cache_category"));
        assertTrue(result.endsWith("intent-section"));
    }

    @Test
    void loadsTaskPlannerRolePromptFromClasspathByDefault() throws Exception {
        TaskAnalysisService service = new TaskAnalysisService(
                mock(ModelRoutingService.class), mock(IntentEvaluationService.class),
                null, mock(RouterStageAwareService.class), null);
        Method method = TaskAnalysisService.class.getDeclaredMethod("resolveSystemPrompt");
        method.setAccessible(true);

        String prompt = (String) method.invoke(service);

        assertTrue(prompt.contains("Role：任务规划专家"));
        assertTrue(prompt.contains("task_steps"));
        assertTrue(prompt.contains("execution_order"));
        assertTrue(prompt.contains("flowchart"));
        assertTrue(prompt.contains("semantic_cache_category"));
        assertTrue(prompt.contains("{{WORKFLOW_OPERATION_CATALOG}}"));
        assertTrue(prompt.contains("operation 仅允许"));
        assertTrue(prompt.contains("DISCOVER_PRODUCTS 输出 data.products"));
        assertTrue(prompt.contains("ANALYZE_PRODUCT_DATA 输出 data.analysis"));
        assertTrue(prompt.contains("RECOMMEND_PRODUCT 输出 data.recommendation"));
        assertTrue(prompt.contains("仅输出一个合法 JSON 对象"));
        assertTrue(prompt.contains("从 Nacos 的健康实例缓存中发现 Agent"));
        assertTrue(prompt.contains("{{NACOS_AGENT_CATALOG}}"));
        assertFalse(prompt.contains("## 商品推荐并下单的强制链路"));
    }

    @Test
    void replacesCatalogPlaceholderWithCurrentNacosSnapshot() throws Exception {
        AgentPromptCatalogService catalogService = mock(AgentPromptCatalogService.class);
        when(catalogService.buildCatalog()).thenReturn(
                "- route_name=product; source=nacos; service_name=product-service; capabilities=[商品查询]");
        TaskAnalysisService service = new TaskAnalysisService(
                mock(ModelRoutingService.class), mock(IntentEvaluationService.class),
                null, mock(RouterStageAwareService.class), catalogService);
        Method method = TaskAnalysisService.class.getDeclaredMethod(
                "buildDynamicPrompt", String.class, List.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(service, "查询商品", List.of());

        assertTrue(prompt.contains("route_name=product"));
        assertTrue(prompt.contains("service_name=product-service"));
        assertFalse(prompt.contains("{{NACOS_AGENT_CATALOG}}"));
    }

    @Test
    void injectsPlanningSkillFromRetrievedIntentWithoutKeywordRouting() throws Exception {
        IntentRetriever retriever = mock(IntentRetriever.class);
        IntentDef product = new IntentDef("PRODUCT", "商品", "商品推荐",
                List.of(), "示例", "相关工具");
        when(retriever.retrieve("任意表达", 3)).thenReturn(List.of(product));
        when(retriever.buildIntentSection(List.of(product))).thenReturn("intent-section");
        TaskAnalysisService service = new TaskAnalysisService(
                mock(ModelRoutingService.class), mock(IntentEvaluationService.class),
                retriever, mock(RouterStageAwareService.class), null);
        Field prompt = TaskAnalysisService.class.getDeclaredField("systemPrompt");
        prompt.setAccessible(true);
        prompt.set(service, "base-prompt");
        SkillPackageManager manager = new SkillPackageManager();
        manager.register(SkillPackage.builder("route-product", "商品链")
                .instruction("DISCOVER_PRODUCTS -> ANALYZE_PRODUCT_DATA -> RECOMMEND_PRODUCT")
                .addTriggerOperation("PRODUCT")
                .build());
        manager.bind("route-product", "router-service");
        Field managerField = TaskAnalysisService.class.getDeclaredField("skillPackageManager");
        managerField.setAccessible(true);
        managerField.set(service, manager);
        Method method = TaskAnalysisService.class.getDeclaredMethod(
                "buildDynamicPrompt", String.class, List.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, "任意表达", List.of());

        assertTrue(result.contains("【技能：route-product@1.0.0"));
        assertTrue(result.contains("DISCOVER_PRODUCTS -> ANALYZE_PRODUCT_DATA -> RECOMMEND_PRODUCT"));
    }
}
