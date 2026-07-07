/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * PDF 文档解析器——基于 Apache PDFBox 3.x 实现。
 * <p>
 * 按页解析，每页输出若干 ParsedDocument，保留页号用于引用溯源。
 * 已支持：
 * <ul>
 *   <li>双栏排版检测（基于文本块 x 坐标聚类，按栏重排为正确阅读顺序）；</li>
 *   <li>⭐ 表格感知提取（基于文本 x/y 坐标聚类，检测对齐的多列多行区域，
 *       重构为 Markdown 表格，作为 contentType=pdf-table 的独立文档输出，
 *       正文段落不受表格干扰）。</li>
 * </ul>
 * 注意：PDFBox 为纯文本提取，不处理扫描件 OCR；复杂嵌套表格（跨页、
 * 合并单元格）需引入 Camelot/pdfplumber 等专用工具（见分析文档）。
 * </p>
 */
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    /** ⭐ OCR 策略（可插拔，默认空操作降级） */
    private OcrStrategy ocrStrategy = new NoopOcrStrategy();

    /** ⭐ 设置 OCR 策略 */
    public void setOcrStrategy(OcrStrategy ocrStrategy) {
        this.ocrStrategy = ocrStrategy != null ? ocrStrategy : new NoopOcrStrategy();
    }

    @Override
    public List<ParsedDocument> parse(String filePath) throws DocumentParseException {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        String sourceUrl = path.toAbsolutePath().toString();

        List<ParsedDocument> results = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            int totalPages = document.getNumberOfPages();
            log.info("[PdfParser] 开始解析 PDF: file={}, pages={}", fileName, totalPages);

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                PDPage page = document.getPage(pageNum - 1);
                float pageWidth = (page.getMediaBox() != null) ? page.getMediaBox().getWidth() : 612f;

                // ⭐ 双栏检测 + 表格检测：收集每行文本块（含坐标）后，
                //   先抽取表格（输出为 pdf-table 文档），再将非表格正文按栏重排。
                TwoColumnPdfTextStripper stripper = new TwoColumnPdfTextStripper(pageWidth);
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                stripper.setLineSeparator("\n");
                stripper.setParagraphStart("\n\n");

                TwoColumnPdfTextStripper.PageParseResult pageResult = stripper.getPageResult(document);
                if (pageResult.isEmpty()) {
                    log.debug("[PdfParser] 第 {} 页为空，跳过", pageNum);
                    continue;
                }

                // 1) 表格：每个检测到的表格作为一个独立 ParsedDocument
                int tableIdx = 0;
                for (TwoColumnPdfTextStripper.TableBlock table : pageResult.tables()) {
                    tableIdx++;
                    String tableDocId = fileName + "-p" + pageNum + "-table" + tableIdx;
                    String title = extractTitle(table.markdown(), fileName + " 表格" + tableIdx);
                    results.add(ParsedDocument.builder()
                            .docId(tableDocId)
                            .title("表格: " + title)
                            .content(table.markdown())
                            .sourceUrl(sourceUrl)
                            .pageNumber(pageNum)
                            .section("第" + pageNum + "页-表格" + tableIdx)
                            .contentType("pdf-table")
                            .contentHash(sha256(table.markdown()))
                            .build());
                }

                // 2) 正文：排除表格文本块后，按空行分割为段落
                String pageText = pageResult.prose().trim();
                if (!pageText.isBlank()) {
                    String[] paragraphs = pageText.split("\n\\s*\n");
                    for (int paraIdx = 0; paraIdx < paragraphs.length; paraIdx++) {
                        String para = paragraphs[paraIdx].trim();
                        if (para.isBlank()) continue;

                        String title = extractTitle(para, fileName);
                        String section = "第" + pageNum + "页";
                        String contentHash = sha256(para);

                        ParsedDocument parsed = ParsedDocument.builder()
                                .docId(fileName + "-p" + pageNum + "-s" + (paraIdx + 1))
                                .title(title)
                                .content(para)
                                .sourceUrl(sourceUrl)
                                .pageNumber(pageNum)
                                .section(section)
                                .contentType("pdf")
                                .contentHash(contentHash)
                                .build();
                        results.add(parsed);
                    }
                }

                // 3) OCR 图片文本提取（可选，仅在 OCR 策略可用时生效）
                if (ocrStrategy.isAvailable()) {
                    try {
                        // 使用 PDFRenderer 将整页渲染为图片后交给 OCR 策略
                        java.awt.image.BufferedImage pageImg = new PDFRenderer(document)
                                .renderImage(pageNum - 1, 1.5f); // 1.5x 缩放
                        byte[] imgBytes = toPngBytes(pageImg);
                        List<String> ocrTexts = ocrStrategy.extractText(imgBytes,
                                fileName + "-p" + pageNum);
                        for (int oi = 0; oi < ocrTexts.size(); oi++) {
                            String text = ocrTexts.get(oi);
                            if (text.isBlank()) continue;
                            results.add(ParsedDocument.builder()
                                    .docId(fileName + "-p" + pageNum + "-ocr" + oi)
                                    .title("OCR: " + fileName + " 第" + pageNum + "页")
                                    .content(text)
                                    .sourceUrl(sourceUrl)
                                    .pageNumber(pageNum)
                                    .section("第" + pageNum + "页-OCR")
                                    .contentType("pdf-ocr")
                                    .contentHash(sha256(text))
                                    .build());
                        }
                        if (!ocrTexts.isEmpty()) {
                            log.info("[PdfParser] OCR 提取: file={}, page={}", fileName, pageNum);
                        }
                    } catch (Exception e) {
                        log.warn("[PdfParser] OCR 提取异常: file={}, page={}, error={}",
                                fileName, pageNum, e.getMessage());
                    }
                }
            }

            log.info("[PdfParser] 解析完成: file={}, elements={}", fileName, results.size());
        } catch (IOException e) {
            throw new DocumentParseException("PDF 解析失败: " + filePath, e);
        }

        return results;
    }

    /**
     * 双栏 + 表格感知 PDF 文本提取器。
     * <p>
     * 重写 {@code writeString} 收集每一行文本块的 (栏, x, y, 宽度, 字号)，
     * 解析结束后：
     * <ol>
     *   <li>按 y 坐标聚类成行，检测"对齐的多列多行"区域作为表格，重构为 Markdown；</li>
     *   <li>非表格文本块按 (栏, y) 重排，消除双栏乱序，作为正文输出。</li>
     * </ol>
     * </p>
     */
    private static class TwoColumnPdfTextStripper extends PDFTextStripper {

        private final List<Cell> cells = new ArrayList<>();
        private final float pageWidth;

        TwoColumnPdfTextStripper(float pageWidth) throws IOException {
            super();
            this.pageWidth = pageWidth;
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (positions == null || positions.isEmpty()) return;
            TextPosition first = positions.get(0);
            float x = first.getX();
            float y = first.getY();
            float width = first.getWidth();
            float fontSize = first.getFontSize();
            int col = (x < pageWidth / 2f) ? 0 : 1;
            cells.add(new Cell(cells.size(), col, x, y, width, fontSize, text));
        }

        /** 执行表格检测 + 正文重组，返回整页解析结果 */
        PageParseResult getPageResult(PDDocument document) {
            // ⭐ 触发 PDFBox 文本提取（writeString 收集坐标），再执行检测
            if (cells.isEmpty()) {
                try {
                    getText(document);
                } catch (IOException e) {
                    log.warn("[PdfParser] 文本提取失败: {}", e.getMessage());
                    return PageParseResult.EMPTY;
                }
            }
            if (cells.isEmpty()) return PageParseResult.EMPTY;

            List<TableBlock> tables = detectTables();
            Set<Integer> tableCellIdx = new HashSet<>();
            for (TableBlock t : tables) tableCellIdx.addAll(t.cellIndices());

            String prose = buildProse(tableCellIdx);
            return new PageParseResult(prose, tables);
        }

        /** 检测表格：按 y 聚类成行 → 寻找对齐的多列多行区域 → 重构 Markdown */
        private List<TableBlock> detectTables() {
            if (cells.size() < 4) return List.of();

            // 1) 计算行容忍度（基于中位数字号）
            double medianFont = median(cells.stream().map(c -> c.fontSize).sorted().toList());
            double rowTol = Math.max(10, medianFont * 1.8);
            double colTol = Math.max(15, medianFont * 1.5);

            // 2) 按 y 降序（页面自上而下）排序，附带原始索引
            List<IndexedCell> sorted = new ArrayList<>();
            for (Cell c : cells) sorted.add(new IndexedCell(c.index, c));
            sorted.sort(Comparator.<IndexedCell>comparingDouble(ic -> ic.cell.y).reversed());

            // 3) 聚合成行
            List<Row> rows = new ArrayList<>();
            Row current = null;
            for (IndexedCell ic : sorted) {
                if (current == null || Math.abs(ic.cell.y - current.y) > rowTol) {
                    current = new Row(ic.cell.y);
                    rows.add(current);
                }
                current.cells.add(ic);
            }

            // 4) 每行按 x 升序，并记录 x 起点
            for (Row r : rows) {
                r.cells.sort(Comparator.comparingDouble(ic -> ic.cell.x));
                r.xStarts = r.cells.stream().mapToDouble(ic -> ic.cell.x).toArray();
            }

            // 5) 寻找对齐的多列多行区域（连续 >=2 行，每行 >=2 列，列 x 对齐）
            List<TableBlock> tables = new ArrayList<>();
            int i = 0;
            while (i < rows.size()) {
                Row r = rows.get(i);
                if (r.cells.size() < 2) { i++; continue; }

                // 尝试从 r 开始，向后扩展对齐行
                List<Row> run = new ArrayList<>();
                run.add(r);
                double[] canon = r.xStarts;
                int j = i + 1;
                while (j < rows.size()) {
                    Row nxt = rows.get(j);
                    if (nxt.cells.size() >= 2 && aligns(nxt.xStarts, canon, colTol)) {
                        run.add(nxt);
                        j++;
                    } else {
                        break;
                    }
                }

                if (run.size() >= 2) {
                    tables.add(buildTable(run));
                    i = j; // 跳过整个表格区域
                } else {
                    i++;
                }
            }
            return tables;
        }

        /** 判断两行的列 x 起点是否对齐 */
        private static boolean aligns(double[] a, double[] b, double colTol) {
            if (a.length != b.length) return false;
            for (int k = 0; k < a.length; k++) {
                if (Math.abs(a[k] - b[k]) > colTol) return false;
            }
            return true;
        }

        /** 将一组对齐行重构为 Markdown 表格 */
        private TableBlock buildTable(List<Row> run) {
            List<Integer> idx = new ArrayList<>();
            List<String> headerCells = new ArrayList<>();
            for (IndexedCell ic : run.get(0).cells) {
                headerCells.add(ic.cell.text.strip());
                idx.add(ic.index);
            }
            StringBuilder md = new StringBuilder();
            md.append("| ").append(String.join(" | ", headerCells)).append(" |").append("\n");
            md.append("|").append("---|".repeat(headerCells.size())).append("\n");

            for (int r = 1; r < run.size(); r++) {
                List<String> dataCells = new ArrayList<>();
                for (IndexedCell ic : run.get(r).cells) {
                    dataCells.add(ic.cell.text.strip());
                    idx.add(ic.index);
                }
                // 补齐列数，避免 Markdown 表格错位
                while (dataCells.size() < headerCells.size()) dataCells.add("");
                md.append("| ").append(String.join(" | ", dataCells.subList(0, headerCells.size()))).append(" |").append("\n");
            }
            return new TableBlock(md.toString().strip(), idx);
        }

        /** 排除表格文本块后，按 (栏, y) 重排为正文纯文本 */
        private String buildProse(Set<Integer> excluded) {
            List<Cell> proseCells = new ArrayList<>();
            for (Cell c : cells) {
                if (!excluded.contains(c.index)) proseCells.add(c);
            }
            if (proseCells.isEmpty()) return "";

            proseCells.sort(Comparator.comparingInt((Cell c) -> c.col).thenComparingDouble(c -> -c.y));
            StringBuilder sb = new StringBuilder();
            Integer lastCol = null;
            for (Cell c : proseCells) {
                if (lastCol != null && c.col != lastCol) sb.append("\n\n");
                sb.append(c.text).append("\n");
                lastCol = c.col;
            }
            return sb.toString();
        }

        private static double median(List<Double> sorted) {
            if (sorted.isEmpty()) return 12;
            int n = sorted.size();
            return (n % 2 == 1) ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        }

        /** 单页解析结果 */
        private record PageParseResult(String prose, List<TableBlock> tables) {
            static final PageParseResult EMPTY = new PageParseResult("", List.of());
            boolean isEmpty() { return prose.isBlank() && tables.isEmpty(); }
        }

        /** 检测到的表格块（Markdown + 组成该表格的原始 cell 索引） */
        private record TableBlock(String markdown, List<Integer> cellIndices) {}

        /** 一行文本块（含原始索引，便于回写表格 cell） */
        private record IndexedCell(int index, Cell cell) {}

        /** 聚类后的行 */
        private static class Row {
            final double y;
            final List<IndexedCell> cells = new ArrayList<>();
            double[] xStarts;
            Row(double y) { this.y = y; }
        }
    }

    /** 文本块（writeString 收集的最小单元） */
    private record Cell(int index, int col, double x, double y, double width, double fontSize, String text) {}

    /** 提取段落标题：取首行前 80 字 */
    private static String extractTitle(String paragraph, String fallback) {
        String firstLine = paragraph.split("\n", 2)[0].trim();
        if (firstLine.length() > 80) firstLine = firstLine.substring(0, 80) + "...";
        return firstLine.isEmpty() ? fallback : firstLine;
    }

    /** SHA-256 哈希 */
    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }

    /** 将 BufferedImage 编码为 PNG 字节数组 */
    private static byte[] toPngBytes(java.awt.image.BufferedImage img) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
