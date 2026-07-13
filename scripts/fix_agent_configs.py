import os

base = "D:/workspace/SmartAssistant"

files_config = [
    {
        "path": f"{base}/smart-assistant-order/src/main/java/com/example/smartassistant/config/OrderAgentConfig.java",
        "tool_beans": ["OrderTools", "OrderMemoryTool", "OrderAnalyticsTool", "OrderKnowledgeTool", "TextToSqlTool", "CouponTools"],
        "tool_imports": [
            "import com.example.smartassistant.order.tool.CouponTools;",
            "import com.example.smartassistant.order.tool.OrderAnalyticsTool;",
            "import com.example.smartassistant.order.tool.OrderKnowledgeTool;",
            "import com.example.smartassistant.order.tool.OrderMemoryTool;",
            "import com.example.smartassistant.order.tool.OrderTools;",
            "import com.example.smartassistant.order.tool.TextToSqlTool;",
        ],
        "tag": "ORDER",
        "old_log_line": "\t\tlog.info(\"[OrderAgent] 注册 {} 个工具（从 ToolProvider 获取）\", toolList.size());",
        "new_log_line": "\t\tlog.info(\"[OrderAgent] 加载 {} 个本模块工具\", toolList.size());",
        "old_refresh_method": "startToolRefresh",
    },
    {
        "path": f"{base}/smart-assistant-product/src/main/java/com/example/smartassistant/config/ProductAgentConfig.java",
        "tool_beans": ["ProductTools", "ProductMemoryTool", "KnowledgeQueryTool"],
        "tool_imports": [
            "import com.example.smartassistant.product.tool.KnowledgeQueryTool;",
            "import com.example.smartassistant.product.tool.ProductMemoryTool;",
            "import com.example.smartassistant.product.tool.ProductTools;",
        ],
        "tag": "PRODUCT",
        "old_log_line": "        log.info(\"[ProductAgent] 注册 {} 个工具（从 ToolProvider 获取）\", toolList.size());",
        "new_log_line": "        log.info(\"[ProductAgent] 加载 {} 个本模块工具\", toolList.size());",
        "old_refresh_method": "startToolRefresh",
    },
    {
        "path": f"{base}/smart-assistant-general/src/main/java/com/example/smartassistant/general/config/GeneralAgentConfig.java",
        "tool_beans": ["WeatherTool", "ImageTools", "GeneralTools", "GeneralMemoryTool"],
        "tool_imports": [
            "import com.example.smartassistant.general.tool.GeneralMemoryTool;",
            "import com.example.smartassistant.general.tool.GeneralTools;",
            "import com.example.smartassistant.general.tool.ImageTools;",
            "import com.example.smartassistant.general.tool.WeatherTool;",
        ],
        "tag": "GENERAL",
        "old_log_line": '        log.info("[GeneralAgent] ToolProvider 发现 {} 个工具（tag={}）", toolCallbacks.size(), toolTag);',
        "new_log_line": '        log.info("[GeneralAgent] 加载 {} 个本模块工具", moduleToolCallbacks.length);',
        "old_refresh_method": "startToolRefresh",
    },
]

