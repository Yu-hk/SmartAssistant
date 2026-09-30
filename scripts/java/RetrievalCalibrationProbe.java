import com.example.smartassistant.common.embedding.EmbeddingClient;
import com.example.smartassistant.common.embedding.RemoteBgeEmbeddingModel;
import com.example.smartassistant.common.rag.*;
import com.example.smartassistant.common.rag.pipeline.*;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.service.search.handler.RrfFusionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.util.*;

/** Synthetic seed-corpus experiment: real BM25, BGE, weighted RRF and Product reranking.
 * No reference labels enter retrieval; no Spring boot, database or business mutation.
 * Not a full Product/Router benchmark: native KB aggregation/ACL integration is not exercised. */
public class RetrievalCalibrationProbe {
    static final class StrictCachedEmbedding extends RemoteBgeEmbeddingModel {
        final Map<String, float[]> cache = new HashMap<>();
        int dimension, calls;
        StrictCachedEmbedding(EmbeddingClient client) { super(client); }
        @Override public float[] embedding(String text) {
            if (cache.containsKey(text)) return cache.get(text).clone();
            float[] values = super.embedding(text);
            double norm = 0;
            if (values == null || values.length == 0) throw new IllegalStateException("Embedding unavailable");
            for (float value : values) {
                if (!Float.isFinite(value)) throw new IllegalStateException("Embedding unavailable");
                norm += (double)value * value;
            }
            if (norm == 0 || (dimension != 0 && dimension != values.length))
                throw new IllegalStateException("Embedding unavailable");
            dimension = values.length; calls++;
            cache.put(text, values.clone());
            return values;
        }
    }
    static double cosine(float[] a, float[] b) {
        double dot=0, aa=0, bb=0;
        for (int i=0;i<a.length;i++) { dot+=(double)a[i]*b[i]; aa+=(double)a[i]*a[i]; bb+=(double)b[i]*b[i]; }
        return dot / Math.sqrt(aa*bb);
    }
    public static void main(String[] args) throws Exception {
        var output = System.out;
        System.setOut(System.err);
        var mapper = new ObjectMapper();
        byte[] bytes=System.in.readNBytes(262145);
        if (bytes.length>262144) throw new IllegalArgumentException("Bounded synthetic input required");
        var input=mapper.readTree(bytes);
        var queries=input.path("queries");
        int k=input.path("top_k").asInt();
        if (!queries.isArray() || queries.size()<2 || queries.size()>64 || k<1 || k>8)
            throw new IllegalArgumentException("Invalid experiment input");
        String endpoint=System.getenv("RAG_EVAL_EMBEDDING_URL");
        if (endpoint==null || endpoint.isBlank()) throw new IllegalArgumentException("Embedding URL required");
        var http=new SimpleClientHttpRequestFactory(); http.setConnectTimeout(5000); http.setReadTimeout(10000);
        var beans=new StaticListableBeanFactory(); beans.addBean("http", RestClient.builder().requestFactory(http));
        var model=new StrictCachedEmbedding(new EmbeddingClient(beans.getBeanProvider(RestClient.Builder.class),endpoint));
        var tokenizer=new ChineseTokenizer(); tokenizer.init();
        var corpus=Map.of("order_knowledge",KnowledgeSeedData.orderDocuments(),"product_knowledge",KnowledgeSeedData.productDocuments());
        var rows=new ArrayList<Map<String,Object>>();
        var corpusRows=new TreeMap<String,List<Map<String,String>>>();
        for (var entry:corpus.entrySet()) corpusRows.put(entry.getKey(),entry.getValue().stream()
            .sorted(Comparator.comparing(KnowledgeDocument::getId))
            .map(d->Map.of("id",d.getId(),"textSha256",RagSearchContext.evidenceId(d.toEmbedText()))).toList());
        var seen=new HashSet<String>();
        for (var q:queries) {
            String id=q.path("id").asText(), question=q.path("question").asText(), domain=q.path("knowledge_base").asText();
            if (!seen.add(id) || id.isBlank() || question.isBlank() || question.length()>2000 || !corpus.containsKey(domain))
                throw new IllegalArgumentException("Invalid query");
            var docs=corpus.get(domain).stream().sorted(Comparator.comparing(KnowledgeDocument::getId)).toList();
            var bm25=new Bm25Scorer(tokenizer); bm25.initialize(docs);
            var sparse=docs.stream().map(d->Map.entry(d,bm25.score(d,question))).filter(e->e.getValue()>0)
                .sorted(Map.Entry.<KnowledgeDocument,Double>comparingByValue().reversed()).toList();
            float[] queryVector=model.embedding(question);
            var dense=docs.stream().map(d->Map.entry(d,cosine(queryVector,model.embedding(d.toEmbedText()))))
                .filter(e->e.getValue()>=.30).sorted(Map.Entry.<KnowledgeDocument,Double>comparingByValue().reversed()).toList();
            var byText=new HashMap<String,String>(); docs.forEach(d->byText.put(d.toEmbedText(),d.getId()));
            var rankings=new LinkedHashMap<String,Object>();
            var scorer=new EmbeddingScorer(model::embedding);
            for (String setting:List.of("adaptive","sparse-020","sparse-050","sparse-080")) {
                for (double blend:new double[]{0,.35}) {
                    var ctx=new RagSearchContext(question); new AdaptiveWeightHandler().handle(ctx);
                    if (!setting.equals("adaptive")) {
                        double weight=setting.equals("sparse-020")?.2:setting.equals("sparse-050")?.5:.8;
                        ctx.setAttribute(AdaptiveWeightHandler.ATTR_SPARSE_WEIGHT,weight);
                        ctx.setAttribute(AdaptiveWeightHandler.ATTR_DENSE_WEIGHT,1-weight);
                    }
                    ctx.addPathResult("BM25",sparse.stream().map(e->e.getKey().toEmbedText()).toList());
                    ctx.addPathResult("知识库",dense.stream().map(e->e.getKey().toEmbedText()).toList());
                    new RrfFusionHandler().handle(ctx);
                    var pool=ctx.getFusedResults().stream().map(e->byText.get(e.getContent())).toList();
                    new RerankHandler(scorer,true,k,null,blend).handle(ctx);
                    if (ctx.isDegraded()) throw new IllegalStateException("Invalid degraded experiment");
                    String name=setting+(blend==0?"-semantic":"-blend035");
                    rankings.put(name,Map.of("doc_ids",ctx.getFusedResults().stream().map(e->byText.get(e.getContent())).toList(),
                        "candidate_ids",pool,"sparse_weight",ctx.getAttribute(AdaptiveWeightHandler.ATTR_SPARSE_WEIGHT),
                        "dense_weight",ctx.getAttribute(AdaptiveWeightHandler.ATTR_DENSE_WEIGHT),"fusion_weight",blend));
                }
            }
            rows.add(Map.of("id",id,"question_sha256",RagSearchContext.evidenceId(question),"knowledge_base",domain,"rankings",rankings));
        }
        output.println(mapper.writeValueAsString(Map.of("schema_version",1,"backend","public-seed-bm25-bge-product-rrf-rerank",
            "embedding_verified",true,"embedding_dimension",model.dimension,"embedding_calls",model.calls,
            "corpus",corpusRows,"top_k",k,"results",rows)));
    }
}
