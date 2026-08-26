package com.example.smartassistant.router.model;

import java.util.List;
import java.util.Locale;

/**
 * Parser for the constrained expression language used to pass predecessor
 * outputs between Router graph nodes.
 *
 * <p>Publication validation and runtime resolution deliberately share this
 * parser so a workflow cannot pass one phase and fail because the other phase
 * interpreted the same binding differently.</p>
 */
public final class InputBindingExpression {

    private InputBindingExpression() {
    }

    public enum Section {
        ANSWER,
        STATUS,
        AGENT_NAME,
        DATA
    }

    public record Parsed(String sourceNodeId, Section section, List<String> dataPath) {
        public Parsed {
            dataPath = dataPath != null ? List.copyOf(dataPath) : List.of();
        }
    }

    /**
     * Accepts either {@code $.nodes.<nodeId>.<section>} or
     * {@code <nodeId>.<section>}. Structured data bindings must name at least
     * zero or more fields after {@code data}; binding to {@code data} itself transfers the
     * complete structured output. Scalar sections cannot have a trailing path.
     */
    public static Parsed parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("binding expression is blank");
        }
        String normalized = expression.trim();
        if (normalized.startsWith("$.")) normalized = normalized.substring(2);
        String[] parts = normalized.split("\\.", -1);
        int index = parts.length > 0 && "nodes".equals(parts[0]) ? 1 : 0;
        if (parts.length - index < 2 || parts[index].isBlank()) {
            throw new IllegalArgumentException("binding source or section is missing");
        }

        String sourceNodeId = parts[index++];
        String sectionValue = parts[index++];
        Section section = switch (sectionValue.toLowerCase(Locale.ROOT)) {
            case "answer" -> Section.ANSWER;
            case "status" -> Section.STATUS;
            case "agentname" -> Section.AGENT_NAME;
            case "data" -> Section.DATA;
            default -> throw new IllegalArgumentException(
                    "unsupported binding section: " + sectionValue);
        };

        if (section == Section.DATA) {
            List<String> path = List.of(parts).subList(index, parts.length);
            if (path.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("data binding contains a blank field");
            }
            return new Parsed(sourceNodeId, section, path);
        }
        if (index != parts.length) {
            throw new IllegalArgumentException(
                    "scalar binding section cannot include a field path");
        }
        return new Parsed(sourceNodeId, section, List.of());
    }
}
