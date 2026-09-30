package com.example.smartassistant.service.core;

import com.example.smartassistant.common.jev.JevDecisionClient;
import com.example.smartassistant.spi.ProductIdentity;
import org.springframework.stereotype.Component;
import java.util.*;

/** Candidate-only semantic suggestion. A probability is not authority to choose an SKU. */
@Component
public class JevProductEntityAdvisor {
    private final JevDecisionClient client;
    public JevProductEntityAdvisor(JevDecisionClient client) { this.client = client; }
    public record Suggestion(String code, double confidence) { }
    public Optional<Suggestion> suggest(String mention, List<ProductIdentity> candidates, String requestId) {
        if (candidates.isEmpty() || candidates.size() > 5 || !client.available()) return Optional.empty();
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("UNKNOWN", "没有匹配商品，或缺少足够的型号与代际信息");
        criteria.put("AMBIGUOUS", "多个候选合理，需要用户核实版本");
        for (int i = 0; i < candidates.size(); i++) criteria.put("C" + i, candidates.get(i).descriptor());
        return client.evaluate("用户的商品提及：" + mention,
                Map.of("entity", Map.of("type", "choice", "instructions",
                        "只从候选目录选择最可能的身份。不得补充商品事实；不要把系列当成唯一型号。", "criteria", criteria)), requestId)
                .flatMap(decision -> {
                    String choice = decision.choice("entity");
                    double confidence = decision.confidence("entity");
                    if (!choice.matches("C[0-4]") || !Double.isFinite(confidence) || confidence < .90 || confidence > 1)
                        return Optional.empty();
                    int index = choice.charAt(1) - '0';
                    return index < candidates.size() ? Optional.of(new Suggestion(candidates.get(index).code(), confidence)) : Optional.empty();
                });
    }
}
