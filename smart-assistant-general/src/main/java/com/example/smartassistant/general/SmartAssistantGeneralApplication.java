/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general;

import com.example.smartassistant.common.agent.ReActProfileAutoConfiguration;
import com.example.smartassistant.common.correction.CorrectionService;
import com.example.smartassistant.common.exception.GlobalExceptionHandler;
import com.example.smartassistant.common.memory.AgentMemoryService;
import com.example.smartassistant.common.rag.advisor.AdvisorChainAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@Import({
    AgentMemoryService.class,
    AdvisorChainAutoConfiguration.class,
    CorrectionService.class,
    GlobalExceptionHandler.class,
    ReActProfileAutoConfiguration.class
})
public class SmartAssistantGeneralApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartAssistantGeneralApplication.class, args);
    }
}
