package com.example.smartassistant.user.oauth;

import com.example.smartassistant.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Map;

@Component
public class OAuthProviderGateway {
    private final OAuthProperties properties;
    private final RestClient http;

    public OAuthProviderGateway(OAuthProperties properties,
                                @Qualifier("oauthRestClient") RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    public URI authorizationUri(OAuthProvider provider, String state, String redirectUri) {
        OAuthProperties.Provider config = requireEnabled(provider);
        return switch (provider) {
            case WECHAT -> UriComponentsBuilder.fromUriString("https://open.weixin.qq.com/connect/qrconnect")
                    .queryParam("appid", config.getClientId()).queryParam("redirect_uri", redirectUri)
                    .queryParam("response_type", "code").queryParam("scope", "snsapi_login")
                    .queryParam("state", state).fragment("wechat_redirect").build().encode().toUri();
            case DINGTALK -> UriComponentsBuilder.fromUriString("https://login.dingtalk.com/oauth2/auth")
                    .queryParam("redirect_uri", redirectUri).queryParam("response_type", "code")
                    .queryParam("client_id", config.getClientId()).queryParam("scope", "openid")
                    .queryParam("state", state).queryParam("prompt", "consent").build().encode().toUri();
            case FEISHU -> UriComponentsBuilder.fromUriString("https://accounts.feishu.cn/open-apis/authen/v1/authorize")
                    .queryParam("app_id", config.getClientId()).queryParam("redirect_uri", redirectUri)
                    .queryParam("state", state).build().encode().toUri();
        };
    }

    public ExternalOAuthIdentity exchange(OAuthProvider provider, String code, String redirectUri) {
        if (code == null || code.isBlank()) throw oauthFailure("授权码为空");
        OAuthProperties.Provider config = requireEnabled(provider);
        return switch (provider) {
            case WECHAT -> exchangeWechat(config, code);
            case DINGTALK -> exchangeDingTalk(config, code);
            case FEISHU -> exchangeFeishu(config, code, redirectUri);
        };
    }

    private ExternalOAuthIdentity exchangeWechat(OAuthProperties.Provider config, String code) {
        JsonNode token = http.get().uri(builder -> builder
                        .scheme("https").host("api.weixin.qq.com").path("/sns/oauth2/access_token")
                        .queryParam("appid", config.getClientId()).queryParam("secret", config.getClientSecret())
                        .queryParam("code", code).queryParam("grant_type", "authorization_code").build())
                .retrieve().body(JsonNode.class);
        ensureSuccess(token);
        String accessToken = required(token, "access_token");
        String openId = required(token, "openid");
        JsonNode user = http.get().uri(builder -> builder
                        .scheme("https").host("api.weixin.qq.com").path("/sns/userinfo")
                        .queryParam("access_token", accessToken).queryParam("openid", openId)
                        .queryParam("lang", "zh_CN").build())
                .retrieve().body(JsonNode.class);
        ensureSuccess(user);
        return new ExternalOAuthIdentity(OAuthProvider.WECHAT, openId, text(user, "unionid"),
                text(user, "nickname"), null, text(user, "headimgurl"));
    }

    private ExternalOAuthIdentity exchangeDingTalk(OAuthProperties.Provider config, String code) {
        JsonNode token = http.post().uri("https://api.dingtalk.com/v1.0/oauth2/userAccessToken")
                .body(Map.of("clientId", config.getClientId(), "clientSecret", config.getClientSecret(),
                        "code", code, "grantType", "authorization_code"))
                .retrieve().body(JsonNode.class);
        String accessToken = required(token, "accessToken");
        JsonNode user = http.get().uri("https://api.dingtalk.com/v1.0/contact/users/me")
                .header("x-acs-dingtalk-access-token", accessToken)
                .retrieve().body(JsonNode.class);
        return new ExternalOAuthIdentity(OAuthProvider.DINGTALK, required(user, "openId"),
                text(user, "unionId"), text(user, "nick"), text(user, "email"), text(user, "avatarUrl"));
    }

    private ExternalOAuthIdentity exchangeFeishu(OAuthProperties.Provider config, String code, String redirectUri) {
        JsonNode token = http.post().uri("https://open.feishu.cn/open-apis/authen/v2/oauth/token")
                .body(Map.of("grant_type", "authorization_code", "client_id", config.getClientId(),
                        "client_secret", config.getClientSecret(), "code", code, "redirect_uri", redirectUri))
                .retrieve().body(JsonNode.class);
        ensureSuccess(token);
        JsonNode tokenData = token != null && token.has("data") ? token.path("data") : token;
        String accessToken = required(tokenData, "access_token");
        JsonNode response = http.get().uri("https://open.feishu.cn/open-apis/authen/v1/user_info")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve().body(JsonNode.class);
        ensureSuccess(response);
        JsonNode user = response != null && response.has("data") ? response.path("data") : response;
        return new ExternalOAuthIdentity(OAuthProvider.FEISHU, required(user, "open_id"),
                text(user, "union_id"), text(user, "name"), text(user, "email"), text(user, "avatar_url"));
    }

    private OAuthProperties.Provider requireEnabled(OAuthProvider provider) {
        OAuthProperties.Provider config = properties.provider(provider);
        if (!config.isEnabled()) throw new ServiceException(503, "OAUTH_NOT_CONFIGURED", provider.displayName() + "登录尚未配置");
        return config;
    }

    private void ensureSuccess(JsonNode node) {
        if (node == null) throw oauthFailure("平台返回为空");
        int code = node.path("code").asInt(node.path("errcode").asInt(0));
        if (code != 0) throw oauthFailure(node.path("msg").asText(node.path("errmsg").asText("平台授权失败")));
    }

    private String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw oauthFailure("平台响应缺少 " + field);
        return value;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) return null;
        return node.path(field).asText(null);
    }

    private ServiceException oauthFailure(String detail) {
        return new ServiceException(502, "OAUTH_PROVIDER_ERROR", detail);
    }
}
