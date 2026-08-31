package com.example.smartassistant.user.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class OAuthProviderGatewayTest {

    @Test
    void exchangesDingTalkCodeUsingJackson3ResponseTypes() {
        OAuthProperties properties = new OAuthProperties();
        properties.getDingtalk().setEnabled(true);
        properties.getDingtalk().setClientId("ding-client");
        properties.getDingtalk().setClientSecret("ding-secret");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.dingtalk.com/v1.0/oauth2/userAccessToken"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"accessToken\":\"access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.dingtalk.com/v1.0/contact/users/me"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"openId":"ding-open-id","unionId":"ding-union-id","nick":"测试用户",
                         "email":"user@example.com","avatarUrl":"https://example.com/avatar.png"}
                        """, MediaType.APPLICATION_JSON));

        OAuthProviderGateway gateway = new OAuthProviderGateway(properties, builder.build());
        ExternalOAuthIdentity identity = gateway.exchange(
                OAuthProvider.DINGTALK, "authorization-code", "https://xiaoyuai.cloud/callback");

        assertEquals("ding-open-id", identity.subject());
        assertEquals("ding-union-id", identity.unionId());
        assertEquals("测试用户", identity.displayName());
        server.verify();
    }
}
