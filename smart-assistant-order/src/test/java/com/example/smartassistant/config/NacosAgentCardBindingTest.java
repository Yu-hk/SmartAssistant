package com.example.smartassistant.config;

import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NacosAgentCardBindingTest {

    @Test
    void springBootBinderBindsNacosAgentCardAndNestedSkillsDirectly() {
        String prefix = "nacos.a2a.registry.agent-card";
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(prefix + ".name", "OrderAgent");
        values.put(prefix + ".protocol-version", "0.3.0");
        values.put(prefix + ".preferred-transport", "JSONRPC");
        values.put(prefix + ".skills[0].id", "create-order");
        values.put(prefix + ".skills[0].name", "创建订单");
        values.put(prefix + ".skills[0].tags", "order,create");
        values.put(prefix + ".skills[0].examples[0]", "帮我下单");

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));
        AgentCard card = new AgentCard();

        Binder.get(environment).bind(prefix, Bindable.ofInstance(card));

        assertThat(card.getName()).isEqualTo("OrderAgent");
        assertThat(card.getProtocolVersion()).isEqualTo("0.3.0");
        assertThat(card.getPreferredTransport()).isEqualTo("JSONRPC");
        assertThat(card.getSkills()).singleElement().satisfies(skill -> {
            assertThat(skill.getId()).isEqualTo("create-order");
            assertThat(skill.getTags()).containsExactly("order", "create");
            assertThat(skill.getExamples()).containsExactly("帮我下单");
        });
    }
}
