package com.example.smartassistant.consumer.tool;

import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据动图生成工具
 * <p>
 * 将时间序列数据渲染为逐帧动画 GIF。纯 Java 实现，无外部依赖。
 * </p>
 */
@Component
public class DataGifTool {

    private static final Logger log = LoggerFactory.getLogger(DataGifTool.class);
    
    // ⭐ GIF 缓存：避免 400KB+ Base64 数据直接流过 LLM 上下文
    private static final ConcurrentHashMap<String, byte[]> gifCache = new ConcurrentHashMap<>();
    private static final String GIF_CACHE_PREFIX = "GIF_CACHE:";

    /**
     * 从缓存中获取 GIF 数据并清理
     */
    public static byte[] getGifFromCache(String cacheKey) {
        byte[] data = gifCache.remove(cacheKey);
        if (data == null) {
            log.warn("[DataGifTool] 缓存未命中: {}", cacheKey);
        }
        return data;
    }

    @Tool(description = "根据时间序列数据生成趋势动画 GIF。输入需包含日期和数值两列，返回缓存 key（请勿在最终回答中暴露缓存 key）。先调用 executeQuery 获取数据后再调用此工具。")
    public String generateTrendGif(
            @ToolParam(description = "图表标题，如'用户增长趋势'") String title,
            @ToolParam(description = "X 轴标签，如'日期'") String xLabel,
            @ToolParam(description = "Y 轴标签，如'用户数'") String yLabel,
            @ToolParam(description = "时间序列数据，JSON 格式：数组元素包含 date 和 value 字段") String dataJson,
            @ToolParam(description = "线条颜色（可选），如 blue/red/green/orange/purple/teal，默认 blue") String lineColor) {

        log.info("[DataGifTool] 开始生成趋势 GIF: title={}", title);

        try {
            // 1. 解析数据
            List<DataPoint> points = parseDataJson(dataJson);
            if (points.isEmpty()) return "数据为空，无法生成 GIF";
            points.sort(Comparator.comparing(p -> p.date));

            List<String> dates = points.stream().map(p -> p.date).toList();
            List<Double> values = points.stream().map(p -> p.value).toList();
            Color color = parseColor(lineColor != null ? lineColor : "blue");
            double maxVal = values.stream().mapToDouble(v -> v).max().orElse(100);

            // 2. 逐帧渲染
            int w = 800, h = 480, delayCs = 40; // 400ms 每帧
            List<BufferedImage> frames = new ArrayList<>();
            for (int i = 1; i <= points.size(); i++) {
                frames.add(renderFrame(title, xLabel, yLabel,
                        dates.subList(0, i), values.subList(0, i),
                        color, maxVal * 1.2, points.size()));
            }

            // 3. 编码为 GIF（纯 ImageIO）
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
                ImageWriteParam iwp = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(frames.get(0)), iwp);

                // 设置 GIF 动画参数
                configureGifMetadata(metadata, delayCs);

                writer.setOutput(ios);
                writer.prepareWriteSequence(null);

                for (int i = 0; i < frames.size(); i++) {
                    // 最后一帧停留更久
                    int frameDelay = (i == frames.size() - 1) ? 300 : delayCs;
                    IIOMetadata frameMeta = createFrameMetadata(writer, frameDelay);
                    writer.writeToSequence(
                            new IIOImage(frames.get(i), null, frameMeta), null);
                }

                writer.endWriteSequence();
                writer.dispose();
            }

            byte[] gifData = baos.toByteArray();
            // ⭐ 存入缓存，返回短 token 避免 400KB+ 数据流过 LLM 上下文
            String cacheKey = UUID.randomUUID().toString();
            gifCache.put(cacheKey, gifData);
            log.info("[DataGifTool] GIF 生成成功: {} 帧, {} KB, cacheKey={}",
                    frames.size(), gifData.length / 1024, cacheKey);

