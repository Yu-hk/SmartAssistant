package com.example.smartassistant.consumer.tool;

import org.knowm.xchart.*;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/**
 * DataGifTool 快速功能测试（独立可执行）
 * 模拟：查数据库 → 渲染折线图 → 合成 GIF
 */
public class DataGifToolTest2 {

    public static void main(String[] args) throws Exception {
        // 1. 模拟数据（近 30 天用户增长）
        String[][] raw = {
            {"2026-04-06","3"},{"2026-04-07","5"},{"2026-04-08","8"},
            {"2026-04-09","6"},{"2026-04-10","5"},{"2026-04-11","6"},
            {"2026-04-12","7"},{"2026-04-13","3"},{"2026-04-14","5"},
            {"2026-04-15","6"},{"2026-04-16","9"},{"2026-04-17","3"},
            {"2026-04-18","5"},{"2026-04-19","2"},{"2026-04-20","8"},
            {"2026-04-21","5"},{"2026-04-22","3"},{"2026-04-23","14"},
            {"2026-04-27","1"}
        };
        String[] dates = Arrays.stream(raw).map(r -> r[0]).toArray(String[]::new);
        double[] values = Arrays.stream(raw).mapToDouble(r -> Double.parseDouble(r[1])).toArray();

        System.out.println("数据点: " + raw.length);

        // 2. 逐帧渲染折线图
        int w = 800, h = 480, total = raw.length;
        Color blue = new Color(49, 130, 206);
        double yMax = Arrays.stream(values).max().orElse(100) * 1.2;

        java.util.List<BufferedImage> frames = new ArrayList<>();

        for (int frame = 1; frame <= total; frame++) {
            XYChart chart = new XYChartBuilder().width(w).height(h)
                .title("近30天用户增长趋势")
                .xAxisTitle("日期")
                .yAxisTitle("新增用户数")
                .build();

            chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
            chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
            chart.getStyler().setChartBackgroundColor(Color.WHITE);
            chart.getStyler().setPlotBackgroundColor(new Color(248, 250, 252));
            chart.getStyler().setPlotGridLinesColor(new Color(226, 232, 240));
            chart.getStyler().setAxisTickLabelsColor(new Color(74, 85, 104));
            chart.getStyler().setMarkerSize(6);

            // X 轴标签间隔
            int step = Math.max(1, total / 10);
            chart.getStyler().setxAxisTickLabelsFormattingFunction(d -> {
                int idx = (int) Math.round(d);
                return (idx >= 0 && idx < total && (idx % step == 0 || idx == total - 1))
                    ? dates[idx] : "";
            });

            double[] x = new double[frame];
            double[] y = new double[frame];
            for (int i = 0; i < frame; i++) {
                x[i] = i;
                y[i] = values[i];
            }

            XYSeries series = chart.addSeries("用户数", x, y);
            series.setLineColor(blue);
            series.setMarker(SeriesMarkers.CIRCLE);
            series.setMarkerColor(blue);
            series.setLineWidth(2.0f);
            chart.getStyler().setYAxisMax(yMax);

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            chart.paint(g, w, h);
            g.dispose();
            frames.add(img);
        }

        System.out.println("渲染完成: " + frames.size() + " 帧");

        // 3. 编码为 GIF
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
            ImageWriteParam iwp = writer.getDefaultWriteParam();

            writer.setOutput(ios);
            writer.prepareWriteSequence(null);

            for (int i = 0; i < frames.size(); i++) {
                int delay = (i == frames.size() - 1) ? 300 : 40; // 最后一帧 3s
                IIOMetadata meta = createFrameMetadata(writer, delay);
                writer.writeToSequence(new IIOImage(frames.get(i), null, meta), null);
            }

            writer.endWriteSequence();
            writer.dispose();
        }

        byte[] gifData = baos.toByteArray();
        String path = "target/test-user-growth.gif";
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(gifData);
        }

        System.out.println("\n✅ GIF 生成成功！");
        System.out.println("   文件: " + new File(path).getAbsolutePath());
        System.out.println("   大小: " + (gifData.length / 1024) + " KB");
        System.out.println("   帧数: " + frames.size());
        System.out.println("   data:image/gif;base64," + Base64.getEncoder().encodeToString(gifData).substring(0, 50) + "...");
    }

    static IIOMetadata createFrameMetadata(ImageWriter writer, int delayCs) throws Exception {
        ImageWriteParam iwp = writer.getDefaultWriteParam();
        IIOMetadata meta = writer.getDefaultImageMetadata(
            ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB), iwp);
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
    }
}
