/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.util;

import java.util.regex.Pattern;

/** Normalizes transport-level whitespace noise without decoding executable markup. */
public final class UserQuestionNormalizer {

    private static final Pattern HTML_SPACE_ENTITY = Pattern.compile(
            "(?i)(?:&#x(?:20|a0|1680|200[0-9a]|202f|205f|3000);"
                    + "|&#(?:32|160|5760|819[2-9]|820[0-2]|8239|8287|12288);"
                    + "|&(?:nbsp|ensp|emsp|thinsp);)");

    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");

    private UserQuestionNormalizer() {
    }

    /**
     * Converts encoded/unicode whitespace to a normal space and collapses repeated spacing.
     * Other HTML entities are intentionally preserved so input normalization cannot turn
     * encoded markup into active prompt content.
     */
    public static String normalize(String question) {
        if (question == null || question.isBlank()) {
            return question == null ? null : "";
        }
        String decodedSpacing = HTML_SPACE_ENTITY.matcher(question).replaceAll(" ");
        return WHITESPACE.matcher(decodedSpacing).replaceAll(" ").trim();
    }
}
