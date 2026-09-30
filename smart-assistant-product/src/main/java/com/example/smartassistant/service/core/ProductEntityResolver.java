package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Catalog-anchored mention detection and linking, independent of query word order. */
@Component
public class ProductEntityResolver {
    public enum Status { RESOLVED, AMBIGUOUS, NOT_FOUND, REFERENCE_UNRESOLVED }
    public record Candidate(String code, String name, String source, double retrievalScore) { }
    public record Mention(String surface, int start, int end, Status status, String code,
                          List<Candidate> candidates, String basis, Double semanticConfidence) {
        public Mention { candidates = List.copyOf(candidates); }
    }
    private record Hit(String surface, int start, int end, String key, String basis) { }
    private record Snapshot(List<ProductIdentity> identities, Map<String, List<ProductIdentity>> names, long expires) { }
    private static final Pattern REFERENCE = Pattern.compile("前面[两二2]款|这[两二2]款|它们|第[一二三四1-4]款|这款|这个|那款|那个|(?<![\\p{IsHan}])它(?!们)");
    private static final Pattern UNKNOWN = Pattern.compile("(?:(?<=^)|(?<=[和与、，,；;：:]))\\s*(?:请帮我|帮我|查询|查一下|查下|看看|请|对比|比较|那)?\\s*([^，,；;？?和与、：:]{2,65}?)(?:的)?(?=价格|多少钱|售价|重量|多重|续航|降噪|颜色|规格|库存|有货|合计|总价|每款)");
    // Detect the numeric boundary even when the value is invalid; the planner must clarify it.
    private static final Pattern QUANTITY_TAIL = Pattern.compile("^\\s*[0-9]+(?:\\.[0-9]+)?\\s*[件个台副]");
    private static final Pattern UNKNOWN_LIST = Pattern.compile("(?:^|[和与、])\\s*(?:请帮我|帮我|查询|查一下|查下|看看|请|对比|比较)?\\s*([^，,；;？?和与、：:]{2,65}?)(?=\\s*[和与、])");
    private final ProductBackend backend;
    private final JevProductEntityAdvisor advisor;
    private volatile Snapshot snapshot;
    @Autowired public ProductEntityResolver(ProductBackend backend, JevProductEntityAdvisor advisor) {
        this.backend = backend; this.advisor = advisor;
    }
    public ProductEntityResolver(ProductBackend backend) { this(backend, null); }

    /** No process-wide conversational state: callers supply this session's bounded history. */
    public List<Mention> resolve(String question, List<String> history, String requestId) {
        Snapshot catalog = currentCatalog();
        if (catalog.identities().isEmpty()) return List.of();
        List<Mention> mentions = new ArrayList<>(detect(question, catalog, requestId, true));
        var correction = Pattern.compile("不是([^，,；;]{1,30})[，,；;]\\s*(?:而是|是)([^，,；;？?]{1,30}?)(?=[，,；;？?]|多少钱|价格|重量|续航|降噪|颜色|$)").matcher(question);
        if (correction.find()) {
            List<ProductIdentity> prior = context(history, catalog);
            String replacement = correction.group(2).trim().replaceFirst("的$", "");
            int start = correction.start(2), end = start + replacement.length();
            String negated = ProductIdentity.normalize(correction.group(1));
            boolean matchesPrior = prior.size() == 1 && (ProductIdentity.normalize(prior.getFirst().variant()).equals(negated)
                    || prior.getFirst().names().stream().anyMatch(n -> ProductIdentity.normalize(n).equals(negated)));
            List<ProductIdentity> matches = matchesPrior && !prior.getFirst().family().isBlank()
                    ? catalog.identities().stream().filter(p -> p.family().equals(prior.getFirst().family()))
                        .filter(p -> ProductIdentity.normalize(p.variant()).equals(ProductIdentity.normalize(replacement))
                                || p.names().stream().anyMatch(n -> ProductIdentity.normalize(n).equals(ProductIdentity.normalize(replacement))))
                        .toList() : List.of();
            // The negated mention cannot remain a positive product target.
            mentions.removeIf(m -> m.start() < correction.end());
            mentions.add(matches.isEmpty() ? new Mention(replacement, start, end, Status.REFERENCE_UNRESOLVED, "",
                    prior.stream().map(p -> candidate(p, "CONTEXT", 1)).toList(), "CORRECTION_REQUIRES_VARIANT", null)
                    : link(replacement, start, end, matches, "CONTEXT_EXPLICIT_CORRECTION"));
        }
        var refs = REFERENCE.matcher(question);
        List<ProductIdentity> context = null;
        while (refs.find()) {
            int start = refs.start(), end = refs.end();
            if (mentions.stream().anyMatch(m -> m.start() < end && m.end() > start)) continue;
            if (context == null) context = context(history, catalog);
            String ref = refs.group();
            List<ProductIdentity> selected = List.of();
            if (ref.contains("两") || ref.contains("二2") || ref.equals("它们") || ref.matches(".*[二2]款") && !ref.startsWith("第")) {
                if (context.size() == 2) selected = context;
            } else if (ref.startsWith("第")) {
                int ordinal = "一二三四".indexOf(ref.charAt(1));
                if (ordinal < 0) ordinal = ref.charAt(1) - '1';
                if (ordinal >= 0 && ordinal < context.size()) selected = List.of(context.get(ordinal));
            } else if (context.size() == 1) selected = context;
            if (selected.isEmpty()) mentions.add(new Mention(ref, start, end, Status.REFERENCE_UNRESOLVED, "",
                    context.stream().map(p -> candidate(p, "CONTEXT", 1)).toList(), "CONTEXT_INSUFFICIENT", null));
            else for (var p : selected) mentions.add(new Mention(ref, start, end, Status.RESOLVED, p.code(),
                    List.of(candidate(p, "CONTEXT", 1)), "CONTEXT_DISPLAY_ORDER", null));
        }
        mentions.sort(Comparator.comparingInt(Mention::start));
        return List.copyOf(mentions);
    }

