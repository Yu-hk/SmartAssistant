import com.example.smartassistant.common.embedding.EmbeddingClient;
import com.example.smartassistant.common.embedding.RemoteBgeEmbeddingModel;
import com.example.smartassistant.common.rag.*;
import com.example.smartassistant.common.rag.pipeline.*;
import com.example.smartassistant.common.rag.properties.RagProductionProperties;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.config.NativeRagProperties;
import com.example.smartassistant.service.search.*;
import com.example.smartassistant.service.search.handler.KnowledgeSearchHandler;
import com.example.smartassistant.service.search.handler.RrfFusionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.util.*;
import java.util.regex.Pattern;

/** Actual memory-KB/Product knowledge subchain, NOT PG/Router/catalog/generation E2E.
 * Synthetic documents only. Gold labels and split never enter this program. */
public class NativeRetrievalCalibrationProbe {
    static final List<String> STRATEGIES=List.of("hybrid-semantic","dense-semantic","hybrid-blend035","hybrid-facet-rrf");
    static final Pattern CID=Pattern.compile("\\[CID:([^\\]]+)]");
    static List<String> facets(String query) {
        var pieces=Arrays.stream(query.split("[；;]",-1)).map(String::strip).toList();
        // Do not silently drop a clause, expand scope, or ask a model for extra facts.
        return pieces.size()>1 && pieces.size()<=3 && pieces.stream().allMatch(s->s.length()>=4)
                ? pieces : List.of(query);
    }
    static List<String> cids(String text) {
        var found=new LinkedHashSet<String>(); var match=CID.matcher(text);
        while(match.find()) found.add(match.group(1));
        return List.copyOf(found);
    }
    static final class StrictModel extends RemoteBgeEmbeddingModel {
        boolean failed;
        int dimension,calls;
        final RetrievalCalibrationProbe.StrictCachedEmbedding delegate;
        StrictModel(EmbeddingClient client) { super(client); delegate=new RetrievalCalibrationProbe.StrictCachedEmbedding(client); }
        @Override public float[] embedding(String text) {
            try { float[] values=delegate.embedding(text); dimension=delegate.dimension; calls=delegate.calls; return values; }
            catch(RuntimeException e) { failed=true; throw e; }
        }
    }
    static final class RecordingKb extends InMemoryKnowledgeBase {
        boolean facetMode,inMulti;
        List<KnowledgeHit> last=List.of(); List<String> lastFacets=List.of(); int calls;
        final List<Map<String,Object>> nativeRankings=new ArrayList<>();
        RecordingKb(String name,StrictModel model,ChineseTokenizer tokenizer) {
            super(name,model,tokenizer,null);
        }
        @Override public List<KnowledgeHit> search(String query,int k,AclContext acl) {
            List<KnowledgeHit> hits;
            if(!inMulti) nativeRankings.clear();
            if(facetMode && !inMulti && facets(query).size()>1) {
                // Native multi-query currently exposes tenant-only ACL. This isolated
                // profile is public-only; never extend this call to an authenticated user.
                if(!acl.equals(AclContext.PUBLIC)) throw new IllegalStateException("Public-only facet profile");
                inMulti=true;
                try { hits=super.searchMultiQuery(facets(query),k,acl.getTenantId()); }
                finally { inMulti=false; }
            } else {
                hits=super.search(query,k,acl);
                nativeRankings.add(Map.of("question_sha256",RagSearchContext.evidenceId(query),"top_k",k,
                        "doc_ids",hits.stream().map(h->h.getDocument().getId()).toList()));
            }
            if(!inMulti) { last=List.copyOf(hits); lastFacets=facetMode?facets(query):List.of(query); calls++; }
            return hits;
        }
    }
    static final class Capture implements RagSearchHandler {
        RagSearchContext context;
        public void handle(RagSearchContext context) { this.context=context; }
        public int getOrder() { return 120; }
    }
    static KnowledgeDocument document(JsonNode row) {
        String visibility=row.path("visibility").asText();
        if(!Set.of("public","tenant","role","user","clearance").contains(visibility))
            throw new IllegalArgumentException("Unknown synthetic ACL");
        return new KnowledgeDocument(row.path("id").asText(),row.path("title").asText(),row.path("content").asText(),
                row.path("category").asText(),row.path("keywords").asText(),-1,-1,
                visibility.equals("tenant")?"synthetic-other-tenant":"","v1","",0,"",
                AuthorityLevel.L2_INTERNAL,DocumentStatus.ACTIVE,"v1",
                visibility.equals("role")?Set.of("synthetic-admin"):Set.of(),
                visibility.equals("user")?Set.of("synthetic-other-user"):Set.of(),visibility.equals("clearance")?3:0);
    }
    public static void main(String[] args) throws Exception {
        var output=System.out; System.setOut(System.err); MDC.clear();
        var mapper=new ObjectMapper(); byte[] bytes=System.in.readNBytes(524289);
        if(bytes.length>524288) throw new IllegalArgumentException("Bounded synthetic input");
        var input=mapper.readTree(bytes); var queries=input.path("queries"); var docs=input.path("documents");
        if(!queries.isArray() || queries.size()<2 || queries.size()>64 || !docs.isArray() || docs.size()<2 || docs.size()>128)
            throw new IllegalArgumentException("Bounded corpus/query count");
        String endpoint=System.getenv("RAG_EVAL_EMBEDDING_URL");
        if(endpoint==null || endpoint.isBlank()) throw new IllegalArgumentException("Embedding URL required");
        var http=new SimpleClientHttpRequestFactory(); http.setConnectTimeout(5000); http.setReadTimeout(10000);
        var beans=new StaticListableBeanFactory(); beans.addBean("http",RestClient.builder().requestFactory(http));
        var model=new StrictModel(new EmbeddingClient(beans.getBeanProvider(RestClient.Builder.class),endpoint));
        var tokenizer=new ChineseTokenizer(); tokenizer.init();
        var corpora=new TreeMap<String,List<KnowledgeDocument>>();
        var fingerprints=new TreeMap<String,List<Map<String,String>>>(); var docIds=new HashSet<String>();
        for(var row:docs) {
            String domain=row.path("knowledge_base").asText(),id=row.path("id").asText();
            if(!Set.of("product_knowledge","order_knowledge").contains(domain) || id.isBlank() || !docIds.add(id))
                throw new IllegalArgumentException("Invalid document identity");
            var d=document(row); corpora.computeIfAbsent(domain,key->new ArrayList<>()).add(d);
        }
        for(var entry:corpora.entrySet()) fingerprints.put(entry.getKey(),entry.getValue().stream()
                .sorted(Comparator.comparing(KnowledgeDocument::getId))
                .map(d->Map.of("id",d.getId(),"textSha256",RagSearchContext.evidenceId(d.toEmbedText()))).toList());
        var hybrid=new RecordingKb("product_knowledge",model,tokenizer);
        var dense=new RecordingKb("product_knowledge",model,null);
        hybrid.addDocumentsAndBuildIndexes(corpora.get("product_knowledge"));
        dense.addDocumentsAndBuildIndexes(corpora.get("product_knowledge"));
        var order=new InMemoryKnowledgeBase("order_knowledge",model,tokenizer,null);
        order.addDocumentsAndBuildIndexes(corpora.get("order_knowledge"));
        var rows=new ArrayList<Map<String,Object>>(); var seen=new HashSet<String>();
        for(var q:queries) {
            String id=q.path("id").asText(),question=q.path("question").asText();
            if(!seen.add(id) || id.isBlank() || question.isBlank() || question.length()>2000)
                throw new IllegalArgumentException("Invalid query");
            var rankings=new LinkedHashMap<String,Object>();
            for(String strategy:STRATEGIES) {
                RecordingKb kb=strategy.equals("dense-semantic")?dense:hybrid;
                kb.facetMode=strategy.equals("hybrid-facet-rrf"); kb.last=List.of(); kb.lastFacets=List.of(); int before=kb.calls;
                var retrieval=new KnowledgeRetrievalService().register(kb).register(order);
                var scope=new ProductKnowledgeScopeSelector(retrieval,new RagProductionProperties());
                var capture=new Capture();
                var pipeline=new RagSearchPipeline(List.of(new AdaptiveWeightHandler(),new KnowledgeSearchHandler(retrieval),
                        new RrfFusionHandler(),new DedupHandler(true,DedupHandler.DedupMode.AGGRESSIVE,.85),
                        new RerankHandler(new EmbeddingScorer(model::embedding),true,3,null,strategy.equals("hybrid-blend035")?.35:0),capture));
                var settings=new NativeRagProperties(); settings.setEnabled(false); settings.setMaxAttempts(1);
                var service=new ProductRagService(pipeline,scope,beans.getBeanProvider(SupplementalQueryPlanner.class),settings);
                var result=service.retrieveWithQualityResult(question,"synthetic-native-calibration");
                var ctx=capture.context;
                if(ctx==null || ctx.isDegraded() || model.failed || kb.calls-before!=1)
                    throw new IllegalStateException("Invalid degraded or repeated attempt");
                List<String> leaves=kb.last.stream().map(h->h.getDocument().getId()).toList();
                List<String> finalIds=cids(result.getContent());
                if(!leaves.equals(finalIds)) throw new IllegalStateException("Leaf/context mismatch");
                var rank=new LinkedHashMap<String,Object>(); rank.put("doc_ids",leaves); rank.put("context_doc_ids",finalIds);
                rank.put("fragments",ctx.getFusedResults().size()); rank.put("kb_service_calls",1);
                rank.put("native_query_count",kb.nativeRankings.size()); rank.put("native_rankings",List.copyOf(kb.nativeRankings));
                rank.put("facet_count",kb.lastFacets.size()); rank.put("facet_sha256",kb.lastFacets.stream().map(RagSearchContext::evidenceId).toList());
                rank.put("selected_domains",ctx.getAttribute("rag.knowledgeBases")); rank.put("scope_reason",ctx.getAttribute("rag.scopeReason"));
                rank.put("sparse_weight",ctx.getAttribute(AdaptiveWeightHandler.ATTR_SPARSE_WEIGHT));
                rank.put("dense_weight",ctx.getAttribute(AdaptiveWeightHandler.ATTR_DENSE_WEIGHT));
                rank.put("fusion_weight",strategy.equals("hybrid-blend035")?.35:0);
                rank.put("context_sha256",RagSearchContext.evidenceId(result.getContent()));
                rank.put("context_chars",result.getContent().length()); rank.put("degraded",false);
                rankings.put(strategy,rank);
            }
            rows.add(Map.of("id",id,"question_sha256",RagSearchContext.evidenceId(question),"rankings",rankings));
        }
        output.println(mapper.writeValueAsString(Map.of("schema_version",1,"backend","native-memory-product-knowledge-subchain",
                "embedding_verified",true,"embedding_dimension",model.dimension,"embedding_calls",model.calls,
                "corpus",fingerprints,"leaf_top_k",5,"results",rows)));
    }
}
