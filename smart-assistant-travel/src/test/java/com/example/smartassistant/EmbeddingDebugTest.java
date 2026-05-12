package com.example.smartassistant;

import com.example.smartassistant.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Travel RAG 独立测试用例
 *
 * 用于调试向量生成过程中的 OOM 问题
 *
 * 使用方式:
 * 1. 直接运行 main 方法
 * 2. 或在 IDE 中以 JUnit 方式运行
 *
 * @author Debug Assistant
 */
@Slf4j
public class EmbeddingDebugTest {

    // ⚠️ 使用前请设置环境变量 DASHSCOPE_API_KEY，不要硬编码 API Key
    private static final String DASHSCOPE_API_KEY = System.getenv("DASHSCOPE_API_KEY");
    private static final String DASHSCOPE_EMBEDDING_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    public static void main(String[] args) {
        log.info("========================================");
        log.info(" Travel RAG 独立测试用例开始");
        log.info("========================================");

        // 打印内存状态
        printMemoryStatus("测试开始前");

        // 测试1: 简单文本向量生成
        testSimpleEmbedding();

        // 测试2: 测试分块逻辑
        testChunkingLogic();

        // 测试3: 模拟完整流程（分块 + 向量化 + 批量插入）
        testFullWorkflow();

        log.info("========================================");
        log.info(" 测试完成");
        log.info("========================================");
    }

    /**
     * 测试1: 简单文本向量生成
     */
    private static void testSimpleEmbedding() {
        log.info("\n========== 测试1: 简单文本向量生成 ==========");
        printMemoryStatus("测试1开始前");

        String testText = "这是一段测试文本，用于测试向量生成功能。";

        try {
            float[] embedding = callDashScopeApi(testText);
            log.info("✅ 向量生成成功: dimensions={}", embedding.length);
            log.info("   前5个维度: {}", Arrays.toString(Arrays.copyOf(embedding, 5)));
        } catch (Exception e) {
            log.error("❌ 向量生成失败: {}", e.getMessage(), e);
        }

        printMemoryStatus("测试1完成后");
        System.gc();
        printMemoryStatus("GC后");
    }

    /**
     * 测试2: 测试分块逻辑
     */
    private static void testChunkingLogic() {
        log.info("\n========== 测试2: 测试分块逻辑 ==========");
        printMemoryStatus("测试2开始前");

        // 模拟游记内容
        String travelNoteContent = """
                # 我的西安之旅

                第一天：抵达西安
                我们乘坐高铁抵达西安北站，一下火车就感受到了古都的热情。
                西安的天气非常好，阳光明媚，空气中弥漫着历史的气息。

                下午我们去了回民街，这里是西安最有名的美食街。
                街道两旁摆满了各种小吃摊位，羊肉泡馍、肉夹馍、凉皮...
                每一样都让人垂涎欲滴。我们尝试了正宗的羊肉泡馍，味道确实很棒！

                第二天：兵马俑
                早上五点起床，前往兵马俑博物馆参观。
                虽然起得很早，但是看到那壮观的陶俑阵列，一切都值得了。

                景区信息：
                - 门票：120元/人
                - 开放时间：8:30-18:00
                - 建议游玩时间：3-4小时

                第三天：古城墙
                我们租了自行车，在古城墙上骑行了一圈。
                全程约14公里，骑行时间约2小时。

                这次旅行非常难忘，西安是一座值得多次游览的城市！
                """;

        log.info("原始内容长度: {} 字符", travelNoteContent.length());

        List<String> chunks = splitIntoChunks(travelNoteContent, 300, 50);
        log.info("分块数量: {}", chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            log.info("   Chunk[{}]: {} 字符 - {}", i, chunks.get(i).length(),
                    chunks.get(i).substring(0, Math.min(30, chunks.get(i).length())) + "...");
        }

        printMemoryStatus("测试2完成后");
    }

