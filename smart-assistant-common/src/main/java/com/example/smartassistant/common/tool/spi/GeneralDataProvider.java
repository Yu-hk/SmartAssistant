/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool.spi;

/**
 * General domain data provider — implemented by the general module.
 * <p>Tool code accesses general-purpose functionality like script execution through this interface.</p>
 */
public interface GeneralDataProvider {

    /**
     * Execute a script in a sandboxed environment.
     *
     * @param script the script content to execute
     * @return execution result with success status, output, and error details
     */
    ScriptResult executeScript(String script);

    /**
     * Result of a script execution.
     *
     * @param success   whether the script executed successfully
     * @param output    the script output (formatted text)
     * @param errorCode error code if execution failed
     * @param message   error description if execution failed
     * @param hint      user-facing hint on how to fix the issue
     */
    record ScriptResult(boolean success, String output, String errorCode, String message, String hint) {}
}
