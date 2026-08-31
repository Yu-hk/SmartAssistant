package com.example.smartassistant.user.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class OAuthHttpClientConfiguration {

    @Bean("oauthRestClient")
    RestClient oauthRestClient() {
        return RestClient.builder().build();
    }
}