    private List<Mention> detect(String question, Snapshot catalog, String requestId, boolean semantic) {
        List<Hit> hits = new ArrayList<>();
        // Normalized comparison uses original character spans, including full-width letters and spaces.
        for (int start = 0; start < question.length(); start++) {
            if (start > 0 && asciiWord(question.charAt(start - 1)) && asciiWord(question.charAt(start))) continue;
            for (int end = start + 1; end <= Math.min(question.length(), start + 80); end++) {
                if (end < question.length() && asciiWord(question.charAt(end))
                        && !QUANTITY_TAIL.matcher(question.substring(end)).find()) continue;
                String surface = question.substring(start, end);
                if (surface.isBlank() || Character.isWhitespace(surface.charAt(0)) || Character.isWhitespace(surface.charAt(surface.length() - 1))) continue;
                String key = ProductIdentity.normalize(surface);
                if (key.length() < 2 || !catalog.names().containsKey(key)) continue;
                // A following Latin model suffix is not ignorable formatting (Pro != Pro Max).
                int next = end;
                while (next < question.length() && Character.isWhitespace(question.charAt(next))) next++;
                if (next < question.length() && asciiWord(question.charAt(next))
                        && !QUANTITY_TAIL.matcher(question.substring(next)).find()) continue;
                if (Pattern.compile("^[（(]?(?:第[一二三四五六七八九十0-9]+代|[0-9]+代)")
                        .matcher(question.substring(next)).find()) continue;
                hits.add(new Hit(surface, start, end, key, "EXACT_CATALOG_NORMALIZED"));
            }
        }
        hits.sort(Comparator.comparingInt((Hit h) -> h.end() - h.start()).reversed().thenComparingInt(Hit::start));
        List<Mention> result = new ArrayList<>();
        for (var hit : hits) {
            if (result.stream().anyMatch(m -> m.start() < hit.end() && m.end() > hit.start())) continue;
            List<ProductIdentity> matches = catalog.names().get(hit.key());
            result.add(link(hit.surface(), hit.start(), hit.end(), matches, hit.basis()));
        }
        for (Pattern unknownPattern : List.of(UNKNOWN, UNKNOWN_LIST)) {
        var unknown = unknownPattern.matcher(question);
        while (unknown.find()) {
            String value = unknown.group(1).trim().replaceFirst("的$", "");
            int start = unknown.start(1), end = start + value.length();
            if (REFERENCE.matcher(value).matches() || result.stream().anyMatch(m -> m.start() < end && m.end() > start)) continue;
            if (value.matches(".*(?:分别|各自|每款|总预算|合计|多少|查询|看看|[0-9]+件).*")) continue;
            if (value.matches(".*(?:价格|重量|续航|降噪|有货|库存|规格|颜色).*")) continue;
            List<ProductIdentity> candidates = recall(value, catalog.identities());
            var suggestion = semantic && advisor != null ? advisor.suggest(value, candidates, requestId) : Optional.<JevProductEntityAdvisor.Suggestion>empty();
            List<Candidate> ranked = candidates.stream().map(p -> candidate(p, "LEXICAL_CANDIDATE", lexical(value, p))).toList();
            String basis = suggestion.isPresent() ? "JEV_SUGGESTION_REQUIRES_CONFIRMATION" : "NO_UNIQUE_EXACT_IDENTITY";
            if (suggestion.isPresent()) ranked = ranked.stream().sorted(Comparator.comparing(c -> !c.code().equals(suggestion.get().code()))).toList();
            result.add(new Mention(value, start, end, candidates.isEmpty() ? Status.NOT_FOUND : Status.AMBIGUOUS, "", ranked,
                    basis, suggestion.map(JevProductEntityAdvisor.Suggestion::confidence).orElse(null)));
        }
        }
        result.sort(Comparator.comparingInt(Mention::start));
        return result;
    }

