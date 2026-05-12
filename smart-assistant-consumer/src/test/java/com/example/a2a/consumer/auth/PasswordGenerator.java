/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordGenerator {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String password = "123456";
        String encoded = encoder.encode(password);
        System.out.println("原始密码: " + password);
        System.out.println("BCrypt 哈希: " + encoded);
        System.out.println("\nSQL 更新语句:");
        System.out.println("UPDATE users SET password = '" + encoded + "' WHERE username='admin';");
    }
}
