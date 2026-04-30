package com.example.smartassistant.service.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.config.McpTableWhitelistConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP Agent 服务 - Travel Service
 * 使用 ReactAgent 实现自然语言到 SQL 的自动转换
 */
@Service
@Slf4j
public class McpAgentService {
    
    private final ChatModel chatModel;
    private final JdbcTemplate jdbcTemplate;
    private final McpTableWhitelistConfig whitelistConfig;  // ⭐ 注入白名单配置
    private ReactAgent mcpAgent;
    
    public McpAgentService(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            JdbcTemplate jdbcTemplate,
            McpTableWhitelistConfig whitelistConfig) {
        this.chatModel = chatModel;
        this.jdbcTemplate = jdbcTemplate;
        this.whitelistConfig = whitelistConfig;
    }
    
    /**
     * ⭐ 初始化 ReactAgent，只注册 executeQuery 工具
     * Agent 通过自然语言理解，自动生成并执行 SQL
     */
    @PostConstruct
    public void init() {
        log.info("[McpAgentService] 开始创建 ReactAgent...");
        
        try {
            // ⭐ 关键解决：手动创建非代理的工具对象，避免 CGLIB 代理问题
            // ⭐ 传入白名单配置，实现应用层表访问控制
            DatabaseQueryTools tools = new DatabaseQueryTools(jdbcTemplate, whitelistConfig);
            
            // ⭐ 使用 MethodToolCallbackProvider 扫描 @Tool 注解的方法
            MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
            
            ToolCallback[] toolCallbacks = provider.getToolCallbacks();
            log.info("[McpAgentService] ✅ 发现 {} 个 MCP 工具", toolCallbacks.length);
            
            // ⭐ 使用 tools() 方法注册工具回调
            this.mcpAgent = ReactAgent.builder()
                .name("travel-database-query-agent")
                .model(chatModel)
                .tools(toolCallbacks)
                .instruction("""
                    你是一个专业的旅行数据库查询助手，通过生成并执行 SQL 来获取真实数据。
                    
                    ## 🎯 核心原则
                    1. **必须使用工具**：绝对不要编造数据，必须调用 executeQuery 获取真实数据
                    2. **基于事实回答**：只根据工具返回的数据回答，不要推测或假设
                    3. **简洁清晰**：回答控制在 50-100 字，直接给出关键信息
                    4. **安全第一**：只能生成 SELECT 语句，禁止任何写操作
                    
                    ## 🔧 工作流程
                    
                    ### 步骤 1：理解用户意图
                    - 分析问题类型：景点统计/天气查询/路线规划/附近推荐
                    - 确定需要的数据表和字段
                    - 识别地理位置、时间范围等条件
                    
                    ### 步骤 2：探索数据结构（如需要）
                    - 如果不确定表结构，先调用 getTableSchema()
                    - 了解可用的表和字段
                    
                    ### 步骤 3：生成 SQL
                    - 编写标准的 PostgreSQL SELECT 语句
                    - 合理使用 WHERE、GROUP BY、ORDER BY、LIMIT
                    - 对于大数据量，务必添加 LIMIT 限制
                    
                    ### 步骤 4：执行查询
                    - 调用 executeQuery(sql) 执行生成的 SQL
                    - 检查返回结果
                    
                    ### 步骤 5：组织回答
                    - 将原始数据转化为用户友好的表达
                    - 数字 → "共有 X 个..."
                    - 列表 → "前 N 个是：A, B, C"
                    - 表格 → 用文字描述关键信息
                    
                    ## 📊 常见查询模式
                    
                    ### 景点统计
                    ```sql
                    -- ✅ 景点总数（使用主键索引）
                    SELECT COUNT(*) FROM tourist_attractions;
                    
                    -- ✅ 按等级统计（利用 level 索引）
                    SELECT level, COUNT(*) as count
                    FROM tourist_attractions
                    GROUP BY level
                    ORDER BY count DESC;
                    
                    -- ✅ 按城市统计（利用 city 索引）
                    SELECT city, COUNT(*) as count
                    FROM tourist_attractions
                    GROUP BY city
                    ORDER BY count DESC
                    LIMIT 10;
                    ```
                    
                    ### 景点列表
                    ```sql
                    -- ✅ 热门景点（明确指定字段，添加 LIMIT）
                    SELECT name, city, level, ticket_price
                    FROM tourist_attractions
                    ORDER BY id
                    LIMIT 10;
                    
                    -- ✅ 指定城市的景点（利用 city 索引）
                    SELECT name, description, level, ticket_price
                    FROM tourist_attractions
                    WHERE city = '北京'
                    ORDER BY level DESC
                    LIMIT 5;
                    ```
                    
                    ### 价格区间查询
                    ```sql
                    -- ✅ 免费景点（利用 ticket_price 索引）
                    SELECT name, city, level
                    FROM tourist_attractions
                    WHERE ticket_price = 0
                    ORDER BY level DESC
                    LIMIT 10;
                    
                    -- ✅ 价格范围（使用 BETWEEN）
                    SELECT name, ticket_price, level
                    FROM tourist_attractions
                    WHERE ticket_price BETWEEN 50 AND 100
                    ORDER BY ticket_price ASC
                    LIMIT 10;
                    ```
                    
                    ### 高等级景点
                    ```sql
                    -- ✅ 5A 级景点（利用 level 索引）
                    SELECT name, city, level, ticket_price
                    FROM tourist_attractions
                    WHERE level = '5A'
                    ORDER BY id
                    LIMIT 10;
                    ```
                    
                    ### 多条件组合查询
                    ```sql
                    -- ✅ 北京的 4A/5A 景点（利用复合索引 idx_attractions_city_level）
                    SELECT name, level, ticket_price, province
                    FROM tourist_attractions
                    WHERE city = '北京' AND level IN ('4A', '5A')
                    ORDER BY level DESC
                    LIMIT 10;
                    ```
                    
                    ## ⚡ 性能优化最佳实践
                    
                    ### 1. 只选择需要的字段
                    - ❌ `SELECT * FROM tourist_attractions` （返回所有字段，浪费 I/O）
                    - ✅ `SELECT name, city, level, ticket_price FROM tourist_attractions` （只返回需要的字段）
                    
                    ### 2. 利用索引字段
                    - **tourist_attractions 表索引**:
                      * 单列索引: id (主键), city, level, province, ticket_price, created_at
                      * 复合索引: idx_attractions_city_level (city, level)
                    - ✅ 优先在 WHERE、ORDER BY 中使用这些字段
                    - ✅ 多条件查询时考虑复合索引
                    
                    ### 2. 添加合理的 LIMIT
                    - 列表查询必须加 LIMIT，避免返回过多数据
                    - 统计查询不需要 LIMIT
                    - 示例：`LIMIT 5`, `LIMIT 10`, `LIMIT 20`
                    
                    ### 3. 利用 WHERE 条件过滤
                    - ✅ 先过滤再排序：`WHERE city = '北京' ORDER BY rating DESC`
                    - ❌ 全表排序：`ORDER BY rating DESC` （无 WHERE 条件）
                    
                    ### 4. 避免不必要的计算
                    - ✅ 直接在 SQL 中聚合：`COUNT(*)`, `AVG(rating)`, `MAX(price)`
                    - ❌ 返回所有数据后在代码中计算
                    
                    ## 📋 可用工具
                    
                    ### executeQuery(sql)
                    - **用途**：执行自定义 SQL 查询（仅 SELECT）
                    - **参数**：sql - SQL 查询语句
                    - **限制**：
                      * 必须以 SELECT 开头
                      * 禁止 DROP/DELETE/UPDATE/INSERT/ALTER/CREATE
                    - **返回**：结果集数组
                    
                    ### getTableSchema()
                    - **用途**：获取所有表的元数据
                    - **参数**：无
                    - **返回**：表名、字段名、数据类型、是否可空、默认值
                    
                    ## 💡 最佳实践
                    
                    1. **先探索后查询**：不确定表结构时，先调用 getTableSchema()
                    2. **限制返回行数**：列表查询务必加 LIMIT，避免返回过多数据
                    3. **处理空结果**：如果返回 0 行，如实告知用户
                    4. **格式化输出**：将技术术语转化为用户能理解的语言
                    5. **错误恢复**：SQL 执行失败时，检查语法并重试
                    
                    ## ⚠️ 注意事项
                    
                    - ❌ 禁止猜测数据，必须调用工具
                    - ❌ 禁止执行写操作（INSERT/UPDATE/DELETE）
                    - ❌ 禁止执行 DDL 操作（CREATE/DROP/ALTER）
                    - ✅ 遇到不确定的表名或字段名，先查询元数据
                    - ✅ 对于复杂查询，可以分步执行
                    
                    ## 📝 回答示例
                    
                    - ✅ "当前系统中有 150 个景点。"
                    - ✅ "北京的热门景点有：故宫（4.8分）、长城（4.9分）、天坛（4.7分）。"
                    - ✅ "评分最高的 5 个景点平均价格为 120 元。"
                    - ❌ "根据我的分析..."（不要编造）
                    - ❌ "可能有大约..."（不要推测）
                    """)
                .build();
            
            log.info("[McpAgentService] ✅ ReactAgent 创建成功，已注册 MCP 工具");
            
        } catch (Exception e) {
            log.error("[McpAgentService] ❌ ReactAgent 创建失败: {}", e.getMessage(), e);
            this.mcpAgent = null;
        }
    }
    
