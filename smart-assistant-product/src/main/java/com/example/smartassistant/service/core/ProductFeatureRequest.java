package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.ProductFeatureConstraints;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative deterministic extraction; vague, unsupported or conflicting bounds are clarified. */
public record ProductFeatureRequest(ProductFeatureConstraints constraints, String clarification) {
    private static final String NUMBER = "(\\d+(?:\\.\\d+)?)";
    private static final String WEIGHT_UNIT = "(kg|千克|公斤|克|g)";
    private static final Pattern WEIGHT = Pattern.compile(
            "(?:重量上[限线](?:为|是|设为|改为|调整为|[:：=])?|不超过|不大于|最多|至多|<=|≤)" + NUMBER + WEIGHT_UNIT
                    + "|" + NUMBER + WEIGHT_UNIT + "(?:以内|以下|及以下)");
    private static final Pattern BATTERY = Pattern.compile(
            "续航(?:时间)?(?:至少|不低于|不少于|>=|≥)" + NUMBER + "(?:小时|h)"
                    + "|续航(?:时间)?" + NUMBER + "(?:小时|h)(?:以上|及以上|起)");

    public static ProductFeatureRequest parse(String question) {
        String weightQuery = normalize(ProductQueryContext.latest(question,
                text -> text.matches("(?is).*(?:重量|轻便|便携|\\d\\s*(?:kg|千克|公斤|克|g)).*")));
        String batteryQuery = normalize(ProductQueryContext.latest(question, text -> text.contains("续航")));
        String ancQuery = normalize(ProductQueryContext.latest(question, text -> text.contains("降噪")));
        String query = weightQuery;
        List<String> missing = new ArrayList<>();
        BigDecimal weight = null;
        var wm = WEIGHT.matcher(query);
        int weightBounds = 0;
        while (wm.find()) {
            BigDecimal value = new BigDecimal(wm.group(1) != null ? wm.group(1) : wm.group(3));
            String unit = wm.group(2) != null ? wm.group(2) : wm.group(4);
            if (unit.equals("kg") || unit.equals("千克") || unit.equals("公斤")) value = value.multiply(BigDecimal.valueOf(1000));
            weight = weight == null ? value : weight.min(value);
            weightBounds++;
        }
        if ((query.contains("轻便") || query.contains("便携") || query.contains("重量")
                || query.matches(".*\\d(?:kg|千克|公斤|克|g).*")) && weight == null) {
            missing.add("可接受的重量上限（例如重量不超过1.3公斤）");
        }
        if (weightBounds > 1) missing.add("唯一的重量上限");
        if (weightBounds > 0 && Pattern.compile(NUMBER + WEIGHT_UNIT).matcher(query).results().count() > weightBounds) {
            missing.add("重量仅支持明确的单一上限，请确认其他重量条件");
        }

        query = batteryQuery;
        BigDecimal battery = null;
        var bm = BATTERY.matcher(query);
        int batteryBounds = 0;
        while (bm.find()) {
            BigDecimal value = new BigDecimal(bm.group(1) != null ? bm.group(1) : bm.group(2));
            battery = battery == null ? value : battery.max(value);
            batteryBounds++;
        }
        if (query.contains("续航") && battery == null) missing.add("最低续航时长（例如续航至少10小时）");
        if (batteryBounds > 1) missing.add("唯一的续航下限");
        List<String> scenarios = new ArrayList<>();
        if (query.contains("视频播放") || query.contains("看视频")) scenarios.add("video_playback");
        if (query.matches(".*(?:开启|打开|开着)(?:主动)?降噪.*(?:听歌|音乐).*")) scenarios.add("audio_anc_on");
        if (query.matches(".*(?:关闭|关掉|不开)(?:主动)?降噪.*(?:听歌|音乐).*")) scenarios.add("audio_anc_off");
        if (query.contains("综合使用") || query.contains("混合使用")) scenarios.add("mixed_use");
        if (battery != null && scenarios.size() != 1) missing.add("一种续航测试场景（视频播放、开/关降噪听歌或综合使用）");

        query = ancQuery;
        Boolean anc = null;
        if (query.contains("通话降噪") || query.contains("被动降噪")) missing.add("是否要求主动降噪（通话或被动降噪不能替代）");
        if (query.contains("不支持主动降噪") || query.contains("没有主动降噪")) anc = false;
        else if (query.contains("降噪") && !query.matches(".*(?:关闭|关掉|不开)(?:主动)?降噪.*")) anc = true;
        if ((weight != null && weight.signum() <= 0) || (battery != null && battery.signum() <= 0)) {
            missing.add("大于0的重量或续航数值");
        }
        if ((weight != null || battery != null || anc != null)
                && (weightQuery + batteryQuery + ancQuery).matches(".*(?:或者|或是|二选一).*")) {
            missing.add("是否需要同时满足这些条件（目前不自动解释二选一条件）");
        }
        if (!missing.isEmpty()) return new ProductFeatureRequest(ProductFeatureConstraints.NONE,
                "为了帮您准确筛选，还想确认一下" + String.join("、", missing) + "。"
                        + (battery != null && scenarios.size() != 1 ? "不同续航测试场景不能直接比较。" : ""));
        return new ProductFeatureRequest(new ProductFeatureConstraints(weight, battery,
                scenarios.size() == 1 ? scenarios.getFirst() : "", anc), "");
    }

    private static String normalize(String question) {
        return question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                .replaceAll("(?:不需要|不要求|不关心|无需)(?:主动降噪|降噪|长续航|续航|轻便|便携)", "");
    }
}
