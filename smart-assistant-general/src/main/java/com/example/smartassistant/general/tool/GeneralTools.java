package com.example.smartassistant.general.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 通用工具集 - 数学计算与单位转换
 *
 * <p>供 ReactAgent 调用的工具，覆盖日常实用场景。</p>
 */
@Component
public class GeneralTools {

    private static final Logger log = LoggerFactory.getLogger(GeneralTools.class);

    // ==================== 数学计算 ====================

    @Tool(description = "计算数学表达式，支持 + - * / () 和平方根(sqrt)、幂(pow)")
    public String calculate(
            @ToolParam(description = "数学表达式，如 '3.14 * 5^2'、'sqrt(144)'、'(12 + 8) * 3.5 / 2'") String expression) {
        log.info("[GeneralTools] 数学计算: expression={}", expression);
        try {
            double result = eval(expression);
            // 结果格式化：整数无小数，其他保留 6 位
            String formatted = formatResult(result);
            log.info("[GeneralTools] 计算结果: {} = {}", expression, formatted);
            return formatted;
        } catch (Exception e) {
            log.warn("[GeneralTools] 计算失败: {}", e.getMessage());
            return "无法计算该表达式，请检查格式是否正确。支持的运算符：+ - * / () ^ sqrt()";
        }
    }

    // ==================== 温度转换 ====================

    @Tool(description = "温度单位转换，支持 Celsius(°C)、Fahrenheit(°F)、Kelvin(K)")
    public String convertTemperature(
            @ToolParam(description = "数值") double value,
            @ToolParam(description = "源单位：C(摄氏)、F(华氏)、K(开尔文)") String fromUnit,
            @ToolParam(description = "目标单位：C(摄氏)、F(华氏)、K(开尔文)") String toUnit) {
        log.info("[GeneralTools] 温度转换: {} {} → {}", value, fromUnit, toUnit);
        try {
            // 先转成 Celsius
            double celsius;
            switch (fromUnit.toUpperCase()) {
                case "C": case "℃": case "°C": celsius = value; break;
                case "F": case "℉": case "°F": celsius = (value - 32) * 5 / 9; break;
                case "K": case "KELVIN": celsius = value - 273.15; break;
                default: return "不支持的温度单位: " + fromUnit + "，请使用 C/F/K";
            }

            // 从 Celsius 转到目标
            double result;
            String unit;
            switch (toUnit.toUpperCase()) {
                case "C": case "℃": case "°C": result = celsius; unit = "°C"; break;
                case "F": case "℉": case "°F": result = celsius * 9 / 5 + 32; unit = "°F"; break;
                case "K": case "KELVIN": result = celsius + 273.15; unit = "K"; break;
                default: return "不支持的温度单位: " + toUnit + "，请使用 C/F/K";
            }

            return formatResult(result) + unit;
        } catch (Exception e) {
            log.warn("[GeneralTools] 温度转换失败: {}", e.getMessage());
            return "温度转换失败，请检查输入格式";
        }
    }

    // ==================== 长度转换 ====================

    @Tool(description = "长度单位转换，支持 米(m)、千米(km)、厘米(cm)、毫米(mm)、英尺(ft)、英寸(in)、英里(mi)")
    public String convertLength(
            @ToolParam(description = "数值") double value,
            @ToolParam(description = "源单位：m/km/cm/mm/ft/in/mi") String fromUnit,
            @ToolParam(description = "目标单位：m/km/cm/mm/ft/in/mi") String toUnit) {
        log.info("[GeneralTools] 长度转换: {} {} → {}", value, fromUnit, toUnit);
        try {
            // 全部转成米
            double meters = toMeters(value, fromUnit);
            if (Double.isNaN(meters)) return "不支持的长度单位: " + fromUnit;

            // 从米转到目标
            double result = fromMeters(meters, toUnit);
            if (Double.isNaN(result)) return "不支持的长度单位: " + toUnit;

            return formatResult(result) + " " + toUnit.toLowerCase();
        } catch (Exception e) {
            log.warn("[GeneralTools] 长度转换失败: {}", e.getMessage());
            return "长度转换失败，请检查输入格式";
        }
    }

    // ==================== 重量转换 ====================

