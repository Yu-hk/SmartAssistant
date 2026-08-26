package com.example.smartassistant.consumer.service.agent;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.tool.GifCacheStore;
import com.example.smartassistant.consumer.config.McpTableWhitelistConfig;
import com.example.smartassistant.consumer.service.cache.SqlQueryCache;
import com.example.smartassistant.consumer.service.monitoring.SqlPerformanceMonitor;
import com.example.smartassistant.consumer.service.monitoring.SqlReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpAgentServiceDataAnalysisPromptTest {

    @Test
    void wrapsBusinessQueryWithDataAnalysisPromptBeforeAgentExecution() {
        GifCacheStore gifCacheStore = mock(GifCacheStore.class);
        when(gifCacheStore.getAll()).thenReturn(Map.of());
        McpAgentService service = new McpAgentService(
                mock(ChatModel.class),
                mock(JdbcTemplate.class),
                mock(SqlPerformanceMonitor.class),
                mock(SqlQueryCache.class),
                mock(SqlReviewService.class),
                mock(McpTableWhitelistConfig.class),
                gifCacheStore,
                mock(ApplicationContext.class),
                new PromptManager());
        SmartReActAgent agent = mock(SmartReActAgent.class);
        when(agent.execute(org.mockito.ArgumentMatchers.anyString())).thenReturn("分析完成");
        ReflectionTestUtils.setField(service, "mcpAgent", agent);

        String result = service.query("近 30 天用户增长和销量趋势如何");

        assertThat(result).isEqualTo("分析完成");
        verify(agent).execute(argThat(prompt ->
                prompt.contains("### 数据分析开始 ###")
                        && prompt.contains("近 30 天用户增长和销量趋势如何")
                        && prompt.contains("executeQuery")
                        && !prompt.contains("{{query}}")
                        && !prompt.contains("{{context}}")));
    }
}
