package com.example.smartassistant.user.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class OAuthHttpClientConfigurationTest {

    @Test
    void providesNamedClientAndCreatesProviderGateway() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(OAuthHttpClientConfiguration.class);
            context.registerBean(OAuthProperties.class);
            context.registerBean(OAuthProviderGateway.class);
            context.refresh();

            assertNotNull(context.getBean("oauthRestClient", RestClient.class));
            assertNotNull(context.getBean(OAuthProviderGateway.class));
        }
    }
}