    @Tool(description = "重量单位转换，支持 千克(kg)、克(g)、毫克(mg)、磅(lb)、盎司(oz)、吨(t)")
    public String convertWeight(
            @ToolParam(description = "数值") double value,
            @ToolParam(description = "源单位：kg/g/mg/lb/oz/t") String fromUnit,
            @ToolParam(description = "目标单位：kg/g/mg/lb/oz/t") String toUnit) {
        log.info("[GeneralTools] 重量转换: {} {} → {}", value, fromUnit, toUnit);
        try {
            // 全部转成千克
            double kg = toKilograms(value, fromUnit);
            if (Double.isNaN(kg)) return "不支持的重量单位: " + fromUnit;

            // 从千克转到目标
            double result = fromKilograms(kg, toUnit);
            if (Double.isNaN(result)) return "不支持的重量单位: " + toUnit;

            return formatResult(result) + " " + toUnit.toLowerCase();
        } catch (Exception e) {
            log.warn("[GeneralTools] 重量转换失败: {}", e.getMessage());
            return "重量转换失败，请检查输入格式";
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 安全计算数学表达式（支持 + - * / ^ () sqrt）
     */
    private double eval(String expr) {
        return parseExpression(expr.replace(" ", ""));
    }

    private int pos;
    private String expr;

    private double parseExpression(String s) {
        this.expr = s;
        this.pos = 0;
        double result = parseAddSub();
        if (pos < expr.length()) {
            throw new RuntimeException("无法识别的字符: " + expr.charAt(pos));
        }
        return result;
    }

    private double parseAddSub() {
        double left = parseMulDiv();
        while (pos < expr.length()) {
            char c = expr.charAt(pos);
            if (c == '+') { pos++; left += parseMulDiv(); }
            else if (c == '-') { pos++; left -= parseMulDiv(); }
            else break;
        }
        return left;
    }

    private double parseMulDiv() {
        double left = parsePow();
        while (pos < expr.length()) {
            char c = expr.charAt(pos);
            if (c == '*') { pos++; left *= parsePow(); }
            else if (c == '/') { pos++; left /= parsePow(); }
            else break;
        }
        return left;
    }

    private double parsePow() {
        double left = parseUnary();
        if (pos < expr.length() && expr.charAt(pos) == '^') {
            pos++;
            double right = parsePow(); // right-associative
            left = Math.pow(left, right);
        }
        return left;
    }

    private double parseUnary() {
        if (pos < expr.length() && expr.charAt(pos) == '-') {
            pos++;
            return -parseAtom();
        }
        return parseAtom();
    }

    private double parseAtom() {
        // sqrt()
        if (pos + 4 < expr.length() && expr.startsWith("sqrt", pos)) {
            pos += 4;
            expect('(');
            double val = parseAddSub();
            expect(')');
            return Math.sqrt(val);
        }
        // 数字常量 pi/e
        if (pos + 2 <= expr.length() && expr.startsWith("pi", pos)) { pos += 2; return Math.PI; }
        if (pos < expr.length() && expr.charAt(pos) == 'e') { pos++; return Math.E; }
        // 括号
        if (pos < expr.length() && expr.charAt(pos) == '(') {
            pos++;
            double val = parseAddSub();
            expect(')');
            return val;
        }
        // 数字
        int start = pos;
        while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) pos++;
        if (pos == start) throw new RuntimeException("期望数字，但遇到: " + (pos < expr.length() ? expr.charAt(pos) : "结束"));
        return Double.parseDouble(expr.substring(start, pos));
    }

    private void expect(char c) {
        if (pos >= expr.length() || expr.charAt(pos) != c)
            throw new RuntimeException("期望 '" + c + "'，但遇到: " + (pos < expr.length() ? expr.charAt(pos) : "结束"));
        pos++;
    }

    // ==================== 单位转换辅助 ====================

    private double toMeters(double value, String unit) {
        return switch (unit.toLowerCase()) {
            case "m" -> value;
            case "km" -> value * 1000;
            case "cm" -> value / 100;
            case "mm" -> value / 1000;
            case "ft", "英尺" -> value * 0.3048;
            case "in", "英寸" -> value * 0.0254;
            case "mi", "英里" -> value * 1609.344;
            default -> Double.NaN;
        };
    }

    private double fromMeters(double meters, String unit) {
        return switch (unit.toLowerCase()) {
            case "m" -> meters;
            case "km" -> meters / 1000;
            case "cm" -> meters * 100;
            case "mm" -> meters * 1000;
            case "ft", "英尺" -> meters / 0.3048;
            case "in", "英寸" -> meters / 0.0254;
            case "mi", "英里" -> meters / 1609.344;
            default -> Double.NaN;
        };
    }

    private double toKilograms(double value, String unit) {
        return switch (unit.toLowerCase()) {
            case "kg", "千克" -> value;
            case "g", "克" -> value / 1000;
            case "mg", "毫克" -> value / 1_000_000;
            case "lb", "磅" -> value * 0.45359237;
            case "oz", "盎司" -> value * 0.028349523125;
            case "t", "吨" -> value * 1000;
            default -> Double.NaN;
        };
    }

    private double fromKilograms(double kg, String unit) {
        return switch (unit.toLowerCase()) {
            case "kg", "千克" -> kg;
            case "g", "克" -> kg * 1000;
            case "mg", "毫克" -> kg * 1_000_000;
            case "lb", "磅" -> kg / 0.45359237;
            case "oz", "盎司" -> kg / 0.028349523125;
            case "t", "吨" -> kg / 1000;
            default -> Double.NaN;
        };
    }

    private String formatResult(double value) {
        if (Double.isInfinite(value)) return "结果无穷大";
        if (Double.isNaN(value))      return "结果不是有效数字";
        // 整数无小数
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        // 保留 6 位小数，去除末尾多余的 0
        BigDecimal bd = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
        return bd.stripTrailingZeros().toPlainString();
    }
}