    /**
     * ⭐ 内部工具类：避免 CGLIB 代理问题
     * 这个类不会被 Spring 管理，所以不会有代理问题
     * 只保留核心的 executeQuery 和 getTableSchema 工具
     */
    static class DatabaseQueryTools {
        
        private final JdbcTemplate jdbcTemplate;
        private final McpTableWhitelistConfig whitelistConfig;
        private static final String CURRENT_SERVICE = "travel"; // ⭐ 当前服务标识
        
        public DatabaseQueryTools(JdbcTemplate jdbcTemplate, McpTableWhitelistConfig whitelistConfig) {
            this.jdbcTemplate = jdbcTemplate;
            this.whitelistConfig = whitelistConfig;
        }
        
        @Tool(description = "执行只读 SQL 查询（仅支持 SELECT 语句）。这是核心工具，用于执行 Agent 生成的 SQL。")
        public java.util.List<java.util.Map<String, Object>> executeQuery(
            @ToolParam(description = "SQL 查询语句（必须是 SELECT 语句）", required = true) String sql
        ) {
            // ⭐ 记录 Agent 生成的 SQL
            log.info("[executeQuery] Agent 生成的 SQL: {}", sql);
            
            // ⭐ 安全检查：只允许 SELECT 语句
            String trimmedSql = sql.trim().toUpperCase();
            if (!trimmedSql.startsWith("SELECT")) {
                log.warn("[executeQuery] ❌ 拒绝非 SELECT 语句: {}", sql);
                throw new IllegalArgumentException("只允许执行 SELECT 查询");
            }
            
            // ⭐ 禁止危险操作：使用单词边界匹配，避免误判（如 INTERVAL 被误认为 CREATE）
            String[] dangerousKeywords = {"DROP ", "DROP\t", "DROP\n", "DELETE ", "DELETE\t", "DELETE\n", 
                                          "UPDATE ", "UPDATE\t", "UPDATE\n", 
                                          "INSERT ", "INSERT\t", "INSERT\n",
                                          "ALTER ", "ALTER\t", "ALTER\n",
                                          "CREATE ", "CREATE\t", "CREATE\n"};
            
            for (String keyword : dangerousKeywords) {
                if (trimmedSql.contains(keyword)) {
                    log.warn("[executeQuery] ❌ 拒绝危险操作");
                    log.warn("[executeQuery]    SQL: {}", sql);
                    log.warn("[executeQuery]    包含危险关键词: {}", keyword.trim());
                    throw new IllegalArgumentException("禁止执行危险操作");
                }
            }
            
            // ⭐ 应用层白名单验证：检查 SQL 中涉及的表是否在白名单中
            validateTableAccess(sql);
            
            try {
                java.util.List<java.util.Map<String, Object>> result = jdbcTemplate.queryForList(sql);
                log.info("[executeQuery] ✅ 查询成功，返回 {} 行", result.size());
                return result;
            } catch (Exception e) {
                log.error("[executeQuery] ❌ SQL 执行失败: {}", e.getMessage());
                throw new RuntimeException("SQL 执行失败: " + e.getMessage(), e);
            }
        }
        
