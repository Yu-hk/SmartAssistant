/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.spi.impl;

import com.example.smartassistant.common.tool.spi.GeneralDataProvider;
import com.example.smartassistant.general.sandbox.ScriptSandbox;
import org.springframework.stereotype.Component;

/**
 * Implementation of {@link GeneralDataProvider} in the general module.
 * <p>Delegates script execution to the {@link ScriptSandbox} for sandboxed evaluation.</p>
 */
@Component
public class GeneralDataProviderImpl implements GeneralDataProvider {

    private final ScriptSandbox scriptSandbox;

    public GeneralDataProviderImpl(ScriptSandbox scriptSandbox) {
        this.scriptSandbox = scriptSandbox;
    }

    @Override
    public ScriptResult executeScript(String script) {
        ScriptSandbox.SandboxResult result = scriptSandbox.execute(script);
        return new ScriptResult(
                result.success(),
                result.output(),
                result.errorCode() != null ? result.errorCode().name() : null,
                result.message(),
                result.hint()
        );
    }
}
