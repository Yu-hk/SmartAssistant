package com.example.smartassistant.user.oauth;

import com.example.smartassistant.common.exception.ServiceException;
import com.example.smartassistant.user.model.dto.AuthResponse;
import com.example.smartassistant.user.service.ExternalAccountService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class OAuthLoginService {
    private static final String STATE_PREFIX = "oauth:state:";
    private static final String TICKET_PREFIX = "oauth:ticket:";

    private final OAuthProperties properties;
    private final OAuthProviderGateway providerGateway;
    private final ExternalAccountService accountService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public OAuthLoginService(OAuthProperties properties, OAuthProviderGateway providerGateway,
                             ExternalAccountService accountService, StringRedisTemplate redis,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.providerGateway = providerGateway;
        this.accountService = accountService;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public List<ProviderStatus> providers() {
        return Arrays.stream(OAuthProvider.values())
                .map(provider -> new ProviderStatus(provider.id(), provider.displayName(),
                        properties.provider(provider).isEnabled()))
                .toList();
    }

    public URI begin(OAuthProvider provider, String returnTo, boolean remember) {
        String state = createState(provider, returnTo, remember);
        return providerGateway.authorizationUri(provider, state, callbackUri(provider));
    }

    public DingTalkFrameConfig dingtalkFrameConfig(String returnTo, boolean remember) {
        OAuthProperties.Provider config = properties.provider(OAuthProvider.DINGTALK);
        if (!config.isEnabled() || config.getClientId() == null || config.getClientId().isBlank()) {
            throw new ServiceException(503, "OAUTH_NOT_CONFIGURED", "钉钉登录尚未配置");
        }
        return new DingTalkFrameConfig(
                config.getClientId(),
                callbackUri(OAuthProvider.DINGTALK),
                createState(OAuthProvider.DINGTALK, returnTo, remember),
                "openid",
                "code",
                "consent");
    }

    public URI callback(OAuthProvider provider, String code, String state, String ipAddress, String userAgent) {
        StatePayload payload = consume(STATE_PREFIX + state, StatePayload.class, "授权状态无效或已过期");
        if (!provider.id().equals(payload.provider())) throw badRequest("授权渠道与登录请求不匹配");
        ExternalOAuthIdentity external = providerGateway.exchange(provider, code, callbackUri(provider));
        AuthResponse auth = accountService.login(external, ipAddress, userAgent);
        String ticket = randomToken();
        put(TICKET_PREFIX + ticket, new TicketPayload(auth, payload.returnTo(), payload.remember()),
                properties.getTicketTtl());
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.getFrontendBaseUrl()) + "/login")
                .queryParam("oauth_ticket", ticket).build().encode().toUri();
    }

    public TicketPayload exchangeTicket(String ticket) {
        return consume(TICKET_PREFIX + ticket, TicketPayload.class, "登录票据无效或已使用");
    }

    public URI errorRedirect(String message) {
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.getFrontendBaseUrl()) + "/login")
                .queryParam("oauth_error", message == null ? "第三方登录失败" : message).build().encode().toUri();
    }

    private String callbackUri(OAuthProvider provider) {
        return trimTrailingSlash(properties.getPublicBaseUrl()) + "/api/auth/oauth/" + provider.id() + "/callback";
    }

    private String createState(OAuthProvider provider, String returnTo, boolean remember) {
        String state = randomToken();
        StatePayload payload = new StatePayload(provider.id(), safeReturnTo(returnTo), remember);
        put(STATE_PREFIX + state, payload, properties.getStateTtl());
        return state;
    }

    private String safeReturnTo(String value) {
        if (value == null || value.isBlank() || !value.startsWith("/") || value.startsWith("//")
                || value.contains("\\") || value.contains("\r") || value.contains("\n")) return "/";
        return value;
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void put(String key, Object value, java.time.Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存 OAuth 登录状态", exception);
        }
    }

    private <T> T consume(String key, Class<T> type, String message) {
        if (key.endsWith("null") || key.endsWith(":")) throw badRequest(message);
        String json = redis.opsForValue().getAndDelete(key);
        if (json == null) throw badRequest(message);
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw badRequest(message);
        }
    }

    private ServiceException badRequest(String message) {
        return new ServiceException(400, "OAUTH_INVALID_REQUEST", message);
    }

    public record ProviderStatus(String id, String name, boolean enabled) {}
    public record DingTalkFrameConfig(String clientId, String redirectUri, String state,
                                      String scope, String responseType, String prompt) {}
    public record StatePayload(String provider, String returnTo, boolean remember) {}
    public record TicketPayload(AuthResponse auth, String returnTo, boolean remember) {}
}
