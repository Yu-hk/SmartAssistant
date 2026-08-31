package com.example.smartassistant.user.controller;

import com.example.smartassistant.common.response.ApiResponse;
import com.example.smartassistant.user.oauth.OAuthLoginService;
import com.example.smartassistant.user.oauth.OAuthProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/auth/oauth")
public class OAuthController {
    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);
    private final OAuthLoginService oauthLoginService;

    public OAuthController(OAuthLoginService oauthLoginService) {
        this.oauthLoginService = oauthLoginService;
    }

    @GetMapping("/providers")
    public ApiResponse<List<OAuthLoginService.ProviderStatus>> providers() {
        return ApiResponse.success(oauthLoginService.providers());
    }

    @GetMapping("/dingtalk/frame-config")
    public ApiResponse<OAuthLoginService.DingTalkFrameConfig> dingtalkFrameConfig(
            @RequestParam(defaultValue = "/") String returnTo,
            @RequestParam(defaultValue = "true") boolean remember) {
        return ApiResponse.success(oauthLoginService.dingtalkFrameConfig(returnTo, remember));
    }

    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable String provider,
                                          @RequestParam(defaultValue = "/") String returnTo,
                                          @RequestParam(defaultValue = "true") boolean remember) {
        URI location = oauthLoginService.begin(OAuthProvider.from(provider), returnTo, remember);
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String authCode,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(required = false, name = "error_description") String errorDescription,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestHeader(value = "X-Real-IP", required = false) String realIp,
            @RequestHeader(value = "User-Agent", defaultValue = "Unknown") String userAgent) {
        URI location;
        try {
            if (error != null) throw new IllegalArgumentException(errorDescription == null ? error : errorDescription);
            location = oauthLoginService.callback(OAuthProvider.from(provider), firstNonBlank(code, authCode), state,
                    resolveIp(forwardedFor, realIp), userAgent);
        } catch (Exception exception) {
            log.warn("OAuth callback failed: provider={}, reason={}", provider, exception.getMessage());
            location = oauthLoginService.errorRedirect("第三方登录失败，请重试");
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    @PostMapping("/exchange")
    public ApiResponse<OAuthLoginService.TicketPayload> exchange(@Valid @RequestBody TicketRequest request) {
        return ApiResponse.success(oauthLoginService.exchangeTicket(request.ticket()));
    }

    public record TicketRequest(@NotBlank String ticket) {}

    private String resolveIp(String forwardedFor, String realIp) {
        if (forwardedFor != null && !forwardedFor.isBlank()) return forwardedFor.split(",")[0].trim();
        return realIp == null || realIp.isBlank() ? "127.0.0.1" : realIp;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