        /**
         * ⭐ 验证 SQL 中的表是否在白名单中
         */
        private void validateTableAccess(String sql) {
            if (whitelistConfig == null) {
                log.warn("[executeQuery] ⚠️  白名单配置未加载，跳过表访问验证");
                return;
            }
            
            // 提取 SQL 中的表名（简单实现，生产环境建议使用 SQL 解析器）
            List<String> tablesInSql = extractTableNames(sql);
            
            for (String tableName : tablesInSql) {
                // 检查黑名单（明确禁止的表）
                List<String> forbiddenTables = whitelistConfig.getForbiddenTables(CURRENT_SERVICE);
                if (forbiddenTables.stream().anyMatch(t -> t.equalsIgnoreCase(tableName))) {
                    log.error("[executeQuery] ❌ 拒绝访问黑名单表: {}", tableName);
                    throw new SecurityException("无权访问表: " + tableName);
                }
                
                // 检查白名单（如果配置了白名单）
                List<String> allowedTables = whitelistConfig.getAllowedTables(CURRENT_SERVICE);
                if (!allowedTables.isEmpty()) {
                    boolean isAllowed = allowedTables.stream()
                        .anyMatch(t -> t.equalsIgnoreCase(tableName));
                    
                    if (!isAllowed) {
                        log.error("[executeQuery] ❌ 拒绝访问不在白名单中的表: {}", tableName);
                        log.error("[executeQuery]    当前服务 '{}' 的白名单: {}", CURRENT_SERVICE, allowedTables);
                        throw new SecurityException("无权访问表: " + tableName + "（不在白名单中）");
                    }
                }
                
                log.debug("[executeQuery] ✅ 表 '{}' 访问验证通过", tableName);
            }
        }
        
