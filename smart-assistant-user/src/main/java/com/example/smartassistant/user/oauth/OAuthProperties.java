package com.example.smartassistant.user.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "oauth")
public class OAuthProperties {
    private String publicBaseUrl = "http://localhost:8081";
    private String frontendBaseUrl = "http://localhost:5173";
    private Duration stateTtl = Duration.ofMinutes(5);
    private Duration ticketTtl = Duration.ofSeconds(60);
    private Provider wechat = new Provider();
    private Provider dingtalk = new Provider();
    private Provider feishu = new Provider();

    public Provider provider(OAuthProvider provider) {
        return switch (provider) {
            case WECHAT -> wechat;
            case DINGTALK -> dingtalk;
            case FEISHU -> feishu;
        };
    }

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String value) { this.publicBaseUrl = value; }
    public String getFrontendBaseUrl() { return frontendBaseUrl; }
    public void setFrontendBaseUrl(String value) { this.frontendBaseUrl = value; }
    public Duration getStateTtl() { return stateTtl; }
    public void setStateTtl(Duration value) { this.stateTtl = value; }
    public Duration getTicketTtl() { return ticketTtl; }
    public void setTicketTtl(Duration value) { this.ticketTtl = value; }
    public Provider getWechat() { return wechat; }
    public void setWechat(Provider value) { this.wechat = value; }
    public Provider getDingtalk() { return dingtalk; }
    public void setDingtalk(Provider value) { this.dingtalk = value; }
    public Provider getFeishu() { return feishu; }
    public void setFeishu(Provider value) { this.feishu = value; }

    public static class Provider {
        private boolean enabled;
        private String clientId = "";
        private String clientSecret = "";

        public boolean isEnabled() {
            return enabled && clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
        public void setEnabled(boolean value) { this.enabled = value; }
        public String getClientId() { return clientId; }
        public void setClientId(String value) { this.clientId = value; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String value) { this.clientSecret = value; }
    }
}