for cfg in files_config:
    fp = cfg["path"]
    module_name = fp.split("/smart-assistant-")[1].split("/")[0]

    with open(fp, 'r', encoding='utf-8') as f:
        content = f.read()

    original = content
    changes = []

    # 1. Remove ToolProvider import
    content = content.replace('import com.example.smartassistant.common.tool.provider.ToolProvider;\n', '')
    changes.append("remove ToolProvider import")

    # 2. Add tool imports (after DiscoverToolsTool import line)
    discover_line = 'import com.example.smartassistant.common.gateway.tool.meta.DiscoverToolsTool;'
    tool_import_block = '\n' + '\n'.join(cfg["tool_imports"])
    content = content.replace(discover_line, discover_line + tool_import_block)
    changes.append("add tool imports")

    # 3. Add java.util.Arrays import
    if 'import java.util.Arrays;' not in content:
        content = content.replace('import java.util.ArrayList;', 'import java.util.Arrays;\nimport java.util.ArrayList;')
        changes.append("add Arrays import")

    # 4. Remove @Value toolTag and refreshIntervalSec fields
    lines = content.split('\n')
    new_lines = []
    skip_next = False
    for i, line in enumerate(lines):
        if skip_next:
            skip_next = False
            continue
        if '@Value("${agent.tool-tag:' in line:
            skip_next = True
            continue
        if '@Value("${tool-registry.refresh-interval-seconds:60}")' in line:
            skip_next = True
            continue
        new_lines.append(line)
    content = '\n'.join(new_lines)
    changes.append("remove toolTag/refreshIntervalSec fields")

    # 5. Remove ToolRegistryProperties field block (@Autowired + field)
    content = content.replace(
        '    @Autowired\n    private ToolRegistryProperties toolRegistryProperties;\n', '')
    changes.append("remove ToolRegistryProperties field")

    # 6. Remove ToolRegistryProperties import
    content = content.replace(
        'import com.example.smartassistant.common.tool.client.ToolRegistryProperties;\n', '')
    changes.append("remove ToolRegistryProperties import")

    # 7. Remove ToolProvider constructor param
    content = content.replace('            ToolProvider toolProvider,\n', '')
    changes.append("remove ToolProvider param")

    # 8. Add tool bean params - insert before the AiChatService param
    tool_params = '            ' + ',\n            '.join(cfg["tool_beans']) + ',\n            AiChatService aiChatService,\n            '
    content = content.replace(
        '            AiChatService aiChatService,\n            ',
        tool_params)
    changes.append("add tool bean params")

    # 9. Replace tool loading block
    if cfg["tag"] == "GENERAL":
        # General uses toolCallbacks variable name
        old_load = '        List<ToolCallback> toolCallbacks = toolProvider.getToolCallbacks(toolTag);'
    else:
        old_load = '        List<ToolCallback> toolList = toolProvider.getToolCallbacks(toolTag);'
    
    tool_list_var = 'toolList' if cfg["tag"] != "GENERAL" else 'toolCallbacks'
    new_load = (
        '        // 从本模块 @Component 工具 Bean 直接扫描加载\n'
        '        ToolCallback[] moduleToolCallbacks = MethodToolCallbackProvider.builder()\n'
        '                .toolObjects(' + ', '.join(cfg["tool_beans"]) + ')\n'
        '                .build()\n'
        '                .getToolCallbacks();\n'
        '        List<ToolCallback> ' + tool_list_var + ' = new ArrayList<>(Arrays.asList(moduleToolCallbacks));'
    )
    content = content.replace(old_load, new_load)
    changes.append("replace tool loading")

    # 10. Update log message
    content = content.replace(cfg["old_log_line"], cfg["new_log_line"])
    changes.append("update log message")

    # 11. Remove startToolRefresh() call block
    # Pattern: if (refreshIntervalSec > 0) { startToolRefresh(toolProvider, agent); }
    content = content.replace(
        '        // 启动定时刷新（通过 ToolProvider）\n'
        '        if (refreshIntervalSec > 0) {\n'
        '            startToolRefresh(toolProvider, agent);\n'
        '        }',
        '')
    content = content.replace(
        '        // 启动定时刷新（仅当配置了刷新间隔且 > 0）\n'
        '        if (refreshIntervalSec > 0) {\n'
        '            startToolRefresh(toolProvider, agent);\n'
        '        }',
        '')
    content = content.replace(
        '        // 启动定时刷新\n'
        '        if (refreshIntervalSec > 0) {\n'
        '            startToolRefresh(toolProvider, agent);\n'
        '        }',
        '')
    changes.append("remove refresh call")

    # 12. Remove startToolRefresh() method - find from comment to closing brace
    # Find the start marker
    if cfg["tag"] == "ORDER":
        start_marker = '    /**\n     * 启动定时工具刷新任务。\n     * <p>\n     * 每 {@code tool-registry.refresh-interval-seconds} 秒从 ToolProvider 重新拉取工具列表，'
    elif cfg["tag"] == "GENERAL":
        start_marker = '    /**\n     * 启动定时工具刷新任务（ToolProvider 模式）。'
    else:
        start_marker = '    /**\n     * 启动定时工具刷新任务。\n     * 每 {@code tool-registry.refresh-interval-seconds} 秒从 ToolProvider 重新拉取工具列表，'
    
    idx = content.find(start_marker)
    if idx >= 0:
        # Find the closing brace of the method (the next `}` after `refreshIntervalSec` at the same indent level)
        end_idx = content.find('\n    }\n\n    /**\n     *', idx)
        if end_idx >= 0:
            content = content[:idx] + content[end_idx + 2:]
        else:
            # Try end of file pattern
            end_idx = content.find('\n    }\n}', idx)
            if end_idx >= 0:
                content = content[:idx] + content[end_idx + 2:]
            else:
                end_idx = content.find('\n    }\n\n    @Bean', idx)
                if end_idx >= 0:
                    content = content[:idx] + content[end_idx + 2:]
        changes.append("remove startToolRefresh method")
    else:
        changes.append("(startToolRefresh method not found)")

    # 13. Remove unused concurrent imports
    for imp in ['import java.util.concurrent.Executors;\n', 'import java.util.concurrent.ScheduledExecutorService;\n', 'import java.util.concurrent.TimeUnit;\n']:
        if imp in content:
            content = content.replace(imp, '')
            changes.append("remove " + imp.split('.')[-1].split(';')[0] + " import")

    # Clean up blank lines
    while '\n\n\n' in content:
        content = content.replace('\n\n\n', '\n\n')

    if content != original:
        with open(fp, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"OK {module_name}: {', '.join(changes)}")
    else:
        print(f"SAME {module_name}: no changes made")

print("\nDone!")
