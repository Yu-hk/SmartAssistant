package com.example.smartassistant.consumer.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@SpringBootTest
@ActiveProfiles("test")
public class DataGifToolTest {

    @Autowired
    private DataGifTool dataGifTool;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void generateUserGrowthGif() throws Exception {
        // 先初始化用户表数据
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS users (" +
            "id BIGSERIAL PRIMARY KEY, " +
            "username VARCHAR(100), " +
            "created_at TIMESTAMP DEFAULT NOW())");

        // 插入 30 天模拟数据（如果为空）
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        if (count == 0) {
            String[] dates = {"2026-04-06","2026-04-07","2026-04-08","2026-04-09","2026-04-10",
                "2026-04-11","2026-04-12","2026-04-13","2026-04-14","2026-04-15",
                "2026-04-16","2026-04-17","2026-04-18","2026-04-19","2026-04-20",
                "2026-04-21","2026-04-22","2026-04-23","2026-04-27"};
            int[] values = {3,5,8,6,5,6,7,3,5,6,9,3,5,2,8,5,3,14,1};
            for (int i = 0; i < dates.length; i++) {
                for (int j = 0; j < values[i]; j++) {
                    jdbcTemplate.update(
                        "INSERT INTO users (username, created_at) VALUES (?, ?)",
                        "test_user_" + dates[i] + "_" + j,
                        java.sql.Date.valueOf(java.time.LocalDate.parse(dates[i]))
                    );
                }
            }
        }

        // 查询数据
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT DATE(created_at) as date, COUNT(*) as value " +
            "FROM users GROUP BY DATE(created_at) ORDER BY date"
        );

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(",");
            json.append("{\"date\":\"").append(rows.get(i).get("date"))
                .append("\",\"value\":").append(rows.get(i).get("value")).append("}");
        }
        json.append("]");

        System.out.println("数据点: " + rows.size());
        System.out.println("JSON: " + json);

        // 调用工具
        String result = dataGifTool.generateTrendGif(
            "近30天用户增长趋势",
            "日期",
            "新增用户数",
            json.toString(),
            "blue"
        );

        assert result != null && result.startsWith("data:image/gif;base64,") 
            : "GIF 生成失败";

        String base64 = result.replace("data:image/gif;base64,", "");
        byte[] gifData = Base64.getDecoder().decode(base64);
        Files.write(Path.of("target/test-user-growth.gif"), gifData);

        System.out.println("✅ GIF 生成成功！");
        System.out.println("   文件: target/test-user-growth.gif");
        System.out.println("   大小: " + (gifData.length / 1024) + " KB");
        System.out.println("   帧数: " + rows.size());
    }
}
