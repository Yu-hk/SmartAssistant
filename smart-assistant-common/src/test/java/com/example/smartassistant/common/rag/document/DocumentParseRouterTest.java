/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParseRouterTest {

    @Test
    void legacyDocIsRejectedWithActionableMessage() {
        DocumentParseRouter router = new DocumentParseRouter();

        assertFalse(router.supports("legacy.doc"));
        DocumentParseException error = assertThrows(
                DocumentParseException.class, () -> router.parse("legacy.doc"));
        assertTrue(error.getMessage().contains(".docx"));
    }

    @Test
    void docxRemainsSupported() {
        assertTrue(new DocumentParseRouter().supports("current.docx"));
    }
}
