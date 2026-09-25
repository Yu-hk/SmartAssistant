package com.example.smartassistant.common.agent.protocol;

/** Shared minimum rule for an order delivery address and its clarification form. */
public final class OrderShippingAddressPolicy {
    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 200;
    public static final String HINT = "请填写区县、街道和门牌等详细地址，至少 6 字";

    private OrderShippingAddressPolicy() { }

    public static boolean usable(String address) {
        if (address == null) return false;
        String value = address.strip();
        return value.length() >= MIN_LENGTH && value.length() <= MAX_LENGTH
                && value.chars().noneMatch(Character::isISOControl);
    }
}
