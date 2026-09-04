package com.example.smartassistant.router.service.intent;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic intent fingerprint shared by Consumer and Router.
 *
 * <p>The generator is deliberately stateless. Cache storage and user intent
 * accounting belong to Consumer; Router only uses the tag while coordinating
 * a request.</p>
 */
@Component
public class IntentTagGenerator {

    private final ChineseTokenizer tokenizer;

    public IntentTagGenerator(ChineseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public String generate(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        List<String> keywords = tokenizer.tokenize(question).stream()
                .filter(token -> token != null && token.length() >= 2)
                .filter(token -> !token.matches("\\d+"))
                .distinct()
                .sorted()
                .toList();
        return keywords.isEmpty() ? null : String.join(",", keywords);
    }
}
