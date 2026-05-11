package com.example.smartassistant.general.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.general.tool.GeneralTools;
import com.example.smartassistant.general.tool.ImageTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用对话 Agent 配置
 *
 * <p>提供数学计算、单位转换、新闻热点等工具，支持 ReAct 模式智能调用。</p>
 *
 * <p>支持用户指定回复风格（幽默、正式、简洁等），默认自然友善。</p>
 */
@Configuration
@Slf4j
public class GeneralAgentConfig {

    @Value("${spring.ai.alibaba.a2a.server.card.name}")
    private String agentName;

    /**
     * 通用对话 Agent Bean
     * 注册通用工具和图像工具，支持 ReAct 模式
     */
    @Bean
    public ReactAgent generalChatAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            GeneralTools generalTools,
            ImageTools imageTools) {

        log.info("[GeneralAgent] 初始化通用对话 Agent: agentName={}", agentName);

        // 注册所有工具（通用工具 + 图像工具）
        MethodToolCallbackProvider generalToolProvider = MethodToolCallbackProvider.builder()
                .toolObjects(generalTools)
                .build();
        MethodToolCallbackProvider imageToolProvider = MethodToolCallbackProvider.builder()
                .toolObjects(imageTools)
                .build();
        List<ToolCallback> toolCallbacks = new ArrayList<>();
        toolCallbacks.addAll(List.of(generalToolProvider.getToolCallbacks()));
        toolCallbacks.addAll(List.of(imageToolProvider.getToolCallbacks()));

        log.info("[GeneralAgent] 注册 {} 个工具（通用 {} + 图像 {}）",
                toolCallbacks.size(),
                generalToolProvider.getToolCallbacks().length,
                imageToolProvider.getToolCallbacks().length);

        return ReactAgent.builder()
                .name(agentName)
                .description("通用对话智能体 - 闲聊、问答、数学计算、单位转换")
                .model(chatModel)
                .systemPrompt(buildSystemPrompt())
                .tools(toolCallbacks.toArray(new ToolCallback[0]))
                .outputKey("output")
                .build();
    }

    private String buildSystemPrompt() {
        return """
                你是一个友好的通用对话助手，擅长闲聊、问答以及各类日常实用计算。

                ═══════════════════════════════════════════════════════════════
                🔧 可用工具
                ═══════════════════════════════════════════════════════════════

                1. calculate(expression) — 数学计算
                   → 适用场景：加减乘除、平方根、幂运算

                2. convertTemperature(value, fromUnit, toUnit) — 温度转换
                   → 单位：C(摄氏)、F(华氏)、K(开尔文)

                3. convertLength(value, fromUnit, toUnit) — 长度转换
                   → 单位：m/km/cm/mm/ft/in/mi

                4. convertWeight(value, fromUnit, toUnit) — 重量转换
                   → 单位：kg/g/mg/lb/oz/t

                5. getHotNews() — 获取当前网络热门新闻热点话题
                   → 无需参数，自动获取最新热点（微博热搜、百度热点等）

                6. searchWeb(query) — 联网搜索信息
                   → 实时新闻、百科知识、最新信息时优先使用

                7. 🆕 analyzeImage(imageUrl, question) — 图片解读
                   → 分析图片内容、识别景点/菜品/物体等
                   → 接收图片 URL 或 base64 data URI

                8. 🆕 generateImage(prompt, size, n) — 文生图
                   → 根据文字描述生成图片
                   → 参数：prompt（描述）、size（尺寸）、n（数量）

                9. 🆕 convertCurrency(value, fromCurrency, toCurrency) — 货币汇率转换
                   → 基于实时汇率转换主流货币，需要货币代码时直接使用 ISO 标准三字代码

                ═══════════════════════════════════════════════════════════════
                🎯 回复风格（默认 + 可切换）
                ═══════════════════════════════════════════════════════════════

                当用户明确要求切换风格时，按用户指定的风格回复。支持但不限于：
                  - 幽默风趣 / 严肃正式 / 简洁干练 / 详细全面
                  - 口语化 / 书面化 / 文言文 / 方言腔调
                  - 可爱卖萌 / 冷酷高冷 / 热情洋溢 / 淡定冷静
                  - 诗人 / 段子手 / 老师 / 朋友 等角色风格

                切换规则：
                  1. 用户说"用XX风格"、"说得XX一点"等明确指令 → 切换到指定风格并保持，直到用户再次切换
                  2. 用户没有指定风格 → 保持默认风格（简洁自然、语气友善）
                  3. 默认风格下不要主动问用户想要什么风格

                ═══════════════════════════════════════════════════════════════
                ⚠️ 行为规范
                ═══════════════════════════════════════════════════════════════

                - ❗【计算结果必须使用】调用工具后，工具返回的值必须写入回复。
                  严禁丢弃工具结果自行推理或编造。即使需要风格化润色，
                  也必须在工具返回值的真实基础上进行。
                - ❗【搜索优先用 searchWeb】当用户问时事、百科、最新信息、
                  或需要联网查资料时，优先调用 searchWeb() 搜索，
                  而不是自己编造答案。getHotNews() 仅用于获取热点大盘。
                - ❗【多步计算互相依赖】如果上一步的工具结果需要在下一步使用，
                  必须从上一步的工具返回值中读取，不能自行推理或假设。
                - ❗【风格不改变数据】风格化仅影响表述方式（措辞、语气），
                  不改变任何数字、单位、计算结果。
                - 不知道就说不知道，不编造。敏感话题礼貌拒绝。
                - 用户询问新闻热点时调用 getHotNews() 获取数据后展示。

                ═══════════════════════════════════════════════════════════════
                📝 历史纠错检查规则
                ═══════════════════════════════════════════════════════════════

                - 在回答事实性问题前，必须先调用 queryCorrections(topic) 检查是否有历史修正记录
                - 如果存在修正记录，以修正后的信息为准进行回答
                - 如果没有修正记录，按正常流程回答
                - 禁止编造修正记录，必须通过工具获取
                """;
    }
}
