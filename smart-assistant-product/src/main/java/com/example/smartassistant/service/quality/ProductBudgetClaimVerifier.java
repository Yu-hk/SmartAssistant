package com.example.smartassistant.service.quality;

import com.example.smartassistant.common.rag.eval.HallucinationDetector.HallucinationClaim;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keep budget constraints separate from catalog facts and check arithmetic before masking them. */
public final class ProductBudgetClaimVerifier {
    private static final String AMOUNT = "(" + ProductMoneySyntax.DECIMAL + ")" + ProductMoneySyntax.END;
    private static final Pattern BUDGET = Pattern.compile(
            "预算\\s*(?:[:：=]|<=|≤|<|不超过|上限(?:为)?|最多)?\\s*[¥￥]?\\s*" + AMOUNT + "\\s*元?"
                    + "|" + ProductMoneySyntax.START + "[¥￥]?" + AMOUNT + "\\s*元?\\s*(?:以内|内)?\\s*(?:的)?预算");
    private static final Pattern PRICE = Pattern.compile(
            "(?:\\bprice\\s*[=:]|价格\\s*[:：]?|售价\\s*[:：]?)\\s*[¥￥]?\\s*" + AMOUNT);
    private static final Pattern COMPARISON = Pattern.compile(
            ProductMoneySyntax.START + "(?:(?<leftRole>预算|价格|售价)\\s*[:：]?\\s*)?"
                    + "[¥￥]?\\s*(?<left>" + ProductMoneySyntax.DECIMAL + ")" + ProductMoneySyntax.END
                    + "\\s*元?\\s*(?<operator><=|>=|≤|≥|<|>|=)\\s*"
                    + "(?:(?<rightRole>预算|价格|售价)\\s*[:：]?\\s*)?[¥￥]?\\s*"
                    + "(?<right>" + ProductMoneySyntax.DECIMAL + ")" + ProductMoneySyntax.END + "\\s*元?");
    private static final Pattern REQUIREMENT = Pattern.compile(
            "(?:若|如果)?(?:坚持|仍按|仍以|按|希望|要求|限定在|控制在)\\s*(?:预算|价位)?\\s*[¥￥]?"
                    + AMOUNT + "\\s*元\\s*(?:以内|以下|内)");
    private static final Pattern PRICE_PREFIX = Pattern.compile("(?:价格|售价|市场价|参考价)\\s*[:：]?\\s*[¥￥]?\\s*$");
    private static final Pattern FOREIGN_CURRENCY_PREFIX = Pattern.compile("[$€£]\\s*$");

    private ProductBudgetClaimVerifier() { }

    public record Result(String answer, List<HallucinationClaim> errors) { }

    public static Result verify(String answer, String context, String question) {
        Set<BigDecimal> budgets = new HashSet<>();
        Matcher requested = BUDGET.matcher(ProductMoneySyntax.normalize(question));
        while (requested.find()) budgets.add(budget(requested));
        Set<BigDecimal> prices = new HashSet<>();
        Matcher price = PRICE.matcher(ProductMoneySyntax.normalize(context));
        while (price.find()) prices.add(ProductMoneySyntax.number(price.group(1)));
        String normalized = ProductMoneySyntax.normalize(answer);
        var derived = ProductBudgetDerivationVerifier.verify(normalized, budgets, prices);
        List<HallucinationClaim> errors = new ArrayList<>(derived.errors());
        // Comparisons precede budget masking so explicitly labeled operands remain available.
        String compared = comparisons(derived.answer(), budgets, prices, errors);
        String constrained = mask(compared, REQUIREMENT, m -> budgets.contains(ProductMoneySyntax.number(m.group(1))));
        String repeated = mask(constrained, BUDGET, m -> budgets.contains(budget(m)));
        return new Result(repeated, List.copyOf(errors));
    }

    private static String comparisons(String answer, Set<BigDecimal> budgets, Set<BigDecimal> prices,
                                      List<HallucinationClaim> errors) {
        Matcher match = COMPARISON.matcher(answer);
        StringBuilder result = new StringBuilder();
        while (match.find()) {
            if (FOREIGN_CURRENCY_PREFIX.matcher(answer.substring(Math.max(0, match.start() - 8), match.start())).find()) {
                match.appendReplacement(result, Matcher.quoteReplacement(match.group()));
                continue;
            }
            BigDecimal left = ProductMoneySyntax.number(match.group("left"));
            BigDecimal right = ProductMoneySyntax.number(match.group("right"));
            boolean known = (prices.contains(left) && budgets.contains(right))
                    || (budgets.contains(left) && prices.contains(right));
            boolean budgetHeading = "预算".equals(match.group("leftRole"))
                    && answer.substring(Math.max(0, match.start() - 8), match.start()).matches(".*(?:符合|满足)(?:用户|本次)?");
            boolean roles = (budgetHeading || roleMatches(match.group("leftRole"), left, budgets, prices))
                    && roleMatches(match.group("rightRole"), right, budgets, prices)
                    && (!hasPricePrefix(answer, match.start()) || prices.contains(left));
            boolean correct = switch (match.group("operator")) {
                case "<=", "≤" -> left.compareTo(right) <= 0;
                case "<" -> left.compareTo(right) < 0;
                case ">=", "≥" -> left.compareTo(right) >= 0;
                case ">" -> left.compareTo(right) > 0;
                default -> left.compareTo(right) == 0;
            };
            boolean budgetComparison = budgets.contains(left) || budgets.contains(right)
                    || "预算".equals(match.group("leftRole")) || "预算".equals(match.group("rightRole"));
            if (budgetComparison && (!known || !roles || !correct)) {
                errors.add(new HallucinationClaim("预算比较错误", "价格与预算比较或金额角色不成立", match.group(), 0.7));
            }
            match.appendReplacement(result, Matcher.quoteReplacement(known && roles && correct
                    ? "价格与预算比较已核实" : match.group()));
        }
        match.appendTail(result);
        return result.toString();
    }

    private static boolean roleMatches(String role, BigDecimal value, Set<BigDecimal> budgets, Set<BigDecimal> prices) {
        return role == null || (role.equals("预算") ? budgets.contains(value) : prices.contains(value));
    }

    private static String mask(String answer, Pattern pattern, java.util.function.Predicate<Matcher> verified) {
        Matcher match = pattern.matcher(answer);
        StringBuilder result = new StringBuilder();
        while (match.find()) {
            // A contextual constraint cannot erase a separate claim that the budget is a price.
            match.appendReplacement(result, Matcher.quoteReplacement(verified.test(match)
                    && !hasPricePrefix(answer, match.start()) ? "用户预算" : match.group()));
        }
        match.appendTail(result);
        return result.toString();
    }

    private static boolean hasPricePrefix(String answer, int start) {
        return PRICE_PREFIX.matcher(answer.substring(Math.max(0, start - 32), start)).find();
    }

    private static BigDecimal budget(Matcher match) {
        return ProductMoneySyntax.number(match.group(1) != null ? match.group(1) : match.group(2));
    }
}
