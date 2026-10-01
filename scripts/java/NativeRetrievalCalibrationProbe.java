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
    static final String COVERAGE="hybrid-anchored-coverage";
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
    record QueryPlan(List<String> queries,List<String> entities) {}
    static List<String> entitiesIn(String query,Collection<String> entities) {
        String rest=query.toLowerCase(Locale.ROOT);
        var found=new ArrayList<String>();
        for(String entity:entities.stream().sorted(Comparator.comparingInt(String::length).reversed().thenComparing(s->s)).toList()) {
            String name=entity.toLowerCase(Locale.ROOT);
            if(rest.contains(name)) { found.add(entity); rest=rest.replace(name,""); }
        }
        return found;
    }
    static QueryPlan anchoredPlan(String query,Collection<String> entities) {
        var parts=facets(query); var whole=entitiesIn(query,entities);
        if(parts.size()==1 || whole.isEmpty()) return new QueryPlan(List.of(query),List.of(""));
        var queries=new ArrayList<String>(); var bindings=new ArrayList<String>();
        queries.add(query); bindings.add(""); // Never discard the original question.
        for(String part:parts) {
            var explicit=entitiesIn(part,entities);
            String entity=explicit.size()==1?explicit.getFirst():explicit.isEmpty() && whole.size()==1?whole.getFirst():null;
            if(entity==null) return new QueryPlan(List.of(query),List.of(""));
            queries.add(explicit.isEmpty()?entity+"："+part:part); bindings.add(entity);
        }
        return new QueryPlan(List.copyOf(queries),List.copyOf(bindings));
    }
    static List<KnowledgeHit> coverageMerge(List<KnowledgeHit> original,List<List<KnowledgeHit>> facets,int k) {
        var selected=new LinkedHashMap<String,KnowledgeHit>();
        // One candidate per resolved facet is a bounded evidence seat, not proof of relevance.
        for(var ranking:facets) if(!ranking.isEmpty()) selected.putIfAbsent(ranking.getFirst().getDocument().getId(),ranking.getFirst());
        if(!original.isEmpty()) selected.putIfAbsent(original.getFirst().getDocument().getId(),original.getFirst());
        var scores=new LinkedHashMap<String,Double>(); var candidates=new LinkedHashMap<String,KnowledgeHit>();
        var rankings=new ArrayList<List<KnowledgeHit>>(); rankings.add(original); rankings.addAll(facets);
        for(var ranking:rankings) for(int i=0;i<ranking.size();i++) {
            var hit=ranking.get(i); String id=hit.getDocument().getId();
            scores.merge(id,1.0/(60+i+1),Double::sum); candidates.putIfAbsent(id,hit);
        }
        scores.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed())
                .forEach(e->selected.putIfAbsent(e.getKey(),candidates.get(e.getKey())));
        return selected.values().stream().limit(k).toList();
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
        boolean facetMode,coverageMode,inMulti;
        Set<String> publicEntities=Set.of();
        List<String> reserved=List.of(),protectedOriginal=List.of(),bindings=List.of();
        List<KnowledgeHit> last=List.of(); List<String> lastFacets=List.of(); int calls;
        final List<Map<String,Object>> nativeRankings=new ArrayList<>();
        RecordingKb(String name,StrictModel model,ChineseTokenizer tokenizer) {
            super(name,model,tokenizer,null);
        }
        @Override public List<KnowledgeHit> search(String query,int k,AclContext acl) {
            List<KnowledgeHit> hits;
            if(!inMulti) { nativeRankings.clear(); reserved=List.of(); protectedOriginal=List.of(); bindings=List.of(); }
            if(coverageMode && !inMulti) {
                if(!acl.equals(AclContext.PUBLIC)) throw new IllegalStateException("Public-only coverage profile");
                var plan=anchoredPlan(query,publicEntities); var rankings=new ArrayList<List<KnowledgeHit>>();
                for(int i=0;i<plan.queries().size();i++) {
                    String q=plan.queries().get(i),entity=plan.entities().get(i); int budget=i==0?k:10;
                    var raw=super.search(q,budget,acl); // Preserve the complete ACL, no tenant-only downgrade.
                    nativeRankings.add(Map.of("question_sha256",RagSearchContext.evidenceId(q),"top_k",budget,
                            "doc_ids",raw.stream().map(h->h.getDocument().getId()).toList()));
                    rankings.add(i==0?raw:raw.stream().filter(h->h.getDocument().getCategory().equals(entity)).toList());
                }
                var original=rankings.getFirst(); var scoped=rankings.subList(1,rankings.size());
                hits=coverageMerge(original,scoped,k);
                reserved=scoped.stream().filter(r->!r.isEmpty()).map(r->r.getFirst().getDocument().getId()).distinct().toList();
                protectedOriginal=original.stream().limit(1).map(h->h.getDocument().getId()).toList();
                bindings=plan.entities(); lastFacets=plan.queries();
            } else if(facetMode && !inMulti && facets(query).size()>1) {
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
            if(!inMulti) { last=List.copyOf(hits); if(!coverageMode) lastFacets=facetMode?facets(query):List.of(query); calls++; }
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
    static void coverageContract() {
        var entities=Set.of("晨页灯","木息器");
        String single="晨页灯如何固定夹座；如何记住亮度";
        var plan=anchoredPlan(single,entities);
        if(!plan.queries().equals(List.of(single,"晨页灯如何固定夹座","晨页灯：如何记住亮度"))) throw new AssertionError("inherit-single");
        String two="晨页灯如何固定夹座；木息器如何清洁振片";
        if(!anchoredPlan(two,entities).entities().equals(List.of("","晨页灯","木息器"))) throw new AssertionError("explicit-two");
        for(String q:List.of("晨页灯和木息器如何收纳；它的接口如何清洁","晨页灯如何固定夹座；木息器如何使用；如何清洗",
                "未知设备如何收纳；如何清洗接口","晨页灯价格；还有呢"))
            if(!anchoredPlan(q,entities).queries().equals(List.of(q))) throw new AssertionError("ambiguous-fallback");
        if(entitiesIn("晨页灯和晨页灯Pro",Set.of("晨页灯","晨页灯Pro")).size()!=2) throw new AssertionError("overlap-explicit");
        if(entitiesIn("晨页灯Pro",Set.of("晨页灯","晨页灯Pro")).size()!=1) throw new AssertionError("longest-match");
        var mapper=new ObjectMapper(); var hits=new ArrayList<KnowledgeHit>();
        for(String id:List.of("A","B","N1","N2","N3","N4")) {
            var row=mapper.createObjectNode().put("id",id).put("title",id).put("content","合成测试").put("category","晨页灯").put("visibility","public");
            hits.add(new KnowledgeHit(document(row),.8));
        }
        var original=List.of(hits.get(1),hits.get(2),hits.get(3),hits.get(4),hits.get(5));
        var rankings=List.of(List.of(hits.get(0),hits.get(2),hits.get(3)),List.of(hits.get(1),hits.get(2),hits.get(3)));
        var merged=coverageMerge(original,rankings,5).stream().map(h->h.getDocument().getId()).toList();
        if(merged.size()!=5 || !merged.subList(0,2).equals(List.of("A","B"))) throw new AssertionError("evidence-seats");
        if(coverageMerge(List.of(),List.of(List.of()),5).size()!=0) throw new AssertionError("empty-no-invention");
        System.out.println("{\"coverage_contract_checks\":10,\"network_calls\":0,\"status\":\"passed\"}");
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("--coverage-contract")) { coverageContract(); return; }
        var output=System.out; System.setOut(System.err); MDC.clear();
        var mapper=new ObjectMapper(); byte[] bytes=System.in.readNBytes(524289);
        if(bytes.length>524288) throw new IllegalArgumentException("Bounded synthetic input");
        var input=mapper.readTree(bytes); var queries=input.path("queries"); var docs=input.path("documents");
        String profile=input.path("profile").asText("native-v1");
        if(!Set.of("native-v1","anchored-coverage-v1","bounded-feedback-v1").contains(profile)) throw new IllegalArgumentException("Frozen profile required");
        var strategies=new ArrayList<String>(STRATEGIES);
        if(profile.equals("anchored-coverage-v1")) strategies.add(COVERAGE);
        if(profile.equals("bounded-feedback-v1")) strategies=new ArrayList<>(List.of("hybrid-semantic", "hybrid-bounded-feedback"));
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
        var publicEntities=new TreeSet<String>();
        for(var row:docs) if(row.path("knowledge_base").asText().equals("product_knowledge") && row.path("visibility").asText().equals("public"))
            publicEntities.add(row.path("category").asText());
        hybrid.publicEntities=Set.copyOf(publicEntities);
        var order=new InMemoryKnowledgeBase("order_knowledge",model,tokenizer,null);
        order.addDocumentsAndBuildIndexes(corpora.get("order_knowledge"));
        var rows=new ArrayList<Map<String,Object>>(); var seen=new HashSet<String>();
        for(var q:queries) {
            String id=q.path("id").asText(),question=q.path("question").asText();
            if(!seen.add(id) || id.isBlank() || question.isBlank() || question.length()>2000)
                throw new IllegalArgumentException("Invalid query");
            var rankings=new LinkedHashMap<String,Object>();
            for(String strategy:strategies) {
                RecordingKb kb=strategy.equals("dense-semantic")?dense:hybrid;
                kb.facetMode=strategy.equals("hybrid-facet-rrf"); kb.coverageMode=strategy.equals(COVERAGE);
                kb.last=List.of(); kb.lastFacets=List.of(); int before=kb.calls;
                var retrieval=new KnowledgeRetrievalService().register(kb).register(order);
                var scope=new ProductKnowledgeScopeSelector(retrieval,new RagProductionProperties());
                var capture=new Capture();
                var pipeline=new RagSearchPipeline(List.of(new AdaptiveWeightHandler(),new KnowledgeSearchHandler(retrieval),
                        new RrfFusionHandler(),new DedupHandler(true,DedupHandler.DedupMode.AGGRESSIVE,.85),
                        new RerankHandler(new EmbeddingScorer(model::embedding),true,3,null,strategy.equals("hybrid-blend035")?.35:0),capture));
                var settings=new NativeRagProperties(); settings.setEnabled(false); settings.setMaxAttempts(1);
                settings.setAutomaticRetryEnabled(strategy.equals("hybrid-bounded-feedback"));
                var service=new ProductRagService(pipeline,scope,beans.getBeanProvider(SupplementalQueryPlanner.class),settings);
                var result=service.retrieveWithQualityResult(question,"synthetic-native-calibration");
                service.closeFeedback();
                var ctx=capture.context;
                if(ctx==null || ctx.isDegraded() || model.failed || kb.calls-before<1 || kb.calls-before>2)
                    throw new IllegalStateException("Invalid degraded or repeated attempt");
                List<String> leaves=kb.last.stream().map(h->h.getDocument().getId()).toList();
                List<String> finalIds=cids(result.getContent());
                if(!profile.equals("bounded-feedback-v1") && !leaves.equals(finalIds)) throw new IllegalStateException("Leaf/context mismatch");
                var rank=new LinkedHashMap<String,Object>(); rank.put("doc_ids",leaves); rank.put("context_doc_ids",finalIds);
                rank.put("fragments",ctx.getFusedResults().size()); rank.put("kb_service_calls",kb.calls-before);
                if(profile.equals("bounded-feedback-v1")) { rank.put("feedback", result.getDiagnostics().get("feedback")); rank.put("context",result.getContent()); }
                rank.put("native_query_count",kb.nativeRankings.size()); rank.put("native_rankings",List.copyOf(kb.nativeRankings));
                rank.put("facet_count",kb.lastFacets.size()); rank.put("facet_sha256",kb.lastFacets.stream().map(RagSearchContext::evidenceId).toList());
                rank.put("selected_domains",ctx.getAttribute("rag.knowledgeBases")); rank.put("scope_reason",ctx.getAttribute("rag.scopeReason"));
                rank.put("sparse_weight",ctx.getAttribute(AdaptiveWeightHandler.ATTR_SPARSE_WEIGHT));
                rank.put("dense_weight",ctx.getAttribute(AdaptiveWeightHandler.ATTR_DENSE_WEIGHT));
                rank.put("fusion_weight",strategy.equals("hybrid-blend035")?.35:0);
                rank.put("context_sha256",RagSearchContext.evidenceId(result.getContent()));
                rank.put("context_chars",result.getContent().length()); rank.put("degraded",false);
                if(kb.coverageMode) {
                    rank.put("entity_sha256",kb.bindings.stream().map(RagSearchContext::evidenceId).toList());
                    rank.put("reserved_doc_ids",kb.reserved); rank.put("protected_original_doc_ids",kb.protectedOriginal);
                }
                rankings.put(strategy,rank);
            }
            rows.add(Map.of("id",id,"question_sha256",RagSearchContext.evidenceId(question),"rankings",rankings));
        }
        output.println(mapper.writeValueAsString(Map.of("schema_version",1,"backend","native-memory-product-knowledge-subchain",
                "embedding_verified",true,"embedding_dimension",model.dimension,"embedding_calls",model.calls,
                "corpus",fingerprints,"leaf_top_k",5,"results",rows)));
    }
}
