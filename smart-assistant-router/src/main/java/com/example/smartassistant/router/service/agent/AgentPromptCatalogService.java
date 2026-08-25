package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the bounded Agent capability snapshot injected into the route planner prompt.
 *
 * <p>Nacos metadata is external data rather than trusted prompt text. Only routing fields
 * needed by the current execution compiler are retained, normalized and length limited.</p>
 */
@Service
public class AgentPromptCatalogService {

    private static final Set<String> EXECUTABLE_ROUTES = Set.of("product", "order", "general");
    private static final Pattern UNSAFE_METADATA = Pattern.compile("[^\\p{L}\\p{N} _.,:/+\\-]");
    private static final Pattern PROMPT_INSTRUCTION = Pattern.compile(
            "(?i)(忽略|绕过|覆盖).{0,8}(规则|指令|提示)|system\\s*prompt|previous\\s+instructions?");
    private static final int MAX_FIELD_LENGTH = 160;
    private static final int MAX_EXAMPLES = 4;
    private static final int MAX_EXAMPLE_LENGTH = 60;
    private final AgentDiscoveryService agentDiscoveryService;

    public AgentPromptCatalogService(AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }

    /**
     * Returns deterministic prompt lines for the latest healthy Nacos discovery snapshot.
     * General is always present because it is also implemented as a Router-local fallback.
     */
    public String buildCatalog() {
        Map<String, CatalogEntry> entries = new LinkedHashMap<>();
        Collection<DiscoveredAgent> cachedAgents = agentDiscoveryService.getCachedAgents();
        if (cachedAgents != null) {
            cachedAgents.stream()
                    .filter(agent -> agent != null && Boolean.TRUE.equals(agent.getHealthy()))
                    .map(this::toCatalogEntry)
                    .filter(entry -> entry != null && EXECUTABLE_ROUTES.contains(entry.routeName()))
                    .sorted(Comparator.comparingInt(CatalogEntry::priority).reversed()
                            .thenComparing(CatalogEntry::routeName)
                            .thenComparing(CatalogEntry::serviceName))
                    .forEach(entry -> entries.putIfAbsent(entry.routeName(), entry));
        }
        entries.putIfAbsent("general", new CatalogEntry(
                "general", "router-local", "通用问答,无匹配远程Agent时兜底",
                List.of("回答不属于远程应用场景的通用问题"), 0));

        return entries.values().stream()
                .sorted(Comparator.comparing(CatalogEntry::routeName))
                .map(entry -> "- route_name=" + entry.routeName()
                        + "; source=" + ("router-local".equals(entry.serviceName()) ? "local" : "nacos")
                        + "; service_name=" + entry.serviceName()
                        + "; capabilities=[" + entry.capabilities() + "]"
                        + "; examples=[" + formatExamples(entry.examples()) + "]")
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- route_name=general; source=local; service_name=router-local; "
                        + "capabilities=[通用问答]; examples=[\"回答通用问题\"]");
    }

    private CatalogEntry toCatalogEntry(DiscoveredAgent agent) {
        String routeName = AgentDiscoveryService.canonicalAgentName(
                firstNonBlank(agent.getAgentName(), agent.getServiceName()));
        if (routeName.isBlank()) return null;

        AgentMetadata metadata = agent.getMetadata();
        String capabilities = metadata != null ? metadata.getCapabilities() : "";
        if (capabilities == null || capabilities.isBlank()) {
            capabilities = metadata != null ? metadata.getKeywords() : "";
        }
        int priority = metadata != null && metadata.getPriority() != null
                ? metadata.getPriority() : 0;
        List<String> examples = metadata == null ? List.of()
                : java.util.Arrays.stream(metadata.getRoutingExamplesArray())
                        .map(example -> sanitize(example, MAX_EXAMPLE_LENGTH))
                        .filter(example -> !example.isBlank())
                        .filter(example -> !PROMPT_INSTRUCTION.matcher(example).find())
                        .limit(MAX_EXAMPLES)
                        .toList();
        return new CatalogEntry(
                sanitize(routeName.toLowerCase(Locale.ROOT)),
                sanitize(firstNonBlank(agent.getServiceName(), agent.getAgentName())),
                sanitize(capabilities),
                examples,
                priority);
    }

    private static String formatExamples(List<String> examples) {
        return examples.stream()
                .map(example -> "\"" + example + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private static String sanitize(String value) {
        return sanitize(value, MAX_FIELD_LENGTH);
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        normalized = UNSAFE_METADATA.matcher(normalized).replaceAll("");
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength);
        }
        return normalized;
    }

    private record CatalogEntry(String routeName, String serviceName,
                                String capabilities, List<String> examples, int priority) {
    }
}
