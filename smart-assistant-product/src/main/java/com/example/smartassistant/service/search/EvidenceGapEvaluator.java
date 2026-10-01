package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic online proxy. Not Ragas recall, precision, faithfulness or relevancy. */
final class EvidenceGapEvaluator {
    private static final Set<String> STOP = Set.of("如何", "怎么", "为什么", "什么", "是否", "可以", "能否",
            "请问", "请", "介绍", "说明", "根据", "回答", "问题", "知识库", "一下", "目前", "现在", "哪些", "是什么");
    private static final Pattern CID = Pattern.compile("\\[CID:([^\\]]+)]");
    static final int MAX_CONTEXT_CHARS = 12000;
    private static final Pattern CHUNK = Pattern.compile("(?m)^\\d+\\. 【([^\\r\\n】]+)】（相关度: [^\\r\\n]+\\[CID:([^]\\r\\n]+)]([^\\r\\n]*)\\r?\\n");

    record Chunk(String id, String title, String body, String raw) { }

    /** Conservative parser of KnowledgeHit.toContext, never interpret opaque IDs as entities. */
    private static List<Chunk> chunks(String text) {
        var matcher = CHUNK.matcher(text);
        List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
        List<String> ids = new ArrayList<>(), titles = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start()); ends.add(matcher.end()); ids.add(matcher.group(2)); titles.add(matcher.group(1));
        }
        Set<String> allIds = new LinkedHashSet<>();
        var citations = CID.matcher(text);
        while (citations.find()) if (!allIds.add(citations.group(1))) return List.of();
        if (starts.isEmpty() || !allIds.equals(new LinkedHashSet<>(ids))) return List.of();
        if (!text.substring(0, starts.getFirst()).strip().matches("📚 知识库「[^\\r\\n]+」查询结果：")) return List.of();
        List<Chunk> result = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            result.add(new Chunk(ids.get(i), titles.get(i), text.substring(ends.get(i), end), text.substring(starts.get(i), end)));
        }
        return result;
    }

    private static String anchor(String query, String title) {
        String normalized = query.toLowerCase(Locale.ROOT);
        String name = title.toLowerCase(Locale.ROOT);
        for (int length = Math.min(64, name.length()); length >= 3; length--) {
            String prefix = name.substring(0, length);
            if (prefix.codePoints().allMatch(Character::isLetterOrDigit) && normalized.contains(prefix)) return prefix;
        }
        return "";
    }

    /** Preserve the exact first context; add only new bodies with missing terms and a shared explicit title anchor. */
    static void constrainCandidate(String query, RagSearchContext baseline, RagSearchContext candidate, Assessment before) {
        var first = baseline.getPathResults().get("知识库");
        var second = candidate.getPathResults().get("知识库");
        List<String> oldTexts = first == null ? List.of() : first.getItems();
        List<String> newTexts = second == null ? List.of() : second.getItems();
        List<Chunk> oldChunks = oldTexts.stream().flatMap(s -> chunks(s).stream()).toList();
        List<Chunk> newChunks = newTexts.stream().flatMap(s -> chunks(s).stream()).toList();
        Set<String> oldIds = new LinkedHashSet<>(); oldChunks.forEach(c -> oldIds.add(c.id()));
        Set<String> newIds = new LinkedHashSet<>(); newChunks.forEach(c -> newIds.add(c.id()));
        Set<String> anchors = new LinkedHashSet<>();
        oldChunks.stream().map(c -> anchor(query, c.title())).filter(s -> !s.isEmpty()).forEach(anchors::add);
        Set<String> missing = requiredTerms(query); missing.removeAll(before.matched());
        List<String> selected = new ArrayList<>(oldTexts);
        int accepted = 0;
        // Unparseable old evidence or missing old citations is not a safe expansion.
        if ((oldTexts.isEmpty() || (!oldChunks.isEmpty() && oldIds.containsAll(before.citations()))) && newIds.containsAll(oldIds)) {
            StringBuilder added = new StringBuilder();
            for (Chunk chunk : newChunks) {
                if (oldIds.contains(chunk.id()) || oldIds.size() >= 8) continue;
                String entity = anchor(query, chunk.title());
                boolean sameEntity = !entity.isEmpty() && (oldTexts.isEmpty() || anchors.stream().anyMatch(a ->
                        chunk.title().toLowerCase(Locale.ROOT).startsWith(a)));
                String body = chunk.body().toLowerCase(Locale.ROOT);
                if (!sameEntity || missing.stream().noneMatch(body::contains)) continue;
                if (accepted++ == 0) added.append("📚 知识库「补充证据」查询结果：\n\n");
                added.append(chunk.raw());
                oldIds.add(chunk.id()); // deduplicate across knowledge domains
            }
            if (accepted > 0) selected.add(added.toString());
        }
        candidate.addPathResult("知识库", selected);
        candidate.setAttribute("rag.feedbackAddedChunks", accepted);
    }

    record Assessment(Set<String> matched, int required, Set<String> citations, List<String> evidenceTexts, int chars, boolean valid) {
        double coverage() { return required == 0 ? 1 : (double) matched.size() / required; }
        Map<String, Object> diagnostic() {
            // No query tokens, document contents or user identities in telemetry.
            return Map.of("kind", "lexical_requirement_coverage_proxy_not_ragas", "coverage", coverage(),
                    "requiredCount", required, "matchedCount", matched.size(), "contextChars", chars,
                    "citationCount", citations.size(), "valid", valid);
        }
    }

    private static Set<String> requiredTerms(String query) {
        Set<String> required = new LinkedHashSet<>();
        try {
            String normalized = query.toLowerCase(Locale.ROOT);
            // Remove interrogatives before segmentation; smart mode can join them to a fact noun.
            for (String stop : STOP.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList())
                normalized = normalized.replace(stop, " ");
            IKSegmenter segmenter = new IKSegmenter(new StringReader(normalized), true);
            Lexeme word;
            while ((word = segmenter.next()) != null) {
                String token = word.getLexemeText();
                if (token.length() > 1 && !STOP.contains(token)) required.add(token);
            }
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
        return required;
    }

    static Assessment assess(String query, RagSearchContext context, int maxItems) {
        Set<String> required = requiredTerms(query);
        List<RagSearchContext.RankedItem> items = context.getFusedResults().stream().limit(maxItems).toList();
        StringBuilder body = new StringBuilder();
        boolean finite = Double.isFinite(context.getQualityScore()) && Double.isFinite(context.getQualityThreshold());
        for (var item : items) {
            finite &= Double.isFinite(item.getRrfScore());
            if (item.getContent() != null) body.append(item.getContent()).append('\n');
        }
        String text = body.toString().toLowerCase(Locale.ROOT);
        Set<String> matched = new LinkedHashSet<>();
        required.stream().filter(text::contains).forEach(matched::add);
        Set<String> citations = new LinkedHashSet<>();
        var matcher = CID.matcher(body);
        while (matcher.find()) citations.add(matcher.group(1));
        return new Assessment(Set.copyOf(matched), required.size(), Set.copyOf(citations),
                items.stream().map(RagSearchContext.RankedItem::getContent).filter(Objects::nonNull).toList(), body.length(),
                finite && !context.isDegraded() && !context.isTerminated() && body.length() <= MAX_CONTEXT_CHARS);
    }

    static boolean improves(Assessment baseline, Assessment candidate, RagSearchContext result) {
        return candidate.valid() && candidate.required() > 0 && !candidate.citations().isEmpty()
                && candidate.matched().containsAll(baseline.matched())
                && candidate.citations().containsAll(baseline.citations())
                && baseline.evidenceTexts().stream().allMatch(String.join("\n", candidate.evidenceTexts())::contains)
                && candidate.coverage() >= 0.6 && candidate.coverage() + 1e-9 >= baseline.coverage() + 0.1
                && result.getQualityScore() >= result.getQualityThreshold();
    }
}