        /**
         * ⭐ 从 SQL 中提取表名（简化版）
         * 生产环境建议使用专业的 SQL 解析器如 JSqlParser
         */
        private List<String> extractTableNames(String sql) {
            List<String> tables = new java.util.ArrayList<>();
            
            // 匹配 FROM 子句
            java.util.regex.Pattern fromPattern = java.util.regex.Pattern.compile(
                "\\bFROM\\s+([a-zA-Z_][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher fromMatcher = fromPattern.matcher(sql);
            while (fromMatcher.find()) {
                String tableName = fromMatcher.group(1).toLowerCase();
                // 排除关键字和系统表
                if (isKeywordOrSystemTable(tableName)) {
                    tables.add(tableName);
                }
            }
            
            // 匹配 JOIN 子句
            java.util.regex.Pattern joinPattern = java.util.regex.Pattern.compile(
                "\\bJOIN\\s+([a-zA-Z_][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher joinMatcher = joinPattern.matcher(sql);
            while (joinMatcher.find()) {
                String tableName = joinMatcher.group(1).toLowerCase();
                if (isKeywordOrSystemTable(tableName)) {
                    tables.add(tableName);
                }
            }
            
            return tables.stream().distinct().collect(java.util.stream.Collectors.toList());
        }
        
        /**
         * 判断是否为关键字或系统表
         */
        private boolean isKeywordOrSystemTable(String name) {
            Set<String> keywords = Set.of(
                "select", "from", "where", "and", "or", "not", "in", "on",
                "join", "left", "right", "inner", "outer", "cross",
                "group", "order", "by", "having", "limit", "offset",
                "as", "distinct", "count", "sum", "avg", "min", "max",
                "information_schema", "pg_catalog"
            );
            return !keywords.contains(name.toLowerCase());
        }
        
        @Tool(description = "获取数据库中所有表的元数据信息。当不确定表结构时，先调用此工具。")
        public java.util.List<java.util.Map<String, Object>> getTableSchema() {
            String sql = """
                SELECT
                    table_name,
                    column_name,
                    data_type,
                    is_nullable,
                    column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                ORDER BY table_name, ordinal_position
                """;
            
            return jdbcTemplate.queryForList(sql);
        }
    }
    
    /**
     * 执行自然语言查询
     * ReactAgent 会自动执行 Think-Act-Observe 循环
     */
    public String query(String question) {
        if (mcpAgent == null) {
            throw new IllegalStateException("ReactAgent 未初始化，请检查 ToolCallbackProvider 配置");
        }
        
        log.info("[MCP Agent] 开始查询: {}", question);
        long startTime = System.currentTimeMillis();
        
        try {
            // ⭐ ReactAgent 自动执行工具调用循环
            AssistantMessage response = mcpAgent.call(question);
            String result = response != null ? response.getText() : null;
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("[MCP Agent] 查询完成 (耗时: {} ms): {}", duration, 
                result != null ? result.substring(0, Math.min(100, result.length())) : "null");
            
            return result;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[MCP Agent] 查询失败 (耗时: {} ms): {}", duration, e.getMessage(), e);
            throw new RuntimeException("MCP Agent 查询失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查 Agent 是否可用
     */
    public boolean isAvailable() {
        return mcpAgent != null;
    }
}
