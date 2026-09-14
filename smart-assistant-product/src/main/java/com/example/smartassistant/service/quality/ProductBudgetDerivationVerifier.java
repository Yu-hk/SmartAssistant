package com.example.smartassistant.service.quality;

import com.example.smartassistant.common.rag.eval.HallucinationDetector.HallucinationClaim;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validate budget arithmetic without adding derived numbers to generic product evidence. */
public final class ProductBudgetDerivationVerifier {
    private static final String NUMBER = "([+-]?" + ProductMoneySyntax.DECIMAL + ")" + ProductMoneySyntax.END;
    private static final Pattern EQUATION = Pattern.compile(
            ProductMoneySyntax.START + NUMBER + "\\s*元?\\s*[-−]\\s*" + NUMBER
                    + "\\s*元?\\s*[=＝]\\s*" + NUMBER + "\\s*元?");
    private static final Pattern PRICE_AND_GAP = Pattern.compile(
            "(?:价格|售价)\\s*[:：]?\\s*[¥￥]?" + NUMBER + "\\s*元?\\s*[,，;；]\\s*"
                    + "(?:价差|差额|预算(?:剩余|余额|结余|余量|还剩))\\s*[:：为]?\\s*"
                    + NUMBER + "\\s*元");
    private static final Pattern BUDGET_REMAINDER = Pattern.compile(
            "预算(?:剩余|余额|结余|余量|还剩)\\s*[:：为]?\\s*" + NUMBER + "\\s*元");
    private static final Pattern DIFFERENCE_IN_PROSE = Pattern.compile(
            ProductMoneySyntax.START + NUMBER + "\\s*元\\s*(?:与|和)\\s*" + NUMBER
                    + "\\s*元\\s*(?:之间)?\\s*(?:的)?\\s*(?:差额|价差)\\s*[:：为是]?\\s*"
                    + NUMBER + "\\s*元");
    private static final Pattern PRICE_PREFIX = Pattern.compile(
            "(?:价格|售价|市场价|参考价)\\s*[:：]?\\s*[¥￥]?\\s*$");

    private ProductBudgetDerivationVerifier() { }

    public record Result(String answer, List<HallucinationClaim> errors) { }

    public static Result verify(String answer, Set<BigDecimal> budgets, Set<BigDecimal> prices) {
        List<HallucinationClaim> errors = new ArrayList<>();
        String canonical = ProductMoneySyntax.normalize(answer);
        String normalized = replace(canonical, EQUATION, match -> {
            if (PRICE_PREFIX.matcher(canonical.substring(Math.max(0, match.start() - 32), match.start())).find()) {
                return false;
            }
            BigDecimal budget = number(match.group(1));
            BigDecimal price = number(match.group(2));
            if (!budgets.contains(budget)) return false;
            if (!prices.contains(price)) {
                errors.add(error("预算计算价格无依据", match.group()));
                return false;
            }
            return verifyDifference(budget, price, number(match.group(3)), match.group(), errors);
        });
        String prose = normalized;
        normalized = replace(prose, DIFFERENCE_IN_PROSE, match -> {
            // Do not erase an unsupported product price just because it happens to equal
            // the user's budget. The first operand must be a budget, not a claimed price.
            if (PRICE_PREFIX.matcher(prose.substring(Math.max(0, match.start() - 32), match.start())).find()) {
                return false;
            }
            BigDecimal budget = number(match.group(1));
            if (!budgets.contains(budget)) return false;
            BigDecimal price = number(match.group(2));
            if (!prices.contains(price)) {
                errors.add(error("预算计算价格无依据", match.group()));
                return false;
            }
            return verifyDifference(budget, price, number(match.group(3)), match.group(), errors);
        });
        // A bare "gap" is ambiguous with multiple budgets. Require a unique user budget and
        // an explicit, verified price in the same phrase, not an arbitrary number elsewhere.
        if (budgets.size() == 1) {
            BigDecimal budget = budgets.iterator().next();
            normalized = replace(normalized, PRICE_AND_GAP, match -> {
                BigDecimal price = number(match.group(1));
                if (!prices.contains(price)) {
                    errors.add(error("预算计算价格无依据", match.group()));
                    return false;
                }
                return verifyDifference(budget, price, number(match.group(2)), match.group(), errors);
            });
            if (prices.size() == 1) {
                BigDecimal price = prices.iterator().next();
                normalized = replace(normalized, BUDGET_REMAINDER, match ->
                        verifyDifference(budget, price, number(match.group(1)), match.group(), errors));
            }
        }
        return new Result(normalized, List.copyOf(errors));
    }

    private static boolean verifyDifference(BigDecimal budget, BigDecimal price, BigDecimal claimed,
                                            String snippet, List<HallucinationClaim> errors) {
        BigDecimal actual = budget.subtract(price);
        // These phrases claim money remaining, not money owed. Do not validate an over-budget
        // product by taking an absolute difference or rounding a calculation to fit the claim.
        if (actual.signum() < 0 || claimed.compareTo(actual) != 0) {
            errors.add(error("预算差额错误", snippet));
            return false;
        }
        return true;
    }

    private static String replace(String answer, Pattern pattern,
                                  java.util.function.Predicate<Matcher> verified) {
        Matcher matcher = pattern.matcher(answer);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    verified.test(matcher) ? "预算差额已按实际价格核实" : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static BigDecimal number(String raw) {
        return new BigDecimal(raw.replace(",", "")).stripTrailingZeros();
    }

    private static HallucinationClaim error(String type, String snippet) {
        return new HallucinationClaim(type, "预算差额必须使用已知商品价格准确计算", snippet, 0.7);
    }
}
