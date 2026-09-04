package com.example.smartassistant.common.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class EmbeddingClientTest {

    @Test
    void createsNativeBuilderWhenApplicationDoesNotProvideOne() {
        ObjectProvider<RestClient.Builder> builders = new StaticListableBeanFactory()
                .getBeanProvider(RestClient.Builder.class);

        assertDoesNotThrow(() -> new EmbeddingClient(builders, "http://localhost:8091"));
    }
}
