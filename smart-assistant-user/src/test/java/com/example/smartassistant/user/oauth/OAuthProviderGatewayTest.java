package com.example.smartassistant.user.oauth;

import com.example.smartassistant.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class OAuthProviderGatewayTest {

    @Test
    void buildsFeishuQrAuthorizationUriWithOAuthParametersAndNoSecret() {
        OAuthProviderGateway gateway = feishuGateway();
        String redirectUri = "https://xiaoyuai.cloud/api/auth/oauth/feishu/callback";

        URI uri = gateway.authorizationUri(OAuthProvider.FEISHU, "server-generated-state", redirectUri);

        assertEquals("https", uri.getScheme());
        assertEquals("passport.feishu.cn", uri.getHost());
        assertEquals("/suite/passport/oauth/authorize", uri.getPath());
        assertEquals(Map.of("client_id", "cli_feishu-test", "response_type", "code",
                "redirect_uri", redirectUri, "state", "server-generated-state"), decodedQuery(uri));
        assertFalse(uri.toString().contains("feishu-test-secret"));
        assertFalse(uri.toString().contains("app_id="));
    }

    @Test
    void encodesFeishuAuthorizationParametersWithoutLosingNestedQueryOrState() {
        OAuthProviderGateway gateway = feishuGateway();
        String state = "state+ with/中文?&=value#fragment";
        String redirectUri = "https://example.com/回调?returnTo=%2Forders%3Fpage%3D1&label=a+b c#done";

        URI uri = gateway.authorizationUri(OAuthProvider.FEISHU, state, redirectUri);

        assertEquals(Map.of("client_id", "cli_feishu-test", "response_type", "code",
                "redirect_uri", redirectUri, "state", state), decodedQuery(uri));
        assertFalse(uri.getRawQuery().contains(" "));
        assertFalse(uri.getRawQuery().contains("+"));
        assertNull(uri.getFragment());
    }

    @Test
    void rejectsFeishuAuthorizationWhenProviderIsNotConfigured() {
        OAuthProviderGateway gateway = new OAuthProviderGateway(new OAuthProperties(), RestClient.create());

        assertThrows(ServiceException.class, () -> gateway.authorizationUri(
                OAuthProvider.FEISHU, "state", "https://example.com/callback"));
    }

    private OAuthProviderGateway feishuGateway() {
        OAuthProperties properties = new OAuthProperties();
        properties.getFeishu().setEnabled(true);
        properties.getFeishu().setClientId("cli_feishu-test");
        properties.getFeishu().setClientSecret("feishu-test-secret");
        return new OAuthProviderGateway(properties, RestClient.create());
    }

    private Map<String, String> decodedQuery(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(parameter -> URLDecoder.decode(parameter[0], StandardCharsets.UTF_8),
                        parameter -> URLDecoder.decode(parameter[1], StandardCharsets.UTF_8)));
    }

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
