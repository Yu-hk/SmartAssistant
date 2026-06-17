package com.example.smartassistant.router.service.core;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * POC: 最小 StateGraph 验证 Checkpoint 写入 PostgreSQL 与 LLM 流式调用链路。
 * <p>
 * Graph 结构：START → planner（LLM 调用）→ responder（LLM 调用）→ END
 * 验证点：
 * 1. StateGraph 编译与执行
 * 2. PostgresSaver 将 Checkpoint 持久化到项目 PostgreSQL
 * 3. 节点内调用现有 ChatClient（Ollama + DeepSeek R1 7B）
 * 4. 状态历史可恢复读取
 * 5. workflow.stream() 流式输出链路
 */
@Component
public class GraphCheckpointPoc {

    private static final Logger log = LoggerFactory.getLogger(GraphCheckpointPoc.class);

    private final ChatClient chatClient;
    private final DataSource dataSource;

    public GraphCheckpointPoc(ChatClient.Builder builder, DataSource dataSource) {
        this.chatClient = builder.build();
        this.dataSource = dataSource;
        log.info("[GraphPOC] Initialized with DataSource={}", dataSource);
    }

    /**
     * 执行 POC 流程，返回详细结果摘要。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> runPoc() {
        Map<String, Object> summary = new LinkedHashMap<>();
        String threadId = "poc-thread-" + System.currentTimeMillis();

        try {
            // ── 1. StateSerializer ──
            var stateSerializer = new ObjectStreamStateSerializer<>(AgentState::new);

            // ── 2. PostgresSaver ──
            log.info("[GraphPOC] 初始化 PostgresSaver...");
            var saver = PostgresSaver.builder()
                    .datasource(dataSource)
                    .stateSerializer(stateSerializer)
                    .createTables(true)
                    .build();

            // ── 3. 定义节点（同步 NodeAction → 异步 AsyncNodeAction）──

            // 节点 A: planner — 调用 LLM 生成旅行计划
            NodeAction<AgentState> plannerAction = state -> {
                String prompt = "你是一个旅行规划助手。请用一句话推荐一个适合夏天去的中国城市并说明理由。";
                log.info("[GraphPOC] [planner] calling ChatClient...");
                String response = chatClient.prompt().user(prompt).call().content();
                log.info("[GraphPOC] [planner] response={}", response);
                String result = (response != null) ? response : "（无响应）";
                return Map.of("planner_result", result);
            };

            // 节点 B: responder — 调用 LLM 对规划做总结
            NodeAction<AgentState> responderAction = state -> {
                Optional<String> plan = state.value("planner_result");
                String base = plan.orElse("无前期规划");
                String prompt = "请用一句话总结以下旅行计划的要点：\n" + base;
                log.info("[GraphPOC] [responder] calling ChatClient...");
                String response = chatClient.prompt().user(prompt).call().content();
                log.info("[GraphPOC] [responder] response={}", response);
                String result = (response != null) ? response : "（无响应）";
                return Map.of("responder_result", result);
            };

            // ── 4. 构建 StateGraph ──
            log.info("[GraphPOC] 构建 StateGraph...");
            var graph = new StateGraph<>(AgentState::new)
                    .addNode("planner", AsyncNodeAction.node_async(plannerAction))
                    .addNode("responder", AsyncNodeAction.node_async(responderAction))
                    .addEdge(StateGraph.START, "planner")
                    .addEdge("planner", "responder")
                    .addEdge("responder", StateGraph.END);

            // ── 5. 编译（绑定 CheckpointSaver） ──
            log.info("[GraphPOC] 编译 StateGraph...");
            var compileConfig = CompileConfig.builder()
                    .checkpointSaver(saver)
                    .releaseThread(false)
                    .build();
            var runnableConfig = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();

            var workflow = graph.compile(compileConfig);
            Map<String, Object> inputs = Map.of("input", "夏季旅行规划");

            // ── 6.1 执行 invoke（同步执行） ──
            log.info("[GraphPOC] 执行 workflow.invoke()...");
            var invokeResult = workflow.invoke(inputs, runnableConfig);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) invokeResult
                    .map(state -> (Map<String, Object>) state.data())
                    .orElse(Map.of());

            // ── 7. 验证 Checkpoint 持久化 ──
            log.info("[GraphPOC] 读取 Checkpoint 历史...");
            var history = workflow.getStateHistory(runnableConfig);
            var lastState = workflow.lastStateOf(runnableConfig);

            summary.put("status", "success");
            summary.put("threadId", threadId);
            summary.put("checkpointCount", history.size());
            summary.put("lastNode", lastState.map(s -> s.node()).orElse("none"));
            summary.put("lastNext", lastState.map(s -> s.next()).orElse("none"));
            summary.put("plannerResult", result.getOrDefault("planner_result", ""));
            summary.put("responderResult", result.getOrDefault("responder_result", ""));
            summary.put("checkpointPersisted", !history.isEmpty());

            // ── 6.2 执行 stream（验证流式链路） ──
            log.info("[GraphPOC] 执行 workflow.stream()...");
            String streamThreadId = threadId + "-stream";
            var streamConfig = RunnableConfig.builder()
                    .threadId(streamThreadId)
                    .build();
            List<String> streamNodes = Collections.synchronizedList(new ArrayList<>());

            // stream() 返回 AsyncGenerator.Cancellable<NodeOutput<AgentState>>
            var cancellableStream = workflow.stream(inputs, streamConfig);
            // 用 forEachAsync 异步消费，阻塞等待完成
            cancellableStream.forEachAsync(nodeOutput -> {
                String nodeName = nodeOutput.node();
                streamNodes.add(nodeName);
                log.info("[GraphPOC] [stream] node={} completed", nodeName);
            }).get(10, TimeUnit.SECONDS); // 最多等 10 秒

            summary.put("streamNodes", streamNodes);
            summary.put("streamVerified", streamNodes.size() >= 2);

            // ── 8. 释放 Checkpoint ──
            saver.release(runnableConfig);
            log.info("[GraphPOC] POC 完成: threadId={}, checkpointCount={}", threadId, history.size());

        } catch (Exception e) {
            log.error("[GraphPOC] POC 执行失败", e);
            summary.put("status", "error");
            summary.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            summary.put("threadId", threadId);
        }

        return summary;
    }
}
