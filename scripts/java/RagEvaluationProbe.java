import com.example.smartassistant.common.embedding.EmbeddingClient;
import com.example.smartassistant.common.embedding.RemoteBgeEmbeddingModel;
import com.example.smartassistant.common.rag.*;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Standalone, read-only public seed benchmark; no Spring app or database bootstrapping. */
public class RagEvaluationProbe {
    static final class StrictEmbedding extends RemoteBgeEmbeddingModel {
        boolean failed;
        int dimensions;
        StrictEmbedding(EmbeddingClient client) { super(client); }
        @Override public float[] embedding(String text) {
            try {
                float[] vector = super.embedding(text);
                if (vector == null || vector.length == 0) throw new IllegalStateException();
                double norm = 0;
                for (float value : vector) {
                    if (!Float.isFinite(value)) throw new IllegalStateException();
                    norm += value * (double) value;
                }
                if (norm == 0 || (dimensions != 0 && dimensions != vector.length)) throw new IllegalStateException();
                dimensions = vector.length;
                return vector;
            } catch (RuntimeException e) {
                failed = true;
                for (Throwable cause = e; cause != null; cause = cause.getCause())
                    System.err.println("RAG_EMBEDDING_FAILURE_TYPE=" + cause.getClass().getName());
                throw new IllegalStateException("Embedding unavailable; benchmark invalid");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        var output = System.out;
        System.setOut(System.err); // Dependency logs never contaminate the JSON protocol.
        System.err.println("RAG_PROBE_STARTED");
        var mapper = new ObjectMapper();
        byte[] input = System.in.readNBytes(2 * 1024 * 1024 + 1);
        if (input.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Request too large");
        JsonNode request = mapper.readTree(input);
        int k = request.path("top_k").asInt();
        JsonNode queries = request.path("queries");
        if (k < 5 || k > 50 || !queries.isArray() || queries.isEmpty() || queries.size() > 1000)
            throw new IllegalArgumentException("Invalid request");
        String url = System.getenv("RAG_EVAL_EMBEDDING_URL");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Embedding URL required");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        var beans = new StaticListableBeanFactory();
        beans.addBean("http", RestClient.builder().requestFactory(factory));
        var model = new StrictEmbedding(new EmbeddingClient(beans.getBeanProvider(RestClient.Builder.class), url));
        var tokenizer = new ChineseTokenizer();
        tokenizer.init();
        var docs = Map.of("order_knowledge", KnowledgeSeedData.orderDocuments(),
                          "product_knowledge", KnowledgeSeedData.productDocuments());
        var bases = new HashMap<String, InMemoryKnowledgeBase>();
        var corpus = new TreeMap<String, List<String>>();
        var digest = MessageDigest.getInstance("SHA-256");
        for (String name : new TreeSet<>(docs.keySet())) {
            var kb = new InMemoryKnowledgeBase(name, model, tokenizer, new BgeReranker(model));
            kb.addDocumentsAndBuildIndexes(docs.get(name));
            bases.put(name, kb);
            corpus.put(name, docs.get(name).stream().map(KnowledgeDocument::getId).sorted().toList());
            for (var doc : docs.get(name).stream().sorted(Comparator.comparing(KnowledgeDocument::getId)).toList())
                digest.update(mapper.writeValueAsBytes(List.of(name, doc.getId(), doc.getTitle(), doc.getContent())));
        }
        var results = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        for (JsonNode query : queries) {
            String id = query.path("id").asText(), text = query.path("question").asText();
            var kb = bases.get(query.path("knowledge_base").asText());
            if (kb == null || id.isBlank() || !seen.add(id) || text.isBlank() || text.length() > 2000)
                throw new IllegalArgumentException("Invalid query");
            long start = System.nanoTime();
            var hits = kb.search(text, k, KnowledgeBase.PUBLIC_TENANT);
            results.add(Map.of("id", id, "doc_ids", hits.stream().map(h -> h.getDocument().getId()).toList(),
                               "latency_ms", (System.nanoTime() - start) / 1_000_000.0));
        }
        if (model.failed || model.dimensions == 0) throw new IllegalStateException("Embedding failed");
        var resolverFile = Path.of("/etc/resolv.conf");
        var resolvers = Files.exists(resolverFile) ? Files.readAllLines(resolverFile).stream()
            .map(String::strip).filter(line -> line.startsWith("nameserver "))
            .map(line -> line.split("\\s+")[1]).toList() : List.<String>of();
        output.println(mapper.writeValueAsString(Map.of("schema", 1,
            "backend", "seed-inmemory-bge-bm25-reranker", "embedding_verified", true,
            "embedding_dimensions", model.dimensions, "corpus", corpus,
            "resolver_nameservers", resolvers,
            "corpus_sha256", HexFormat.of().formatHex(digest.digest()), "results", results)));
    }
}
