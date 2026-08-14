package com.example.smartassistant.router.workflow;

import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.example.smartassistant.router.service.core.LangGraphRouteExecutionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Draft, validation and immutable publication lifecycle for workflow definitions. */
@Service
public class WorkflowPublishService {

    private final WorkflowVersionRepository repository;
    private final WorkflowValidator validator;
    private final WorkflowGraphCompiler compiler;
    private final LangGraphRouteExecutionService langGraphRuntime;
    private final AgentDiscoveryService discoveryService;
    private final ObjectMapper objectMapper;

    public WorkflowPublishService(WorkflowVersionRepository repository,
                                  WorkflowValidator validator,
                                  WorkflowGraphCompiler compiler,
                                  LangGraphRouteExecutionService langGraphRuntime,
                                  AgentDiscoveryService discoveryService,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.validator = validator;
        this.compiler = compiler;
        this.langGraphRuntime = langGraphRuntime;
        this.discoveryService = discoveryService;
        this.objectMapper = objectMapper;
    }

    public WorkflowVersion createDraft(String workflowKey, WorkflowDefinition definition, Long actorId) {
        requireKey(workflowKey);
        if (actorId == null || actorId <= 0) throw new IllegalArgumentException("actorId is required");
        return repository.createDraft(workflowKey, definition, actorId);
    }

    public WorkflowValidationResult validate(WorkflowDefinition definition) {
        return validator.validate(definition, allowedAgents());
    }

    public PublishResult publish(String workflowKey, int version, Long actorId) {
        requireKey(workflowKey);
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        if (actorId == null || actorId <= 0) throw new IllegalArgumentException("actorId is required");
        WorkflowVersion draft = repository.find(workflowKey, version)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowKey, version));
        if (draft.status() != WorkflowVersion.Status.DRAFT) {
            return new PublishResult(false, draft, new WorkflowValidationResult(false, List.of(
                    new WorkflowValidationResult.Violation("DRAFT_REQUIRED", "status",
                            "only draft versions can be published"))));
        }
        WorkflowValidationResult validation = validate(draft.definition());
        if (!validation.valid()) return new PublishResult(false, draft, validation);

        IntentGraph graph = compiler.compile(draft.definition(), "workflow publication probe");
        try {
            // A real LangGraph4j compile is part of publication validation; no node is executed.
            langGraphRuntime.validateCompilation(graph);
        } catch (RuntimeException e) {
            WorkflowValidationResult compileFailure = new WorkflowValidationResult(false, List.of(
                    new WorkflowValidationResult.Violation("LANGGRAPH_COMPILE_FAILED", "nodes",
                            safeMessage(e))));
            return new PublishResult(false, draft, compileFailure);
        }

        String checksum = checksum(draft.definition());
        if (!repository.publish(workflowKey, version, checksum, actorId)) {
            throw new IllegalStateException("workflow draft changed before publication");
        }
        WorkflowVersion published = repository.find(workflowKey, version).orElseThrow();
        return new PublishResult(true, published, validation);
    }

    public IntentGraph compilePublished(String workflowKey, String question) {
        requireKey(workflowKey);
        WorkflowVersion published = repository.findPublished(workflowKey)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowKey, null));
        return compiler.compile(published.definition(), question);
    }

    public Optional<WorkflowVersion> find(String workflowKey, int version) {
        requireKey(workflowKey);
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        return repository.find(workflowKey, version);
    }

    public List<WorkflowVersion> list(String workflowKey) {
        requireKey(workflowKey);
        return repository.list(workflowKey);
    }

    private Set<String> allowedAgents() {
        Set<String> names = new TreeSet<>();
        names.add("product");
        names.add("product_agent");
        names.add("order");
        names.add("order_agent");
        names.add("general");
        names.add("general_agent");
        names.add("router_fallback");
        names.add("builtin_order_preparation");
        for (DiscoveredAgent agent : discoveryService.getCachedAgents()) {
            if (agent.getAgentName() != null) names.add(agent.getAgentName());
            if (agent.getServiceName() != null) names.add(agent.getServiceName());
        }
        return Set.copyOf(names);
    }

    private String checksum(WorkflowDefinition definition) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(definition);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("workflow definition cannot be serialized", e);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void requireKey(String workflowKey) {
        if (workflowKey == null || !workflowKey.matches("[a-z][a-z0-9_-]{2,63}")) {
            throw new IllegalArgumentException("workflowKey must match [a-z][a-z0-9_-]{2,63}");
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public record PublishResult(boolean published, WorkflowVersion workflow,
                                WorkflowValidationResult validation) {
    }

    public static final class WorkflowNotFoundException extends RuntimeException {
        public WorkflowNotFoundException(String key, Integer version) {
            super(version != null ? "workflow not found: " + key + "@" + version
                    : "published workflow not found: " + key);
        }
    }
}
