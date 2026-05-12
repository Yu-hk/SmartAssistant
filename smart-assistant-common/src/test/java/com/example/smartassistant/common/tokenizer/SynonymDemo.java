/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tokenizer;

import java.util.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 同义词匹配效果演示
 */
public class SynonymDemo {

    public static void main(String[] args) {
        ChineseTokenizer tokenizer = new ChineseTokenizer();
        tokenizer.init();

        System.out.println("=".repeat(60));
        System.out.println("HanLP Portable 词性标注 + 同义词扩展 效果演示");
        System.out.println("=".repeat(60));

        // 1. HanLP 词性标注
        System.out.println("\n【1. 词性标注效果】");
        System.out.println("-".repeat(40));

        String[] texts = {
            "我想吃川菜",
            "带娃去海边玩沙",
            "找个便宜又好吃的餐厅"
        };

        for (String text : texts) {
            System.out.println("\n输入: \"" + text + "\"");
            System.out.print("词性标注: ");
            tokenizer.posTag(text).forEach(tw ->
                System.out.print(tw.getWord() + "/" + tw.getPos() + " ")
            );
        }

        // 2. 同义词扩展
        System.out.println("\n\n【2. 同义词扩展】");
        System.out.println("-".repeat(40));

        String[] words = {"散步", "溜达", "美食", "辣"};
        for (String word : words) {
            Set<String> expanded = tokenizer.expandToStandardForm(word);
            System.out.println(word + " → " + expanded);
        }

        // 3. 同义词匹配对比
        System.out.println("\n\n【3. 同义词匹配对比】");
        System.out.println("-".repeat(40));

        Set<String> keywords = Set.of("散步", "辣", "好吃");

        String[][] testCases = {
            {"去公园散步", "启用"},
            {"去公园溜达", "启用"},
            {"去公园溜达", "禁用"},
            {"想吃辣的", "启用"},
            {"这家店很香辣", "启用"},
            {"这家店很香辣", "禁用"}
        };

        System.out.printf("%-20s %-8s %-8s%n", "文本", "同义词", "是否匹配");
        System.out.println("-".repeat(40));

        for (String[] tc : testCases) {
            String text = tc[0];
            boolean enableSynonym = tc[1].equals("启用");
            boolean matched = tokenizer.containsAnyKeyword(text, keywords, enableSynonym);
            System.out.printf("%-20s %-8s %-8s%n",
                text.length() > 18 ? text.substring(0, 16) + ".." : text,
                tc[1],
                matched ? "✅ 匹配" : "❌ 未匹配");
        }

        // 4. 意图识别
        System.out.println("\n\n【4. 基于词性的意图识别】");
        System.out.println("-".repeat(40));

        Map<String, ChineseTokenizer.IntentPattern> patterns = new HashMap<>();
        patterns.put("FOOD_SEARCH", ChineseTokenizer.IntentPattern.of("吃", "美食", "餐厅")
                .withPos("v", "n"));
        patterns.put("WEATHER_QUERY", ChineseTokenizer.IntentPattern.of("天气")
                .withPos("n"));
        patterns.put("FAMILY_TRIP", ChineseTokenizer.IntentPattern.of("娃", "孩子", "小孩"));

        String[] intentTexts = {
            "我想吃川菜",
            "今天天气怎么样",
            "带娃去海边玩"
        };

        for (String text : intentTexts) {
            String intent = tokenizer.recognizeIntentByPos(text, patterns);
            System.out.println("\"" + text + "\" → " + (intent != null ? intent : "未识别"));
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("演示完成！");
        System.out.println("=".repeat(60));
    }
}