    /**
     * 测试3: 模拟完整流程
     */
    private static void testFullWorkflow() {
        log.info("\n========== 测试3: 模拟完整流程 ==========");
        printMemoryStatus("测试3开始前");

        String travelNoteContent = "西安旅游真的很有趣！我们去了回民街吃美食，羊肉泡馍特别好吃。";
        List<String> chunks = splitIntoChunks(travelNoteContent, 300, 50);

        log.info("开始处理 {} 个分块...", chunks.size());

        List<float[]> allEmbeddings = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            log.info("处理分块 {}/{}", i + 1, chunks.size());

            try {
                // 1. 生成向量
                float[] embedding = callDashScopeApi(chunk);
                allEmbeddings.add(embedding);
                log.info("   ✅ 向量生成成功: {}", embedding.length);

                // 2. 模拟数据库插入
                // 在实际代码中，这里会调用 travelNoteChunkMapper.insertOne(entity)
                simulateDbInsert(chunk, i, embedding);

            } catch (Exception e) {
                log.error("   ❌ 处理分块 {} 失败: {}", i, e.getMessage());
            }

            // 每处理完一个分块后打印内存状态
            if (i % 5 == 0) {
                printMemoryStatus("处理" + (i + 1) + "个分块后");
                System.gc();
            }
        }

        log.info("完整流程完成！共生成了 {} 个向量", allEmbeddings.size());
        printMemoryStatus("测试3完成后");
    }

    /**
     * 直接调用 DashScope API（无 Spring 依赖）
     */
    private static float[] callDashScopeApi(String text) throws Exception {
        log.info("[API] 开始调用 DashScope API, textLength={}", text.length());

        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "text-embedding-v3");

        Map<String, String> input = new HashMap<>();
        input.put("text", text);
        requestBody.put("input", input);

        // 转换为 JSON 字符串
        String jsonBody = toJson(requestBody);

        // 创建 HTTP 客户端
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // 创建请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_EMBEDDING_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + DASHSCOPE_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(java.time.Duration.ofSeconds(30))
                .build();

        log.info("[API] 发送请求...");

        // 发送请求
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        log.info("[API] 收到响应: status={}, bodyLength={}",
                response.statusCode(), response.body().length());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API 调用失败: " + response.statusCode() + ", " + response.body());
        }

        // 解析响应
        return parseEmbeddingResponse(response.body());
    }

    /**
     * 解析 DashScope API 响应
     */
    private static float[] parseEmbeddingResponse(String json) throws Exception {
        // 简单的 JSON 解析（避免引入 Jackson 依赖）
        // 查找 "embedding": [...] 部分

        int embeddingStart = json.indexOf("\"embedding\":[");
        if (embeddingStart == -1) {
            throw new RuntimeException("响应中未找到 embedding 字段");
        }

        int arrayStart = json.indexOf("[", embeddingStart);
        int arrayEnd = json.lastIndexOf("]");
        if (arrayStart == -1 || arrayEnd == -1) {
            throw new RuntimeException("无法解析 embedding 数组");
        }

        String arrayStr = json.substring(arrayStart + 1, arrayEnd);
        String[] values = arrayStr.split(",");

        float[] embedding = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            embedding[i] = Float.parseFloat(values[i].trim());
        }

        return embedding;
    }

    /**
     * 简单的 Map 转 JSON（避免 Jackson 依赖）
     */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");

            Object value = entry.getValue();
            if (value instanceof Map) {
                sb.append(toJson((Map<String, Object>) value));
            } else if (value instanceof String) {
                sb.append("\"").append(((String) value).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            } else {
                sb.append(value);
            }
            i++;
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * 滑动窗口分块
     */
    private static List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        if (text.length() <= chunkSize) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            // 尽量在句子边界切分
            if (end < text.length()) {
                int lastPeriod = text.lastIndexOf("。", end);
                int lastNewline = text.lastIndexOf("\n", end);
                int boundary = Math.max(lastPeriod, lastNewline);

                if (boundary > start + chunkSize / 2) {
                    end = boundary + 1;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            start = end - overlap;
            if (start <= 0) start = end;
        }

        return chunks;
    }

    /**
     * 模拟数据库插入
     */
    private static void simulateDbInsert(String chunk, int index, float[] embedding) {
        // 实际代码中，这里会调用:
        // travelNoteChunkMapper.insertOne(entity);

        log.info("   [模拟DB] 插入分块 {}, embedding 长度={}", index, embedding.length);
    }

    /**
     * 打印内存状态
     */
    private static void printMemoryStatus(String label) {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        long used = heapUsage.getUsed() / 1024 / 1024;
        long max = heapUsage.getMax() / 1024 / 1024;
        long committed = heapUsage.getCommitted() / 1024 / 1024;

        log.info("📊 [{}] Heap: used={}MB, committed={}MB, max={}MB, usage={}%",
                label, used, committed, max,
                max > 0 ? (used * 100 / max) : 0);
    }
}
