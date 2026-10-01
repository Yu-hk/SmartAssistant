package com.example.smartassistant.embedding;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the production controller through a real loopback HTTP server and JSON converter. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddingControllerContractTest {

    @TempDir
    static Path directory;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private MockedConstruction<BgeEmbeddingModel> construction;
    private ConfigurableApplicationContext context;
    private BgeEmbeddingModel model;
    private String baseUrl;

    @BeforeAll
    void startServerWithoutLoadingAModel() {
        construction = mockConstruction(BgeEmbeddingModel.class, (mock, invocation) -> {
            when(mock.isAvailable()).thenReturn(true);
            when(mock.dimensions()).thenReturn(3);
        });
        try {
            context = EmbeddingApplicationContextTest.startIsolatedApplication(
                    WebApplicationType.SERVLET, directory.resolve("absent.onnx").toString(),
                    directory.resolve("absent-tokenizer.json").toString());
            assertEquals(1, construction.constructed().size());
            model = construction.constructed().getFirst();
            baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        } catch (RuntimeException | Error failure) {
            if (context != null) context.close();
            construction.close();
            construction = null;
            throw failure;
        }
    }

    @BeforeEach
    void resetModel() {
        reset(model);
        when(model.isAvailable()).thenReturn(true);
        when(model.dimensions()).thenReturn(3);
    }

    @AfterAll
    void stopServer() {
        try {
            if (context != null) context.close();
        } finally {
            if (construction != null) construction.close();
            client.close();
        }
    }

    @Test
    void healthSerializesTheAvailableModelContract() throws Exception {
        JsonNode result = successfulJson(request("GET", "/api/embedding/health", null));

        assertEquals(4, result.size());
        assertEquals("UP", result.path("status").asText());
        assertEquals("bge-large-zh-v1.5", result.path("model").asText());
        assertEquals(3, result.path("dimensions").intValue());
        assertTrue(result.path("available").booleanValue());
        verify(model, times(2)).isAvailable();
        verify(model).dimensions();
        verify(model, never()).embedding(any());
    }

    @Test
    void healthReturnsHttp200ForAnUnavailableModel() throws Exception {
        when(model.isAvailable()).thenReturn(false);

        JsonNode result = successfulJson(request("GET", "/api/embedding/health", null));

        assertEquals("DOWN", result.path("status").asText());
        assertFalse(result.path("available").booleanValue());
        assertEquals(3, result.path("dimensions").intValue());
        verify(model, never()).embedding(any());
    }

    @Test
    void dimensionsHasTheExactJsonShape() throws Exception {
        JsonNode result = successfulJson(request("GET", "/api/embedding/dimensions", null));

        assertEquals(json.readTree("{\"dimensions\":3}"), result);
        verify(model).dimensions();
        verify(model, never()).embedding(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"成都有什么美食？", "成都有哪些值得品尝的本地美食，以及适合周末游玩的地方？"})
    void singleEmbeddingPreservesUtf8TextAndSerializesNumericVectorAndTiming(String text) throws Exception {
        when(model.embedding(text)).thenReturn(new float[]{0.25f, -0.5f, 0.75f});

        JsonNode result = successfulJson(request("POST", "/api/embedding", json.writeValueAsString(Map.of("text", text))));

        assertEquals(3, result.size());
        assertVector(result.path("embedding"), 0.25, -0.5, 0.75);
        assertEquals(3, result.path("dimensions").intValue());
        assertDuration(result);
        verify(model).embedding(text);
        verify(model, never()).dimensions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"other\":\"hello\"}", "{\"text\":null}", "{\"text\":\"\"}", "{\"text\":\" \\t\\n\"}"})
    void missingNullOrBlankSingleTextKeepsTheHttp200ErrorMap(String body) throws Exception {
        JsonNode result = successfulJson(request("POST", "/api/embedding", body));

        assertEquals(json.readTree("{\"error\":\"text 不能为空\"}"), result);
        verifyNoInteractions(model);
    }

    @Test
    void unavailableSingleEmbeddingKeepsTheHttp200ErrorMap() throws Exception {
        when(model.embedding("hello")).thenReturn(null);

        JsonNode result = successfulJson(request("POST", "/api/embedding", "{\"text\":\"hello\"}"));

        assertEquals(json.readTree("{\"error\":\"嵌入失败，模型不可用\"}"), result);
        verify(model).embedding("hello");
    }

    @Test
    void batchPreservesInputOrderIncludingRepeatedTexts() throws Exception {
        when(model.embedding("first")).thenReturn(new float[]{1f, 2f, 3f});
        when(model.embedding("second")).thenReturn(new float[]{-1f, -2f, -3f});

        JsonNode result = successfulJson(request("POST", "/api/embedding/batch",
                "{\"texts\":[\"second\",\"first\",\"second\"]}"));

        assertEquals(4, result.size());
        assertEquals(3, result.path("count").intValue());
        assertEquals(3, result.path("dimensions").intValue());
        assertVector(result.path("embeddings").get(0), -1, -2, -3);
        assertVector(result.path("embeddings").get(1), 1, 2, 3);
        assertVector(result.path("embeddings").get(2), -1, -2, -3);
        assertDuration(result);
        InOrder order = inOrder(model);
        order.verify(model).embedding("second");
        order.verify(model).embedding("first");
        order.verify(model).embedding("second");
        verify(model, times(3)).embedding(any());
    }

    @Test
    void partialBatchFailureRetainsAnEmptyArrayAtTheOriginalPositionAndContinues() throws Exception {
        when(model.embedding("before")).thenReturn(new float[]{0.25f, 0.5f, 0.75f});
        when(model.embedding("failure")).thenReturn(null);
        when(model.embedding("after")).thenReturn(new float[]{-0.25f, -0.5f, -0.75f});

        JsonNode result = successfulJson(request("POST", "/api/embedding/batch",
                "{\"texts\":[\"before\",\"failure\",\"after\"]}"));

        assertEquals(3, result.path("count").intValue());
        assertEquals(3, result.path("embeddings").size());
        assertVector(result.path("embeddings").get(0), 0.25, 0.5, 0.75);
        assertEquals(json.readTree("[]"), result.path("embeddings").get(1));
        assertVector(result.path("embeddings").get(2), -0.25, -0.5, -0.75);
        InOrder order = inOrder(model);
        order.verify(model).embedding("before");
        order.verify(model).embedding("failure");
        order.verify(model).embedding("after");
    }

    @Test
    void anUnavailableModelProducesOneEmptyArrayForEveryBatchItem() throws Exception {
        when(model.isAvailable()).thenReturn(false);

        JsonNode result = successfulJson(request("POST", "/api/embedding/batch", "{\"texts\":[\"a\",\"b\"]}"));

        assertEquals(json.readTree("[[],[]]"), result.path("embeddings"));
        assertEquals(2, result.path("count").intValue());
        assertEquals(3, result.path("dimensions").intValue());
        assertDuration(result);
        verify(model).embedding("a");
        verify(model).embedding("b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"other\":[]}", "{\"texts\":null}", "{\"texts\":[]}"})
    void missingNullOrEmptyBatchKeepsTheHttp200ErrorMap(String body) throws Exception {
        JsonNode result = successfulJson(request("POST", "/api/embedding/batch", body));

        assertEquals(json.readTree("{\"error\":\"texts 不能为空\"}"), result);
        verifyNoInteractions(model);
    }

    @Test
    void blankAndNullBatchItemsKeepTheirPositionsInsteadOfDiscardingOtherResults() throws Exception {
        when(model.embedding("valid")).thenReturn(new float[]{1f, 0f, -1f});

        JsonNode result = successfulJson(request("POST", "/api/embedding/batch",
                "{\"texts\":[\"\",null,\"  \",\"valid\"]}"));

        assertEquals(json.readTree("[[],[],[],[1.0,0.0,-1.0]]"), result.path("embeddings"));
        assertEquals(4, result.path("count").intValue());
        InOrder order = inOrder(model);
        order.verify(model).embedding("");
        order.verify(model).embedding(null);
        order.verify(model).embedding("  ");
        order.verify(model).embedding("valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/embedding", "/api/embedding/batch"})
    void malformedJsonIsRejectedByTheHttpBoundary(String path) throws Exception {
        assertEquals(400, request("POST", path, "{\"text\":").statusCode());
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @CsvSource(value = {"/api/embedding|{\"text\":{}}", "/api/embedding/batch|{\"texts\":\"not-an-array\"}"}, delimiter = '|')
    void incompatibleFieldTypesAreRejectedBeforeCallingTheModel(String path, String body) throws Exception {
        assertEquals(400, request("POST", path, body).statusCode());
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/embedding", "GET,/api/embedding/batch", "POST,/api/embedding/health", "POST,/api/embedding/dimensions"})
    void eachEndpointRejectsUnsupportedHttpMethods(String method, String path) throws Exception {
        assertEquals(405, request(method, path, null).statusCode());
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/embeddings", "/api/embedding/unknown"})
    void endpointPathsAreNotSilentlyAliased(String path) throws Exception {
        assertEquals(404, request("POST", path, "{\"text\":\"hello\"}").statusCode());
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/embedding", "/api/embedding/batch"})
    void aMissingRequestBodyIsAnHttp400RatherThanABusinessErrorMap(String path) throws Exception {
        assertEquals(400, request("POST", path, null).statusCode());
        verifyNoInteractions(model);
    }

    @Test
    void unsupportedContentTypeIsAnHttp415() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/embedding"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello"))
                .build();

        assertEquals(415, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        verifyNoInteractions(model);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode successfulJson(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        JsonNode result = json.readTree(response.body());
        assertTrue(result.isObject());
        return result;
    }

    private static void assertDuration(JsonNode result) {
        assertTrue(result.path("duration_ms").isIntegralNumber());
        assertTrue(result.path("duration_ms").longValue() >= 0);
    }

    private static void assertVector(JsonNode vector, double... expected) {
        assertTrue(vector.isArray());
        assertEquals(expected.length, vector.size());
        for (int i = 0; i < expected.length; i++) {
            assertTrue(vector.get(i).isNumber());
            assertEquals(expected[i], vector.get(i).doubleValue(), 0.000001);
        }
    }
}
