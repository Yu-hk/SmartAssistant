package com.example.smartassistant.router.service.evaluation;

import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates operation slots against schemas published by discovered Agents.
 * Router deliberately owns no product, order, travel, or other business slot table.
 */
@Service
public class SlotStateMachine {

    private static final Logger log = LoggerFactory.getLogger(SlotStateMachine.class);
    private static final TypeReference<Map<String, List<SlotDef>>> SLOT_SCHEMA_TYPE =
            new TypeReference<>() { };

    public record SlotDef(String name, String description, boolean required,
                          boolean defaultable, boolean highRisk, int askPriority,
                          String question) {
        public SlotDef(String name, String description, boolean required,
                       boolean defaultable, boolean highRisk, int askPriority) {
            this(name, description, required, defaultable, highRisk, askPriority, null);
        }
    }

    private final AgentDiscoveryService agentDiscoveryService;
    private final ObjectMapper objectMapper;
    private final Map<String, List<SlotDef>> localSchemas;
    private final Map<String, Map<String, List<SlotDef>>> parsedSchemaCache =
            new ConcurrentHashMap<>();

    @Autowired
    public SlotStateMachine(AgentDiscoveryService agentDiscoveryService, ObjectMapper objectMapper) {
        this(agentDiscoveryService, objectMapper, Map.of());
    }

    /** Isolated callers get no implicit business schema. */
    public SlotStateMachine() {
        this(null, new ObjectMapper(), Map.of());
    }

    /** Test/embedded callers supply their schema explicitly. */
    public SlotStateMachine(Map<String, List<SlotDef>> schemas) {
        this(null, new ObjectMapper(), schemas);
    }

    private SlotStateMachine(AgentDiscoveryService discoveryService, ObjectMapper mapper,
                             Map<String, List<SlotDef>> schemas) {
        this.agentDiscoveryService = discoveryService;
        this.objectMapper = mapper != null ? mapper : new ObjectMapper();
        this.localSchemas = schemas == null ? Map.of() : Map.copyOf(schemas);
    }

    /** Analyze all planner nodes with the schema of each node's target Agent. */
    public SlotAnalysisResult analyzeSlots(TaskAnalysisResult analysis) {
        if (analysis == null) return emptyResult();
        Map<String, SlotDef> definitions = new LinkedHashMap<>();
        for (Map<String, Object> subIntent : analysis.getSubIntents()) {
            String agent = stringValue(subIntent.get("target_agent"));
            String operation = stringValue(subIntent.get("operation"));
            indexByName(definitions, findSlotTable(agent, operation));
        }
        if (definitions.isEmpty()) {
            indexByName(definitions, findSlotTable(null, analysis.getIntentCategory()));
        }
        return analyze(List.copyOf(definitions.values()), analysis.getEntities(),
                analysis.getSlotConflicts());
    }

    /** Compatibility entry point: the first argument is a schema/operation key. */
    public SlotAnalysisResult analyzeSlots(String operation, Map<String, Object> entities) {
        return analyze(findSlotTable(null, operation), entities, List.of());
    }

    public List<String> getClarificationPriority(String operation, Map<String, Object> entities) {
        SlotAnalysisResult result = analyzeSlots(operation, entities);
        Set<String> unresolved = new LinkedHashSet<>(result.missingSlots());
        unresolved.addAll(result.defaultableSlots());
        return result.slotDefs().stream()
                .filter(def -> unresolved.contains(def.name()))
                .sorted(Comparator.comparingInt(SlotDef::askPriority))
                .map(SlotDef::name)
                .toList();
    }

    public boolean needsClarification(String operation, Map<String, Object> entities) {
        return analyzeSlots(operation, entities).hasMissing();
    }

    private SlotAnalysisResult analyze(List<SlotDef> definitions, Map<String, Object> entities,
                                       List<Map<String, Object>> existingConflicts) {
        if (definitions == null || definitions.isEmpty()) return emptyResult();
        Map<String, Object> safeEntities = entities == null ? Map.of() : entities;
        Set<String> normalizedKeys = new LinkedHashSet<>();
        safeEntities.forEach((key, value) -> {
            if (value != null && !value.toString().isBlank()
                    && !"null".equalsIgnoreCase(value.toString())) {
                normalizedKeys.add(normalizeKey(key));
            }
        });

        List<String> filled = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> defaultable = new ArrayList<>();
        for (SlotDef slot : definitions) {
            String normalizedSlot = normalizeKey(slot.name());
            boolean present = normalizedKeys.stream().anyMatch(key ->
                    key.equals(normalizedSlot) || key.contains(normalizedSlot)
                            || normalizedSlot.contains(key));
            if (present) {
                filled.add(slot.name());
            } else if (slot.required() && slot.defaultable()) {
                defaultable.add(slot.name());
            } else if (slot.required()) {
                missing.add(slot.name());
            }
        }
        List<Map<String, Object>> conflicts = existingConflicts == null
                ? List.of() : List.copyOf(existingConflicts);
        return new SlotAnalysisResult(filled, missing, defaultable, conflicts,
                List.copyOf(definitions));
    }

    private List<SlotDef> findSlotTable(String agentName, String operation) {
        if (operation == null || operation.isBlank()) return List.of();
        List<SlotDef> local = localSchemas.get(operation);
        if (local != null) return local;
        if (agentDiscoveryService == null) return List.of();

        Collection<DiscoveredAgent> agents = agentDiscoveryService.getCachedAgents();
        if (agents == null) return List.of();
        String canonicalTarget = AgentDiscoveryService.canonicalAgentName(agentName);
        for (DiscoveredAgent agent : agents) {
            if (agent == null || !Boolean.TRUE.equals(agent.getHealthy())) continue;
            String discoveredName = AgentDiscoveryService.canonicalAgentName(
                    firstNonBlank(agent.getAgentName(), agent.getServiceName()));
            if (!canonicalTarget.isBlank() && !canonicalTarget.equals(discoveredName)) continue;
            AgentMetadata metadata = agent.getMetadata();
            String rawSchema = metadata != null ? metadata.getSlotDefinitions() : null;
            List<SlotDef> definitions = parseSchemas(rawSchema).get(operation);
            if (definitions != null) return definitions;
        }
        return List.of();
    }

    private Map<String, List<SlotDef>> parseSchemas(String rawSchema) {
        if (rawSchema == null || rawSchema.isBlank()) return Map.of();
        return parsedSchemaCache.computeIfAbsent(rawSchema, value -> {
            try {
                return objectMapper.readValue(value, SLOT_SCHEMA_TYPE);
            } catch (Exception error) {
                log.warn("[SlotStateMachine] Ignore invalid Agent slot-definitions: {}",
                        error.getMessage());
                return Map.of();
            }
        });
    }

    private static void indexByName(Map<String, SlotDef> target, List<SlotDef> definitions) {
        if (definitions == null) return;
        definitions.forEach(def -> {
            if (def != null && def.name() != null && !def.name().isBlank()) {
                target.putIfAbsent(def.name(), def);
            }
        });
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static SlotAnalysisResult emptyResult() {
        return new SlotAnalysisResult(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public record SlotAnalysisResult(List<String> filledSlots, List<String> missingSlots,
                                     List<String> defaultableSlots,
                                     List<Map<String, Object>> conflicts,
                                     List<SlotDef> slotDefs) {
        public boolean hasMissing() { return missingSlots != null && !missingSlots.isEmpty(); }
        public boolean hasConflicts() { return conflicts != null && !conflicts.isEmpty(); }
        public boolean hasDefaultable() {
            return defaultableSlots != null && !defaultableSlots.isEmpty();
        }
    }
}
