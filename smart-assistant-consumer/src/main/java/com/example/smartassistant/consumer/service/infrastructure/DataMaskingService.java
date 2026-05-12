/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.infrastructure;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 数据脱敏服务
 * 用于保护用户隐私，在日志和监控中脱敏敏感信息
 */
@Service
public class DataMaskingService {

    // 手机号正则
    private static final Pattern PHONE_PATTERN = Pattern.compile("(1[3-9]\\d)(\\d{4})(\\d{4})");
    
    // 身份证正则
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(\\d{6})(\\d{8})(\\d{4})");
    
    // 邮箱正则
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(\\w{2})\\w+@(\\w+)\\.([a-z]{2,})");
    
    /**
     * 脱敏 StructuredPrompt（用于日志输出）
     */
    public String maskStructuredPrompt(String jsonPrompt) {
        if (jsonPrompt == null || jsonPrompt.isEmpty()) {
            return "";
        }
        
        String masked = jsonPrompt;
        
        // 脱敏 userId
        masked = masked.replaceAll("\"userId\":\\d+", "\"userId\":***");
        
        // 脱敏 sessionId
        masked = masked.replaceAll("\"sessionId\":\"[^\"]+\"", "\"sessionId\":\"***\"");
        
        // 脱敏 requestId
        masked = masked.replaceAll("\"requestId\":\"[^\"]+\"", "\"requestId\":\"***\"");
        
        // 脱敏 extra 字段中的敏感信息
        masked = masked.replaceAll("\"extra\":\"[^\"]*phone[^\"]*\"", "\"extra\":\"***\"");
        
        return masked;
    }
    
    /**
     * 脱敏手机号
     */
    public String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        return PHONE_PATTERN.matcher(phone).replaceAll("$1****$3");
    }
    
    /**
     * 脱敏身份证号
     */
    public String maskIdCard(String idCard) {
        if (idCard == null || idCard.isEmpty()) {
            return idCard;
        }
        return ID_CARD_PATTERN.matcher(idCard).replaceAll("$1********$3");
    }
    
    /**
     * 脱敏邮箱
     */
    public String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        return EMAIL_PATTERN.matcher(email).replaceAll("$1***@$2.$3");
    }
    
    /**
     * 脱敏用户名（保留首尾字符）
     */
    public String maskUsername(String username) {
        if (username == null || username.isEmpty()) {
            return username;
        }
        if (username.length() <= 2) {
            return "**";
        }
        return username.charAt(0) + "***" + username.charAt(username.length() - 1);
    }
    
    /**
     * 脱敏对话内容（检测并脱敏敏感信息）
     */
    public String maskConversationContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        
        String masked = content;
        
        // 脱敏手机号
        masked = PHONE_PATTERN.matcher(masked).replaceAll("$1****$3");
        
        // 脱敏身份证号
        masked = ID_CARD_PATTERN.matcher(masked).replaceAll("$1********$3");
        
        // 脱敏邮箱
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("$1***@$2.$3");
        
        return masked;
    }
}