            return GIF_CACHE_PREFIX + cacheKey;

        } catch (Exception e) {
            log.error("[DataGifTool] GIF 生成失败: {}", e.getMessage(), e);
            return "GIF 生成失败: " + e.getMessage();
        }
    }

    // ==================== 帧渲染 ====================

    private BufferedImage renderFrame(String title, String xLabel, String yLabel,
                                       List<String> dates, List<Double> values,
                                       Color color, double yMax, int totalPoints) {
        XYChart chart = new XYChartBuilder()
                .width(800).height(480)
                .title(title)
                .xAxisTitle(xLabel)
                .yAxisTitle(yLabel)
                .build();

        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.getStyler().setChartBackgroundColor(Color.WHITE);
        chart.getStyler().setPlotBackgroundColor(new Color(248, 250, 252));
        chart.getStyler().setPlotGridLinesColor(new Color(226, 232, 240));
        chart.getStyler().setAxisTickLabelsColor(new Color(74, 85, 104));
        chart.getStyler().setMarkerSize(6);
        chart.getStyler().setYAxisMax(yMax);

        // X 轴标签间隔
        int step = Math.max(1, totalPoints / 10);
        chart.getStyler().setxAxisTickLabelsFormattingFunction(d -> {
            int idx = (int) Math.round(d);
            return (idx >= 0 && idx < dates.size()
                    && (idx % step == 0 || idx == dates.size() - 1))
                    ? dates.get(idx) : " ";
        });

        double[] x = new double[values.size()];
        double[] y = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            x[i] = i;
            y[i] = values.get(i);
        }

        XYSeries series = chart.addSeries("数据", x, y);
        series.setLineColor(color);
        series.setMarker(SeriesMarkers.CIRCLE);
        series.setLineWidth(2.0f);

        // 渲染到 BufferedImage
        BufferedImage img = new BufferedImage(800, 480, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            chart.paint(g, 800, 480);
        } finally {
            g.dispose();
        }
        return img;
    }

    // ==================== GIF 编码 ====================

    private void configureGifMetadata(IIOMetadata metadata, int delayCs) {
        try {
            String metaFormat = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(metaFormat);

            // 添加 GIF 扩展（循环播放）
            IIOMetadataNode appExtensions = new IIOMetadataNode("ApplicationExtensions");
            IIOMetadataNode appExt = new IIOMetadataNode("ApplicationExtension");
            appExt.setAttribute("applicationID", "NETSCAPE");
            appExt.setAttribute("authenticationCode", "2.0");
            appExt.setUserObject(new byte[]{0x1, 0x0, 0x0}); // 循环次数 = 0 (无限)
            appExtensions.appendChild(appExt);
            root.appendChild(appExtensions);

            // 设置全局时间
            IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
            gce.setAttribute("disposalMethod", "none");
            gce.setAttribute("userInputFlag", "FALSE");
            gce.setAttribute("transparentColorFlag", "FALSE");
            gce.setAttribute("delayTime", Integer.toString(delayCs / 10));
            gce.setAttribute("transparentColorIndex", "0");
            root.appendChild(gce);

            metadata.setFromTree(metaFormat, root);
        } catch (Exception e) {
            log.warn("[DataGifTool] 元数据配置失败: {}", e.getMessage());
        }
    }

    private IIOMetadata createFrameMetadata(ImageWriter writer, int delayCs) {
        try {
            ImageWriteParam iwp = writer.getDefaultWriteParam();
            IIOMetadata meta = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromBufferedImageType(
                            BufferedImage.TYPE_INT_ARGB), iwp);
            String fmt = meta.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);

            IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
            gce.setAttribute("disposalMethod", "none");
            gce.setAttribute("userInputFlag", "FALSE");
            gce.setAttribute("transparentColorFlag", "FALSE");
            gce.setAttribute("delayTime", Integer.toString(delayCs / 10));
            gce.setAttribute("transparentColorIndex", "0");
            root.appendChild(gce);

            meta.setFromTree(fmt, root);
            return meta;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 数据解析 ====================

    private List<DataPoint> parseDataJson(String json) {
        List<DataPoint> points = new ArrayList<>();
        try {
            json = json.trim();
            if (json.startsWith("[") && json.endsWith("]")) {
                json = json.substring(1, json.length() - 1).trim();
                if (!json.isEmpty()) {
                    for (String obj : splitJsonObjects(json)) {
                        points.add(parseObject(obj));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[DataGifTool] JSON 解析失败: {}", e.getMessage());
        }
        return points;
    }

    private List<String> splitJsonObjects(String json) {
        List<String> objs = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ',' && depth == 0) {
                objs.add(json.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < json.length()) objs.add(json.substring(start).trim());
        return objs;
    }

    private DataPoint parseObject(String s) {
        String date = "";
        double value = 0;
        java.util.regex.Matcher dm = java.util.regex.Pattern
                .compile("\"date\"\\s*:\\s*\"([^\"]+)\"").matcher(s);
        if (dm.find()) date = dm.group(1);
        java.util.regex.Matcher vm = java.util.regex.Pattern
                .compile("\"value\"\\s*:\\s*([\\d.]+)").matcher(s);
        if (vm.find()) value = Double.parseDouble(vm.group(1));
        return new DataPoint(date, value);
    }

    private Color parseColor(String c) {
        return switch (c.toLowerCase()) {
            case "red" -> new Color(229, 62, 62);
            case "green" -> new Color(56, 161, 105);
            case "orange" -> new Color(221, 107, 32);
            case "purple" -> new Color(128, 90, 213);
            case "teal" -> new Color(49, 151, 149);
            default -> new Color(49, 130, 206);
        };
    }

    private record DataPoint(String date, double value) {}
}
