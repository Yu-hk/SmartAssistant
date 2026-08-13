package com.example.smartassistant.toolregistry.general;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/** Registers the General tool catalog with the central Tool Registry. */
@Component
public class GeneralToolCatalogRegistrar {

    private final ToolRegistry localRegistry;

    public GeneralToolCatalogRegistrar(ToolRegistry localRegistry) {
        this.localRegistry = localRegistry;
    }

    @PostConstruct
    public void register() {
        List<ToolDefinition> definitions = List.of(
                read("calculate", "数学表达式计算", "calculation"),
                read("convertTemperature", "温度单位换算", "unit-conversion"),
                read("convertLength", "长度单位换算", "unit-conversion"),
                read("convertWeight", "重量单位换算", "unit-conversion"),
                read("getHotNews", "网络热点榜单查询", "web-search"),
                read("webSearch", "联网搜索具体信息", "web-search"),
                read("convertCurrency", "实时货币汇率换算", "currency"),
                read("queryCorrections", "查询历史纠错记录", "memory"),
                ToolDefinition.write("executeScript", "执行受限计算脚本", ToolRiskLevel.MEDIUM)
                        .toBuilder().toolTier(ToolTier.SHARED)
                        .tags(new String[]{"GENERAL", "SANDBOXED"}).build(),
                read("queryWeather", "实时天气与天气预报", "weather"),
                read("analyzeImage", "分析图片内容", "image-analysis"),
                ToolDefinition.write("generateImage", "根据文本生成图片", ToolRiskLevel.MEDIUM)
                        .toBuilder().toolTier(ToolTier.SHARED)
                        .tags(new String[]{"GENERAL", "IMAGE"}).build()
        );
        localRegistry.registerAll(definitions);
    }

    private ToolDefinition read(String name, String description, String capability) {
        return ToolDefinition.read(name, description, new String[]{"GENERAL", "READ_ONLY"})
                .toBuilder().toolTier(ToolTier.SHARED)
                .functionalCapabilities(List.of(capability)).build();
    }
}
