package com.example.smartassistant.consumer.service.monitoring;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SQL 审查服务
 * 自动检查 Agent 生成的 SQL 是否符合最佳实践
 */
@Service
@Slf4j
public class SqlReviewService {
    
    /**
     * SQL 审查结果
     */
    @Data
    public static class ReviewResult {
        private boolean passed;
        private List<Issue> issues = new ArrayList<>();
        private String sql;
        private int score; // 0-100
        
        public void addIssue(Severity severity, String message, String suggestion) {
            issues.add(new Issue(severity, message, suggestion));
            if (severity == Severity.ERROR) {
                passed = false;
            }
            // 根据严重程度扣分
            score -= severity.getPenalty();
        }
        
        public ReviewResult(String sql) {
            this.sql = sql;
            this.passed = true;
            this.score = 100;
        }
    }
    
    @Data
    public static class Issue {
        private Severity severity;
        private String message;
        private String suggestion;
        
        public Issue(Severity severity, String message, String suggestion) {
            this.severity = severity;
            this.message = message;
            this.suggestion = suggestion;
        }
    }
    
    public enum Severity {
        ERROR(30),      // 严重问题，必须修复
        WARNING(15),    // 警告，建议修复
        INFO(5);        // 提示，可选优化
        
        private final int penalty;
        
        Severity(int penalty) {
            this.penalty = penalty;
        }
        
        public int getPenalty() {
            return penalty;
        }
    }
    
