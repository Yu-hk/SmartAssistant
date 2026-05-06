package com.example.smartassistant.consumer.service.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.consumer.config.McpTableWhitelistConfig;
import com.example.smartassistant.consumer.service.cache.SqlQueryCache;
import com.example.smartassistant.consumer.service.monitoring.SqlPerformanceMonitor;
import com.example.smartassistant.consumer.service.monitoring.SqlReviewService;
import com.example.smartassistant.consumer.tool.DataGifTool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP Agent 服务
 * 使用 ReactAgent 实现自动工具调用循环
 */
@Service
@Slf4j
public class McpAgentService {
    
    private final ChatModel chatModel;
    private final JdbcTemplate jdbcTemplate;
    private final SqlPerformanceMonitor performanceMonitor;
    private final SqlQueryCache sqlQueryCache;  // ⭐ SQL 查询缓存
    private final SqlReviewService sqlReviewService;  // ⭐ SQL 审查服务
    private final McpTableWhitelistConfig whitelistConfig;  // ⭐ 注入白名单配置
    private final DataGifTool dataGifTool;  // ⭐ 注入 GIF 动图工具
    private ReactAgent mcpAgent;
    
    public McpAgentService(
            ChatModel chatModel,
            JdbcTemplate jdbcTemplate,
            SqlPerformanceMonitor performanceMonitor,
            SqlQueryCache sqlQueryCache,
            SqlReviewService sqlReviewService,  // ⭐ 注入审查服务
            McpTableWhitelistConfig whitelistConfig,
            DataGifTool dataGifTool) {  // ⭐ 注入 GIF 动图工具
        this.chatModel = chatModel;
        this.jdbcTemplate = jdbcTemplate;
        this.performanceMonitor = performanceMonitor;
        this.sqlQueryCache = sqlQueryCache;
        this.sqlReviewService = sqlReviewService;
        this.whitelistConfig = whitelistConfig;
        this.dataGifTool = dataGifTool;
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
            DatabaseQueryTools dbTools = new DatabaseQueryTools(
                jdbcTemplate, 
                performanceMonitor,
                sqlQueryCache,
                sqlReviewService,
                whitelistConfig
            );
            
            // ⭐ 注册数据库查询工具 + GIF 动图工具
            MethodToolCallbackProvider dbProvider = MethodToolCallbackProvider.builder()
                .toolObjects(dbTools)
                .build();
            MethodToolCallbackProvider gifProvider = MethodToolCallbackProvider.builder()
                .toolObjects(dataGifTool)
                .build();
            
            // 合并所有工具
            List<ToolCallback> allCallbacks = new java.util.ArrayList<>();
            java.util.Collections.addAll(allCallbacks, dbProvider.getToolCallbacks());
            java.util.Collections.addAll(allCallbacks, gifProvider.getToolCallbacks());
            
            log.info("[McpAgentService] ✅ 发现 {} 个工具 (DB: {}, GIF: {})",
                    allCallbacks.size(),
                    dbProvider.getToolCallbacks().length,
                    gifProvider.getToolCallbacks().length);
            
            // ⭐ 使用 tools() 方法注册工具回调
            this.mcpAgent = ReactAgent.builder()
                .name("database-query-agent")
                .model(chatModel)
                .tools(allCallbacks.toArray(new ToolCallback[0]))
                .systemPrompt("""
                    你是一个专业的数据库查询与数据可视化助手。可以查询数据库获取真实数据，
                    还能将时间序列数据生成动画趋势 GIF 进行可视化展示。
                    
                    ## 🎯 核心原则
                    1. **必须使用工具**：绝对不要编造数据，必须调用 executeQuery 获取真实数据
                    2. **基于事实回答**：只根据工具返回的数据回答，不要推测或假设
                    3. **简洁清晰**：回答控制在 50-100 字，直接给出关键信息
                    4. **安全第一**：只能生成 SELECT 语句，禁止任何写操作
                    
                    ## 🔧 工作流程
                    
                    == 步骤 1：理解用户意图
                    - 分析问题类型：统计/列表/趋势/对比/详情
                    - 确定需要的数据表和字段
                    - 识别时间范围、过滤条件等
                    
                    == 步骤 2：探索数据结构（如需要）
                    - 如果不确定表结构，先调用 getTableSchema()
                    - 了解可用的表和字段
                    
                    == 步骤 3：生成 SQL
                    - 编写标准的 PostgreSQL SELECT 语句
                    - 合理使用 WHERE、GROUP BY、ORDER BY、LIMIT
                    - 对于大数据量，务必添加 LIMIT 限制
                    
                    == 步骤 4：执行查询
                    - 调用 executeQuery(sql) 执行生成的 SQL
                    - 检查返回结果
                    
                    == 步骤 5：自主判断是否生成趋势动图
                    - **自动决策，无需用户要求** —— 当以下条件满足时，必须调用 generateTrendGif()：
                      * 查询结果包含「按时间维度」的数据（如按天/周/月统计），且数据点 ≥ 3 个
                      * 查询意图涉及：趋势分析、增长/下降、变化走势、波动、对比、分布演化
                    - 满足条件 → 调用 generateTrendGif 可视化
                    - 不满足条件 → 直接进入步骤 6

                    == 步骤 6：组织回答
                    - 将原始数据转化为用户友好的表达
                    - 数字 → "共有 X 个..."
                    - 列表 → "前 N 个是：A, B, C"
                    - 表格 → 用文字描述关键信息
                    - 生成了动图 → 回答中先说明 "这是趋势动图，请查看：" 再附带 Base64 数据
                    
                    ## 📊 SQL 语法模式（纯格式参考，不绑定具体表名）
                    
                    == 统计类
                    ```sql
                    -- 统计总数
                    SELECT COUNT(*) FROM [表名];
                    
                    -- 按条件统计
                    SELECT COUNT(*) FROM [表名] WHERE [条件];
                    
                    -- 分组统计
                    SELECT [分组字段], COUNT(*) FROM [表名]
                    GROUP BY [分组字段] ORDER BY count DESC;
                    ```
                    
                    == 列表类
                    ```sql
                    -- 最近 N 条记录
                    SELECT [字段1], [字段2] FROM [表名]
                    WHERE [条件] ORDER BY [时间字段] DESC LIMIT 10;
                    
                    -- 分页查询
                    SELECT [字段1], [字段2] FROM [表名]
                    ORDER BY [主键] LIMIT 10 OFFSET 0;
                    ```
                    
                    == 时间范围查询
                    ```sql
                    -- 最近 N 天
                    SELECT COUNT(*) FROM [表名]
                    WHERE [时间字段] >= NOW() - INTERVAL 'N days';
                    
                    -- 今天的数据
                    SELECT COUNT(*) FROM [表名]
                    WHERE [时间字段] >= CURRENT_DATE;
                    ```
                    
                    == 趋势分析（动图常用）
                    ```sql
                    -- 按天统计，适合 generateTrendGif 输入
                    SELECT DATE([时间字段]) as date, COUNT(*) as value
                    FROM [表名]
                    WHERE [时间字段] >= NOW() - INTERVAL 'N days'
                    GROUP BY DATE([时间字段])
                    ORDER BY date;
                    ```
                    
                    ## ⚡ SQL 编写建议
                    
                    == 1. 只选择需要的字段
                    - ❌ `SELECT * FROM [表名]` （浪费 I/O）
                    - ✅ `SELECT [字段1], [字段2] FROM [表名]` （明确指定字段）
                    
                    == 2. 使用 WHERE 条件缩小范围
                    - 添加时间范围、状态等过滤条件，避免全表扫描
                    - 利用索引字段（主键、外键、时间字段）
                    
                    == 3. 添加合理的 LIMIT
                    - 列表查询必须加 LIMIT
                    - 统计查询不需要 LIMIT
                    
                    == 4. 不确定表结构时
                    - 先调用 **getTableSchema()** 查询实际字段名
                    - 不要根据猜测写 SQL
                    
                    ## 📋 可用工具
                    
                    == executeQuery(sql)
                    - **用途**：执行自定义 SQL 查询（仅 SELECT）
                    - **参数**：sql - SQL 查询语句
                    - **限制**：
                      * 必须以 SELECT 开头
                      * 禁止 DROP/DELETE/UPDATE/INSERT/ALTER/CREATE
                    - **返回**：结果集数组
                    
                    == getTableSchema()
                    - **用途**：获取所有表的元数据
                    - **参数**：无
                    - **返回**：表名、字段名、数据类型、是否可空、默认值
                    
                    == generateTrendGif(title, xLabel, yLabel, dataJson, lineColor)
                    - **用途**：根据时间序列数据生成趋势动画 GIF
                    - **参数**：
                      * title - 图表标题，如"用户增长趋势"
                      * xLabel - X 轴标签，如"日期"
                      * yLabel - Y 轴标签，如"用户数"
                      * dataJson - JSON 格式数据，（数组，每个元素含 date 和 value 字段）
                      * lineColor - 线条颜色（可选），blue/red/green/orange/purple
                    - **工作流**：
                      1. 先调用 executeQuery 获取时间序列数据（必须有 date 和 value 字段）
                      2. 将结果构建为 dataJson 格式
                      3. 调用 generateTrendGif 生成动图（系统会自动在回答末尾附加动图）
                    - **无需用户明确要求动图**：只要查询结果含时间维度且数据点 ≥ 3 个，应主动调用
                    
                    ## 💡 最佳实践
                    
                    1. **先探索后查询**：不确定表结构时，先调用 getTableSchema()
                    2. **限制返回行数**：列表查询务必加 LIMIT，避免返回过多数据
                    3. **处理空结果**：如果返回 0 行，如实告知用户
                    4. **格式化输出**：将技术术语转化为用户能理解的语言
                    5. **错误恢复**：SQL 执行失败时，检查语法并重试
                    6. **主动判断动图**：查询结果含时间维度（按天/周/月统计）且数据点 ≥ 3 时，主动调用 generateTrendGif 展示趋势，无需用户要求
                    
                    ## ⚠️ 注意事项
                    
                    - ❌ 禁止猜测数据，必须调用工具
                    - ❌ 禁止执行写操作（INSERT/UPDATE/DELETE）
                    - ❌ 禁止执行 DDL 操作（CREATE/DROP/ALTER）
                    - ✅ 遇到不确定的表名或字段名，先查询元数据
                    - ✅ 对于复杂查询，可以分步执行
                    - ✅ **主动生成动图**：查询结果含时间维度的序列数据（数据点 ≥ 3），自动调用 generateTrendGif
                    - ❌ 不要只在用户说"动图"时才调用，趋势分析类问题应主动生成
                    
                    ## ⚠️ 回答格式（只说明格式，不能代替工具调用）
                    
                    - ✅ 统计结果：直接说数字，如 "当前共有 XXX 个用户。"
                    - ✅ 趋势图：调用 generateTrendGif 后，系统会自动追加动图数据
                    - ❌ 直接说出具体数字或内容，必须先调用工具获取
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
        private final SqlPerformanceMonitor performanceMonitor;
        private final SqlQueryCache sqlQueryCache;  // ⭐ SQL 查询缓存
        private final SqlReviewService sqlReviewService;  // ⭐ SQL 审查服务
        private final McpTableWhitelistConfig whitelistConfig;  // ⭐ 白名单配置
        private static final String CURRENT_SERVICE = "consumer"; // ⭐ 当前服务标识
        
        public DatabaseQueryTools(JdbcTemplate jdbcTemplate, 
                                  SqlPerformanceMonitor performanceMonitor,
                                  SqlQueryCache sqlQueryCache,
                                  SqlReviewService sqlReviewService,
                                  McpTableWhitelistConfig whitelistConfig) {
            this.jdbcTemplate = jdbcTemplate;
            this.performanceMonitor = performanceMonitor;
            this.sqlQueryCache = sqlQueryCache;
            this.sqlReviewService = sqlReviewService;
            this.whitelistConfig = whitelistConfig;
        }
        
        @Tool(description = "执行只读 SQL 查询（仅支持 SELECT 语句）。这是核心工具，用于执行 Agent 生成的 SQL。")
        public java.util.List<java.util.Map<String, Object>> executeQuery(
            @ToolParam(description = "SQL 查询语句（必须是 SELECT 语句）", required = true) String sql
        ) {
            // ⭐ 记录 Agent 生成的 SQL
            log.info("[executeQuery] Agent 生成的 SQL: {}", sql);
            
            // ⭐ SQL 审查
            if (sqlReviewService != null) {
                SqlReviewService.ReviewResult review = sqlReviewService.review(sql);
                if (!review.isPassed()) {
                    log.warn("[executeQuery] ❌ SQL 审查失败 (得分: {}):", review.getScore());
                    for (SqlReviewService.Issue issue : review.getIssues()) {
                        log.warn("  - [{}] {}: {}", issue.getSeverity(), issue.getMessage(), issue.getSuggestion());
                    }
                    // 严重问题直接拒绝
                    if (review.getIssues().stream().anyMatch(i -> i.getSeverity() == SqlReviewService.Severity.ERROR)) {
                        throw new IllegalArgumentException("SQL 审查失败，请修复错误后重试");
                    }
                } else {
                    log.info("[executeQuery] ✅ SQL 审查通过 (得分: {})", review.getScore());
                }
            }
            
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
            
            // ⭐ 检查缓存
            if (sqlQueryCache != null && sqlQueryCache.isCacheable(sql)) {
                String cacheKey = sqlQueryCache.generateCacheKey(sql);
                java.util.List<java.util.Map<String, Object>> cached = sqlQueryCache.get(cacheKey);
                
                if (cached != null) {
                    log.info("[executeQuery] ✅ 缓存命中，返回缓存结果");
                    return cached;
                }
                log.info("[executeQuery] ❌ 缓存未命中，执行查询");
            }
            
            long startTime = System.currentTimeMillis();
            try {
                List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
                long duration = System.currentTimeMillis() - startTime;
                
                log.info("[executeQuery] ✅ 查询成功，返回 {} 行 (耗时: {} ms)", result.size(), duration);
                
                // ⭐ 记录 SQL 质量指标
                if (performanceMonitor != null) {
                    performanceMonitor.recordAgentSqlMetrics(sql, duration, true, result.size());
                }
                
                // ⭐ 缓存结果
                if (sqlQueryCache != null && sqlQueryCache.isCacheable(sql)) {
                    String cacheKey = sqlQueryCache.generateCacheKey(sql);
                    sqlQueryCache.put(cacheKey, result);
                    log.info("[executeQuery] ✅ 结果已缓存");
                }
                
                return result;
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("[executeQuery] ❌ SQL 执行失败 (耗时: {} ms): {}", duration, e.getMessage());
                
                // ⭐ 记录失败的 SQL
                if (performanceMonitor != null) {
                    performanceMonitor.recordAgentSqlMetrics(sql, duration, false, 0);
                }
                
                throw new RuntimeException("SQL 执行失败: " + e.getMessage(), e);
            }
        }
        
        @Tool(description = "获取数据库中所有表的元数据信息（包括分区表结构）。当不确定表结构时，先调用此工具。")
        public java.util.List<java.util.Map<String, Object>> getTableSchema() {
            // ⭐ 查询主表和分区表的结构，排除分区子表
            String sql = """
                WITH base_tables AS (
                    -- 获取所有普通表、分区主表和视图（排除分区子表）
                    SELECT DISTINCT
                        c.relname AS table_name,
                        CASE
                            WHEN c.relkind = 'p' THEN 'PARTITIONED TABLE'
                            WHEN c.relkind = 'r' AND NOT EXISTS (
                                SELECT 1 FROM pg_inherits WHERE inhrelid = c.oid
                            ) THEN 'TABLE'
                            WHEN c.relkind = 'v' THEN 'VIEW'
                            ELSE NULL
                        END AS table_type,
                        obj_description(c.oid) AS description
                    FROM pg_class c
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE n.nspname = 'public'
                        AND c.relkind IN ('r', 'p', 'v')  -- 普通表、分区表、视图
                        AND c.relname NOT LIKE '%_backup%'
                        AND c.relname NOT LIKE '%_old%'
                        AND c.relname NOT LIKE 'chat_messages_20%'  -- 排除分区子表
                    ORDER BY c.relname
                ),
                table_columns AS (
                    -- 获取每个表的列信息
                    SELECT
                        bt.table_name,
                        bt.table_type,
                        cols.column_name,
                        cols.data_type,
                        cols.is_nullable,
                        cols.column_default,
                        cols.ordinal_position
                    FROM base_tables bt
                    LEFT JOIN information_schema.columns cols
                        ON cols.table_name = bt.table_name
                        AND cols.table_schema = 'public'
                    WHERE bt.table_type IS NOT NULL  -- 只保留有效的表
                    ORDER BY bt.table_name, cols.ordinal_position
                )
                SELECT
                    table_name,
                    table_type,
                    column_name,
                    data_type,
                    is_nullable,
                    column_default
                FROM table_columns
                ORDER BY table_name, ordinal_position;
                """;
                    
            return jdbcTemplate.queryForList(sql);
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
                if (forbiddenTables != null && forbiddenTables.stream().anyMatch(t -> t.equalsIgnoreCase(tableName))) {
                    log.error("[executeQuery] ❌ 拒绝访问黑名单表: {}", tableName);
                    throw new SecurityException("无权访问表: " + tableName);
                }
                
                // 检查白名单（如果配置了白名单）
                List<String> allowedTables = whitelistConfig.getAllowedTables(CURRENT_SERVICE);
                if (allowedTables != null && !allowedTables.isEmpty()) {
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
                if (!isKeywordOrSystemTable(tableName)) {
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
                if (!isKeywordOrSystemTable(tableName)) {
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
            return keywords.contains(name.toLowerCase());
        }
    }
    
    private static final Pattern GIF_CACHE_PATTERN = Pattern.compile("GIF_CACHE:([a-f0-9-]+)");

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
            
            // ⭐ 后处理：将 GIF_CACHE:xxx 替换为真实 Base64 data URI
            if (result != null) {
                result = resolveGifCache(result);
            }
            
            // ⭐ 兜底：如果 Agent 未输出 GIF_CACHE token，但有缓存数据，追加到末尾
            if (result != null && !result.contains("data:image/gif;base64,")) {
                result = appendCachedGif(result);
            }
            
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
    
    /**
     * ⭐ 后处理：将 Agent 返回文本中的 GIF_CACHE:xxx 替换为真实 Base64 data URI
     */
    private String resolveGifCache(String text) {
        if (text == null || text.isBlank()) return text;
        
        StringBuffer sb = new StringBuffer();
        Matcher matcher = GIF_CACHE_PATTERN.matcher(text);
        boolean found = false;
        
        while (matcher.find()) {
            found = true;
            String cacheKey = matcher.group(1);
            byte[] gifData = DataGifTool.getGifFromCache(cacheKey);
            
            if (gifData != null) {
                String base64 = Base64.getEncoder().encodeToString(gifData);
                String dataUri = "data:image/gif;base64," + base64;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(dataUri));
                log.info("[GIF Cache] 替换成功: cacheKey={}, size={} KB", cacheKey, gifData.length / 1024);
            } else {
                log.warn("[GIF Cache] 缓存未命中或已过期: cacheKey={}", cacheKey);
                matcher.appendReplacement(sb, "[GIF 数据已过期]");
            }
        }
        
        if (found) {
            matcher.appendTail(sb);
            log.info("[GIF Cache] 完成 {} 个 GIF 替换", 
                java.util.regex.Pattern.compile("GIF_CACHE:").split(text).length - 1);
            return sb.toString();
        }
        
        return text;
    }
    
    /**
     * ⭐ 兜底：当 Agent 未输出 GIF_CACHE token 时，从缓存中取最新 GIF 追加到回答末尾
     */
    private String appendCachedGif(String text) {
        if (text == null || text.isBlank()) return text;
        
        java.util.Map<String, byte[]> allEntries = DataGifTool.getAllCacheEntries();
        if (allEntries.isEmpty()) return text;
        
        String cacheKey = allEntries.keySet().iterator().next();
        byte[] gifData = DataGifTool.getGifFromCache(cacheKey);
        
        if (gifData != null) {
            String base64 = Base64.getEncoder().encodeToString(gifData);
            String dataUri = "data:image/gif;base64," + base64;
            log.info("[GIF Cache] 兜底追加 GIF: cacheKey={}, size={} KB", cacheKey, gifData.length / 1024);
            return text + "\n\n" + dataUri;
        }
        
        return text;
    }
}
