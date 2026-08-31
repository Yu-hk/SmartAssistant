package com.example.smartassistant.user.oauth;

import java.util.Locale;

public enum OAuthProvider {
    WECHAT("wechat", "微信"),
    DINGTALK("dingtalk", "钉钉"),
    FEISHU("feishu", "飞书");

    private final String id;
    private final String displayName;

    OAuthProvider(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static OAuthProvider from(String value) {
        for (OAuthProvider provider : values()) {
            if (provider.id.equals(value.toLowerCase(Locale.ROOT))) return provider;
        }
        throw new IllegalArgumentException("不支持的第三方登录渠道");
    }
}
