package com.example.smartassistant.user.oauth;

import com.example.smartassistant.common.exception.ServiceException;
import com.example.smartassistant.user.model.dto.AuthResponse;
import com.example.smartassistant.user.service.ExternalAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthLoginServiceTest {
    @Mock OAuthProviderGateway gateway;
    @Mock ExternalAccountService accountService;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    private OAuthProperties properties;
    private OAuthLoginService service;

    @BeforeEach
    void setUp() {
        properties = new OAuthProperties();
        properties.setPublicBaseUrl("https://xiaoyuai.cloud");
        properties.setFrontendBaseUrl("https://xiaoyuai.cloud");
        properties.getDingtalk().setEnabled(true);
        properties.getDingtalk().setClientId("ding-test-client");
        properties.getDingtalk().setClientSecret("ding-test-secret");
        properties.getFeishu().setEnabled(true);
        properties.getFeishu().setClientId("feishu-test-client");
        properties.getFeishu().setClientSecret("feishu-test-secret");
        when(redis.opsForValue()).thenReturn(values);
        service = new OAuthLoginService(properties, gateway, accountService, redis, new ObjectMapper());
    }

    @Test
    void beginStoresStateAndRejectsOpenRedirectReturnPath() {
        when(gateway.authorizationUri(any(), anyString(), anyString()))
                .thenReturn(URI.create("https://provider.example/authorize"));

        URI result = service.begin(OAuthProvider.FEISHU, "//evil.example/path", true);

        assertEquals("https://provider.example/authorize", result.toString());
        verify(values).set(anyString(), org.mockito.ArgumentMatchers.contains("\"returnTo\":\"/\""),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
        verify(gateway).authorizationUri(org.mockito.ArgumentMatchers.eq(OAuthProvider.FEISHU),
                anyString(), org.mockito.ArgumentMatchers.eq("https://xiaoyuai.cloud/api/auth/oauth/feishu/callback"));
    }

    @Test
    void loginTicketCanOnlyBeConsumedOnce() throws Exception {
        AuthResponse auth = new AuthResponse("token", "refresh", 8L, "wx_user", null, "ROLE_USER");
        String json = new ObjectMapper().writeValueAsString(new OAuthLoginService.TicketPayload(auth, "/", true));
        when(values.getAndDelete("oauth:ticket:once")).thenReturn(json).thenReturn(null);

        assertEquals("token", service.exchangeTicket("once").auth().getToken());
        assertThrows(ServiceException.class, () -> service.exchangeTicket("once"));
    }

    @Test
    void dingtalkFrameConfigStoresStateWithoutExposingSecret() {
        OAuthLoginService.DingTalkFrameConfig config = service.dingtalkFrameConfig("//evil.example", false);

        assertEquals("ding-test-client", config.clientId());
        assertEquals("https://xiaoyuai.cloud/api/auth/oauth/dingtalk/callback", config.redirectUri());
        assertEquals("openid", config.scope());
        assertEquals("code", config.responseType());
        assertEquals("consent", config.prompt());
        verify(values).set(
                org.mockito.ArgumentMatchers.eq("oauth:state:" + config.state()),
                org.mockito.ArgumentMatchers.contains("\"returnTo\":\"/\""),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
    }

    @Test
    void feishuFrameConfigStoresStateAndReturnsTrustedAuthorizationUri() {
        when(gateway.authorizationUri(
                org.mockito.ArgumentMatchers.eq(OAuthProvider.FEISHU),
                anyString(),
                org.mockito.ArgumentMatchers.eq("https://xiaoyuai.cloud/api/auth/oauth/feishu/callback")))
                .thenAnswer(invocation -> URI.create(
                        "https://accounts.feishu.cn/open-apis/authen/v1/authorize?state="
                                + invocation.getArgument(1, String.class)));

        OAuthLoginService.FeishuFrameConfig config = service.feishuFrameConfig("//evil.example", false);

        assertEquals("https://accounts.feishu.cn/open-apis/authen/v1/authorize?state=" + config.state(),
                config.authorizationUri());
        verify(values).set(
                org.mockito.ArgumentMatchers.eq("oauth:state:" + config.state()),
                org.mockito.ArgumentMatchers.contains("\"returnTo\":\"/\""),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
    }
}