    private List<ProductIdentity> context(List<String> history, Snapshot catalog) {
        if (history == null) return List.of();
        List<ProductIdentity> displayed = List.of();
        for (int i = history.size() - 1; i >= Math.max(0, history.size() - 10); i--) {
            String turn = Objects.toString(history.get(i), "");
            if (turn.startsWith("助手：") || turn.startsWith("助手:")) {
                if (displayed.isEmpty()) displayed = identities(detect(turn.substring(3), catalog, null, false), catalog);
            } else if (turn.startsWith("用户：") || turn.startsWith("用户:")) {
                List<Mention> prior = detect(turn.substring(3), catalog, null, false);
                if (!prior.isEmpty()) {
                    if (prior.stream().anyMatch(m -> m.status() != Status.RESOLVED)) return List.of();
                    return displayed.isEmpty() ? identities(prior, catalog) : displayed;
                }
                // Only a pure reference follow-up can continue the same subject.
                if (!REFERENCE.matcher(turn.substring(3)).find()) return List.of();
            }
        }
        return List.of();
    }
    private static List<ProductIdentity> identities(List<Mention> mentions, Snapshot catalog) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        mentions.stream().filter(m -> m.status() == Status.RESOLVED).forEach(m -> codes.add(m.code()));
        return codes.stream().map(code -> catalog.identities().stream().filter(p -> p.code().equals(code)).findFirst().orElseThrow()).toList();
    }
    private static Mention link(String surface, int start, int end, List<ProductIdentity> products, String basis) {
        boolean familyOnly = products.stream().anyMatch(p -> !p.family().isBlank()
                && ProductIdentity.normalize(p.family()).equals(ProductIdentity.normalize(surface))
                && !ProductIdentity.normalize(p.name()).equals(ProductIdentity.normalize(surface)));
        boolean unique = products.size() == 1 && !familyOnly;
        return new Mention(surface, start, end, unique ? Status.RESOLVED : Status.AMBIGUOUS,
                unique ? products.getFirst().code() : "",
                products.stream().map(p -> candidate(p, basis, 1)).toList(), basis, null);
    }
    private static Candidate candidate(ProductIdentity p, String source, double score) { return new Candidate(p.code(), p.name(), source, score); }
    private static List<ProductIdentity> recall(String mention, List<ProductIdentity> catalog) {
        return catalog.stream().filter(p -> lexical(mention, p) >= .25)
                .sorted(Comparator.<ProductIdentity>comparingDouble(p -> lexical(mention, p)).reversed().thenComparing(ProductIdentity::code))
                .limit(5).toList();
    }
    /** Character bigram overlap is a retrieval score, explicitly not a confidence probability. */
    private static double lexical(String mention, ProductIdentity identity) {
        String a = ProductIdentity.normalize(mention), b = ProductIdentity.normalize(identity.descriptor());
        if (a.length() < 2 || b.length() < 2) return 0;
        Set<String> grams = new HashSet<>();
        for (int i = 0; i < a.length() - 1; i++) grams.add(a.substring(i, i + 2));
        long overlap = grams.stream().filter(b::contains).count();
        return (double) overlap / grams.size();
    }
    private synchronized Snapshot currentCatalog() {
        long now = System.nanoTime();
        if (snapshot != null && now < snapshot.expires()) return snapshot;
        // Never serve a stale snapshot after a failed refresh: identity might have changed.
        List<ProductIdentity> identities = backend.listProductIdentities();
        if (identities == null) identities = List.of();
        Map<String, List<ProductIdentity>> names = new LinkedHashMap<>();
        for (var identity : identities) for (String name : identity.names()) {
            String key = ProductIdentity.normalize(name);
            List<ProductIdentity> matches = names.computeIfAbsent(key, unused -> new ArrayList<>());
            if (matches.stream().noneMatch(p -> p.code().equals(identity.code()))) matches.add(identity);
        }
        Map<String, List<ProductIdentity>> immutable = new LinkedHashMap<>();
        names.forEach((key, values) -> immutable.put(key, List.copyOf(values)));
        snapshot = new Snapshot(List.copyOf(identities), Collections.unmodifiableMap(immutable), now + TimeUnit.SECONDS.toNanos(30));
        return snapshot;
    }
    private static boolean asciiWord(char c) {
        String normalized = java.text.Normalizer.normalize(String.valueOf(c), java.text.Normalizer.Form.NFKC);
        return normalized.length() == 1 && normalized.charAt(0) < 128 && Character.isLetterOrDigit(normalized.charAt(0));
    }
}
