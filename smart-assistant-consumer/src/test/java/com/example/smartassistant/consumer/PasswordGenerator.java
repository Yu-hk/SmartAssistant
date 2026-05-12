/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordGenerator {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String password = "123456";
        
        // 生成多个哈希值供选择
        System.out.println("=== BCrypt Password Hash Generator ===");
        System.out.println("Original password: " + password);
        System.out.println();
        
        for (int i = 1; i <= 3; i++) {
            String encoded = encoder.encode(password);
            System.out.println("Hash #" + i + ": " + encoded);
            
            // 验证生成的哈希
            boolean matches = encoder.matches(password, encoded);
            System.out.println("  Verification: " + (matches ? "✓ PASS" : "✗ FAIL"));
            System.out.println();
        }
        
        System.out.println("=== SQL Update Statement ===");
        String hash = encoder.encode(password);
        System.out.println("UPDATE users SET password = '" + hash + "' WHERE username = 'admin';");
    }
}