    // 危险操作模式
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
        "\\b(DROP|DELETE|UPDATE|INSERT|ALTER|CREATE|TRUNCATE)\\b",
        Pattern.CASE_INSENSITIVE
    );
    
    // SELECT * 模式
    private static final Pattern SELECT_ALL_PATTERN = Pattern.compile(
        "\\bSELECT\\s+\\*",
        Pattern.CASE_INSENSITIVE
    );
    
    // LIKE '%...%' 模式（无法使用索引）
    private static final Pattern LIKE_WILDCARD_PATTERN = Pattern.compile(
        "LIKE\\s+'%[^']*%'",
        Pattern.CASE_INSENSITIVE
    );
    
    /**
     * 审查 SQL 语句
     * @param sql 要审查的 SQL
     * @return 审查结果
     */
    public ReviewResult review(String sql) {
        ReviewResult result = new ReviewResult(sql);
        
        if (sql == null || sql.trim().isEmpty()) {
            result.addIssue(Severity.ERROR, "SQL 不能为空", "提供有效的 SQL 语句");
            return result;
        }
        
        String trimmedSql = sql.trim();
        String upperSql = trimmedSql.toUpperCase();
        
        // 1. 安全检查：只允许 SELECT
        if (!upperSql.startsWith("SELECT")) {
            result.addIssue(Severity.ERROR, 
                "非 SELECT 语句", 
                "只允许执行 SELECT 查询，禁止写操作");
            return result;
        }
        
        // 2. 检查危险操作
        if (DANGEROUS_PATTERN.matcher(trimmedSql).find()) {
            result.addIssue(Severity.ERROR, 
                "包含危险操作关键词", 
                "移除 DROP/DELETE/UPDATE/INSERT/ALTER/CREATE/TRUNCATE 等关键词");
        }
        
        // 3. 检查 SELECT *
        if (SELECT_ALL_PATTERN.matcher(trimmedSql).find()) {
            result.addIssue(Severity.WARNING, 
                "使用 SELECT *", 
                "明确指定需要的字段，例如：SELECT id, name, email FROM users");
        }
        
        // 4. 检查是否缺少 LIMIT（列表查询）
        if (!upperSql.contains("COUNT(") && 
            !upperSql.contains("SUM(") && 
            !upperSql.contains("AVG(") &&
            !upperSql.contains("LIMIT")) {
            result.addIssue(Severity.WARNING, 
                "列表查询缺少 LIMIT", 
                "添加 LIMIT 限制返回行数，例如：LIMIT 10");
        }
        
        // 5. 检查 LIKE 通配符前缀（无法使用索引）
        if (LIKE_WILDCARD_PATTERN.matcher(trimmedSql).find()) {
            result.addIssue(Severity.WARNING, 
                "LIKE 使用前缀通配符", 
                "避免使用 LIKE '%keyword%'，考虑使用全文搜索或改为后匹配 LIKE 'keyword%'");
        }
        
        // 6. 检查是否有 WHERE 条件（大数据量表）
        if (!upperSql.contains("WHERE") && 
            !upperSql.contains("LIMIT 1") &&
            !upperSql.contains("LIMIT 0")) {
            result.addIssue(Severity.INFO, 
                "查询没有 WHERE 条件", 
                "考虑添加过滤条件以减少扫描数据量");
        }
        
        // 7. 检查 ORDER BY 是否有 LIMIT 配合
        if (upperSql.contains("ORDER BY") && !upperSql.contains("LIMIT")) {
            result.addIssue(Severity.INFO, 
                "ORDER BY 未配合 LIMIT", 
                "排序大量数据时建议添加 LIMIT，例如：ORDER BY created_at DESC LIMIT 10");
        }
        
        // 8. 检查是否使用了聚合函数但没有 GROUP BY
        if ((upperSql.contains("COUNT(") || upperSql.contains("SUM(") || 
             upperSql.contains("AVG(")) && 
            upperSql.contains("GROUP BY") == false &&
            upperSql.contains("FROM") == true) {
            
            // 提取 SELECT 和 FROM 之间的部分
            int selectIndex = upperSql.indexOf("SELECT") + 6;
            int fromIndex = upperSql.indexOf("FROM");
            
            if (fromIndex > selectIndex) {
                String selectPart = trimmedSql.substring(selectIndex, fromIndex).trim();
                
                // 如果 SELECT 部分既有聚合函数又有非聚合字段
                boolean hasAggregate = selectPart.matches(".*\\b(COUNT|SUM|AVG|MAX|MIN)\\b.*");
                boolean hasNonAggregate = selectPart.matches(".*,.*") && 
                                         !selectPart.matches(".*(COUNT|SUM|AVG|MAX|MIN).*");
                
                if (hasAggregate && hasNonAggregate) {
                    result.addIssue(Severity.WARNING, 
                        "聚合查询缺少 GROUP BY", 
                        "当 SELECT 中同时包含聚合函数和非聚合字段时，需要添加 GROUP BY");
                }
            }
        }
        
        // 9. 检查 JOIN 是否有 ON 条件
        if (upperSql.contains("JOIN") && !upperSql.contains("ON")) {
            result.addIssue(Severity.ERROR, 
                "JOIN 缺少 ON 条件", 
                "为 JOIN 添加 ON 条件，避免笛卡尔积");
        }
        
        // 10. 检查子查询性能
        if (upperSql.contains("IN (SELECT") || upperSql.contains("EXISTS (SELECT")) {
            result.addIssue(Severity.INFO, 
                "使用子查询", 
                "考虑使用 JOIN 替代子查询以提升性能");
        }
        
        log.info("[SQL Review] 审查完成 - 得分: {}, 问题数: {}, SQL: {}", 
            result.getScore(), 
            result.getIssues().size(),
            truncate(sql));
        
        return result;
    }
    
    /**
     * 批量审查 SQL
     */
    public List<ReviewResult> reviewBatch(List<String> sqlList) {
        List<ReviewResult> results = new ArrayList<>();
        for (String sql : sqlList) {
            results.add(review(sql));
        }
        return results;
    }
    
    /**
     * 获取审查统计
     */
    public ReviewStatistics getStatistics(List<ReviewResult> results) {
        ReviewStatistics stats = new ReviewStatistics();
        stats.totalCount = results.size();
        
        for (ReviewResult result : results) {
            if (result.isPassed()) {
                stats.passedCount++;
            } else {
                stats.failedCount++;
            }
            
            stats.averageScore += result.getScore();
            
            for (Issue issue : result.getIssues()) {
                switch (issue.getSeverity()) {
                    case ERROR -> stats.errorCount++;
                    case WARNING -> stats.warningCount++;
                    case INFO -> stats.infoCount++;
                }
            }
        }
        
        if (stats.totalCount > 0) {
            stats.averageScore /= stats.totalCount;
            stats.passRate = (double) stats.passedCount / stats.totalCount * 100;
        }
        
        return stats;
    }
    
    @Data
    public static class ReviewStatistics {
        private int totalCount;
        private int passedCount;
        private int failedCount;
        private double averageScore;
        private double passRate;
        private int errorCount;
        private int warningCount;
        private int infoCount;
    }
    
    private String truncate(String sql) {
        if (sql == null) return "null";
        return sql.length() > 100 ? sql.substring(0, 100) + "..." : sql;
    }
}
