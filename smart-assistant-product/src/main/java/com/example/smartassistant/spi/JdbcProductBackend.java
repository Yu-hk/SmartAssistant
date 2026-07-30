package com.example.smartassistant.spi;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL-backed product and inventory implementation used in deployed environments.
 */
public class JdbcProductBackend implements ProductBackend {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductBackend.class);
    private static final Pattern PRODUCT_CODE =
            Pattern.compile("(?i)(?<![A-Z0-9_])E2E-PROD-\\d+(?![A-Z0-9_-])");

    private final JdbcTemplate jdbcTemplate;

    public JdbcProductBackend(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String queryProductInfo(String productCode) {
        Map<String, Object> product = findProduct(productCode);
        if (product == null) {
            return notFound(productCode);
        }
        String code = text(product.get("product_code"));
        StringBuilder result = new StringBuilder()
                .append("商品编码：").append(code).append('\n')
                .append("商品名称：").append(text(product.get("product_name"))).append('\n')
                .append("价格：¥").append(money(product.get("price"))).append('\n')
                .append("库存状态：").append(text(product.get("stock"))).append('\n')
                .append("规格：").append(text(product.get("spec"))).append('\n')
                .append("颜色：").append(text(product.get("color")));
        appendInventory(result, code);
        return result.toString();
    }

    @Override
    public String checkStock(String productCode) {
        Map<String, Object> product = findProduct(productCode);
        if (product == null) {
            return notFound(productCode);
        }
        String code = text(product.get("product_code"));
        StringBuilder result = new StringBuilder()
                .append(text(product.get("product_name")))
                .append("（").append(code).append("）");
        appendInventory(result, code);
        return result.toString();
    }

    @Override
    public String getPrice(String productCode) {
        Map<String, Object> product = findProduct(productCode);
        if (product == null) {
            return notFound(productCode);
        }
        return text(product.get("product_name")) + "（" + text(product.get("product_code"))
                + "）当前价格为 ¥" + money(product.get("price")) + "。";
    }

    @Override
    public String searchProduct(String keyword) {
        String code = extractCode(keyword);
        List<Map<String, Object>> products;
        if (code != null) {
            products = jdbcTemplate.queryForList(
                    "SELECT product_code, product_name, price, stock, spec, color "
                            + "FROM products WHERE UPPER(product_code) = UPPER(?) LIMIT 20",
                    code);
        } else {
            String term = keyword == null ? "" : keyword.trim();
            products = jdbcTemplate.queryForList(
                    "SELECT product_code, product_name, price, stock, spec, color "
                            + "FROM products WHERE ? = '' OR product_name ILIKE ? OR product_code ILIKE ? "
                            + "ORDER BY product_code LIMIT 200",
                    term, "%" + term + "%", "%" + term + "%");
        }
        if (products.isEmpty()) {
            return "未找到匹配的商品";
        }
        StringBuilder result = new StringBuilder("搜索结果：\n");
        for (Map<String, Object> product : products) {
            result.append("- ")
                    .append(text(product.get("product_code"))).append(" | ")
                    .append(text(product.get("product_name"))).append(" | ¥")
                    .append(money(product.get("price"))).append(" | ")
                    .append(text(product.get("stock"))).append('\n');
        }
        return result.toString().trim();
    }

    private Map<String, Object> findProduct(String input) {
        String code = extractCode(input);
        List<Map<String, Object>> rows;
        if (code != null) {
            rows = jdbcTemplate.queryForList(
                    "SELECT product_code, product_name, price, stock, spec, color "
                            + "FROM products WHERE UPPER(product_code) = UPPER(?) LIMIT 1",
                    code);
        } else {
            String term = input == null ? "" : input.trim();
            rows = jdbcTemplate.queryForList(
                    "SELECT product_code, product_name, price, stock, spec, color "
                            + "FROM products WHERE product_name ILIKE ? OR product_code ILIKE ? "
                            + "ORDER BY product_code LIMIT 1",
                    "%" + term + "%", "%" + term + "%");
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void appendInventory(StringBuilder result, String productCode) {
        List<Map<String, Object>> inventory = jdbcTemplate.queryForList(
                "SELECT DISTINCT ON (warehouse) warehouse, on_hand, reserved, quality_hold, available "
                        + "FROM inventory_snapshots WHERE product_code = ? "
                        + "ORDER BY warehouse, created_at DESC",
                productCode);
        if (inventory.isEmpty()) {
            result.append("\n仓库库存：暂无库存快照");
            return;
        }
        result.append("\n仓库库存：");
        for (Map<String, Object> row : inventory) {
            result.append("\n- ").append(text(row.get("warehouse")))
                    .append("：在库 ").append(text(row.get("on_hand")))
                    .append("，已预留 ").append(text(row.get("reserved")))
                    .append("，质检冻结 ").append(text(row.get("quality_hold")))
                    .append("，可售 ").append(text(row.get("available")));
        }
    }

    private static String extractCode(String input) {
        if (input == null) return null;
        Matcher matcher = PRODUCT_CODE.matcher(input);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }

    private static String money(Object value) {
        if (value == null) return "";
        try {
            return new BigDecimal(value.toString()).setScale(2).toPlainString();
        } catch (NumberFormatException ignored) {
            return value.toString();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String notFound(String input) {
        log.warn("[JdbcProduct] 未找到商品: {}", input);
        return ToolResult.error(AgentErrorCode.PRODUCT_NOT_FOUND,
                "未找到商品：" + input, "请确认商品编码或名称是否正确");
    }
}
