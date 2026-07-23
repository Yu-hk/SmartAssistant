/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * PDF 文档解析器——基于 Apache PDFBox 3.x 实现。
 * <p>
 * 按页解析，每页输出若干 ParsedDocument，保留页号用于引用溯源。支持：
 * <ul>
 *   <li>⭐ 多栏排版检测（基于文本块 x 坐标聚类，按栏重排为正确阅读顺序，支持双栏/三栏等）；</li>
 *   <li>⭐ 表格感知提取（基于文本 x/y 坐标聚类的<b>列桶算法</b>，可处理对齐表、
 *       合并单元格/跨列（colspan）场景，重构为 Markdown 表格，作为 contentType=pdf-table
 *       的独立文档输出，正文段落不受表格干扰）；</li>
 *   <li>⭐ 扫描件 OCR（页面无文本但有图片时，渲染整页交 OCR 策略提取，结果为 pdf-ocr 文档）。</li>
 *   <li>⭐ 内嵌图区域 OCR（文本页含图片时，逐个抽取 {@code PDImageXObject} 仅对图片本身 OCR，
 *       输出 pdf-image-ocr 文档，并与同页正文/表格去重，避免正文被重复索引）。</li>
 *   <li>⭐ 内嵌图语义说明（文本页含图片且 caption 策略可用时，逐个抽取嵌入图交多模态模型
 *       理解内容，输出 pdf-image-caption 文档，与 OCR 互补：OCR 抽图内文字，caption 理解图在表达什么）。</li>
 * </ul>
 * 解析质量指标通过 {@code metadata} 透出（pdf.table / pdf.columns / pdf.ocr*），
 * OCR 引擎不可用时对扫描件打出 WARN 降级告警，绝不阻断解析。
 * </p>
 */
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    /** OCR 策略（可插拔，默认按环境自动检测：系统 Tesseract 可用则启用，否则降级为空操作） */
    private OcrStrategy ocrStrategy = OcrStrategies.autoDetect();

    /** 设置 OCR 策略 */
    public void setOcrStrategy(OcrStrategy ocrStrategy) {
        this.ocrStrategy = ocrStrategy != null ? ocrStrategy : new NoopOcrStrategy();
    }

    /** 图片语义说明策略（可插拔，默认空操作降级，需显式注入多模态策略才生效） */
    private ImageCaptionStrategy imageCaptionStrategy = new NoopImageCaptionStrategy();

    /** 设置图片语义说明策略 */
    public void setImageCaptionStrategy(ImageCaptionStrategy imageCaptionStrategy) {
        this.imageCaptionStrategy = imageCaptionStrategy != null ? imageCaptionStrategy : new NoopImageCaptionStrategy();
    }

    /**
     * 图片 caption 开关（R5 caption 独占），默认开启。
     * MinerU 活跃路径下由调用方关闭，避免与 MinerU 对同一图片重复 caption。
     */
    private boolean captionEnabled = true;

    /** 内嵌图片 OCR 开关（R5），默认开启 */
    private boolean imageOcrEnabled = true;

    /** 设置图片 caption 开关（R5） */
    public void setCaptionEnabled(boolean captionEnabled) {
        this.captionEnabled = captionEnabled;
    }

    /** 设置内嵌图片 OCR 开关（R5） */
    public void setImageOcrEnabled(boolean imageOcrEnabled) {
        this.imageOcrEnabled = imageOcrEnabled;
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
                boolean pageHasImages = hasImages(page);

                // 双栏/表格检测：收集每行文本块（含坐标）后，先抽取表格，再将非表格正文按栏重排。
                TwoColumnPdfTextStripper stripper = new TwoColumnPdfTextStripper(pageWidth);
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                stripper.setLineSeparator("\n");
                stripper.setParagraphStart("\n\n");

                TwoColumnPdfTextStripper.PageParseResult pageResult = stripper.getPageResult(document);
                if (pageResult.isEmpty()) {
                    // 扫描件（无文本但有图片）→ 尝试 OCR
                    if (pageHasImages) {
                        results.addAll(runOcr(pageNum, document, sourceUrl, fileName, true));
                    } else {
                        log.debug("[PdfParser] 第 {} 页为空，跳过", pageNum);
                    }
                    continue;
                }

                // 1) 表格：每个检测到的表格作为一个独立 ParsedDocument（含质量指标）
                List<String> pageExistingTexts = new ArrayList<>();
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
                            .metadata(Map.of(
                                    "pdf.table", "1",
                                    "pdf.tableIndex", String.valueOf(tableIdx)))
                            .build());
                    pageExistingTexts.add(table.markdown());
                }

                // 2) 正文：排除表格文本块后，按空行分割为段落（标注栏数指标）
                String pageText = pageResult.prose().text().trim();
                int columnCount = pageResult.prose().columnCount();
                if (!pageText.isBlank()) {
                    String[] paragraphs = pageText.split("\n\\s*\n");
                    for (int paraIdx = 0; paraIdx < paragraphs.length; paraIdx++) {
                        String para = paragraphs[paraIdx].trim();
                        if (para.isBlank()) continue;

                        String title = extractTitle(para, fileName);
                        String section = "第" + pageNum + "页";
                        String contentHash = sha256(para);

                        Map<String, String> meta = new LinkedHashMap<>();
                        meta.put("pdf.columns", String.valueOf(columnCount));

                        results.add(ParsedDocument.builder()
                                .docId(fileName + "-p" + pageNum + "-s" + (paraIdx + 1))
                                .title(title)
                                .content(para)
                                .sourceUrl(sourceUrl)
                                .pageNumber(pageNum)
                                .section(section)
                                .contentType("pdf")
                                .contentHash(contentHash)
                                .metadata(meta)
                                .build());
                        pageExistingTexts.add(para);
                    }
                }

                // 3) 图片处理：扫描件整页 OCR；文本页抽取内嵌图区域 OCR + 语义说明
                if (pageHasImages) {
                    if (pageResult.isEmpty()) {
                        // 扫描件（无文本）：整页渲染 OCR
                        results.addAll(runOcr(pageNum, document, sourceUrl, fileName, true));
                    } else if (ocrStrategy.isAvailable() && imageOcrEnabled) {
                        // 文本页内嵌图：逐图区域 OCR（不整页重扫，正文不重复）
                        results.addAll(runImageOcr(pageNum, page, sourceUrl, fileName, pageExistingTexts));
                    } else if (ocrStrategy.isAvailable() && !imageOcrEnabled) {
                        log.debug("[PdfParser] 第{}页内嵌图 OCR 已按 R5 开关关闭（captionExclusive）", pageNum);
                    } else {
                        log.warn("[PdfParser] 第{}页含内嵌图片但 OCR 不可用(engine={})，图片内文字将缺失，请部署 Tesseract 或 Ollama",
                                pageNum, ocrStrategy.engineName());
                    }
                    // 嵌入图语义说明（仅文本页的图表/截图；扫描件整页已走 OCR，不再 caption）：
                    // 与 OCR 互补——OCR 抽图内文字，caption 理解图在表达什么
                    // R5：captionEnabled 为 false 时结构性禁用（MinerU 活跃路径），避免同图双重 caption
                    if (!pageResult.isEmpty() && captionEnabled) {
                        results.addAll(runImageCaption(pageNum, page, sourceUrl, fileName));
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
     * 页面是否包含图片（用于扫描件探测）。
     * <p>提升为 public static 以便路由层 {@code PdfParserRouter} 复用（R2 预扫描），不破坏既有调用。</p>
     */
    public static boolean hasImages(PDPage page) {
        try {
            PDResources res = page.getResources();
            if (res == null) return false;
            for (COSName name : res.getXObjectNames()) {
                if (res.getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.debug("[PdfParser] 读取页面资源失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 判断指定页是否为扫描件（无提取文本）。
     * <p>提升为 public static 供路由层 {@code PdfParserRouter} 预扫描复用（R2/R3）。</p>
     *
     * @param doc     已加载的 PDF 文档
     * @param pageNum 页号（从 1 开始）
     * @return true 表示该页无可提取文本（疑似扫描件）
     */
    public static boolean isScannedPage(PDDocument doc, int pageNum) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageNum);
            stripper.setEndPage(pageNum);
            String text = stripper.getText(doc);
            return text == null || text.trim().isEmpty();
        } catch (IOException e) {
            log.debug("[PdfParser] 单页文本提取失败，保守视为扫描件: {}", e.getMessage());
            return true;
        }
    }

    /** 渲染整页并调用 OCR 策略；返回 pdf-ocr 文档列表（含质量指标），永不抛异常 */
    private List<ParsedDocument> runOcr(int pageNum, PDDocument document, String sourceUrl,
                                        String fileName, boolean scanned) {
        List<ParsedDocument> out = new ArrayList<>();
        if (!ocrStrategy.isAvailable()) {
            if (scanned) {
                log.warn("[PdfParser] 第{}页疑似扫描件但 OCR 策略不可用(engine={})，该页文本将缺失",
                        pageNum, ocrStrategy.engineName());
            }
            return out;
        }
        try {
            java.awt.image.BufferedImage pageImg = new PDFRenderer(document)
                    .renderImage(pageNum - 1, 1.5f); // 1.5x 缩放
            byte[] imgBytes = toPngBytes(pageImg);
            List<String> ocrTexts = ocrStrategy.extractText(imgBytes, fileName + "-p" + pageNum);
            for (int oi = 0; oi < ocrTexts.size(); oi++) {
                String text = ocrTexts.get(oi);
                if (text.isBlank()) continue;
                out.add(ParsedDocument.builder()
                        .docId(fileName + "-p" + pageNum + "-ocr" + oi)
                        .title("OCR: " + fileName + " 第" + pageNum + "页")
                        .content(text)
                        .sourceUrl(sourceUrl)
                        .pageNumber(pageNum)
                        .section("第" + pageNum + "页-OCR")
                        .contentType("pdf-ocr")
                        .contentHash(sha256(text))
                        .metadata(Map.of(
                                "pdf.ocr", "1",
                                "pdf.ocrChars", String.valueOf(text.length()),
                                "pdf.ocrEngine", ocrStrategy.engineName(),
                                "pdf.ocrScanned", scanned ? "1" : "0"))
                        .build());
            }
            if (!ocrTexts.isEmpty()) {
                log.info("[PdfParser] OCR 提取: file={}, page={}, engine={}",
                        fileName, pageNum, ocrStrategy.engineName());
            }
        } catch (Exception e) {
            log.warn("[PdfParser] OCR 提取异常: file={}, page={}, error={}",
                    fileName, pageNum, e.getMessage());
        }
        return out;
    }

    /**
     * 文本页内嵌图区域 OCR：逐个抽取页面中的 {@link PDImageXObject}（而非整页重渲染），
     * 仅对图片本身做 OCR，输出 pdf-image-ocr 文档。配合 {@code pageExistingTexts} 去重，
     * 若 OCR 文本与同页正文/表格高度重合则丢弃，避免正文被重复索引。永不抛异常。
     */
    private List<ParsedDocument> runImageOcr(int pageNum, PDPage page, String sourceUrl,
                                            String fileName, List<String> pageExistingTexts) {
        List<ParsedDocument> out = new ArrayList<>();
        try {
            PDResources res = page.getResources();
            if (res == null) return out;
            int imgIdx = 0;
            for (COSName name : res.getXObjectNames()) {
                Object xobj;
                try {
                    xobj = res.getXObject(name);
                } catch (IOException e) {
                    continue;
                }
                if (!(xobj instanceof PDImageXObject img)) continue;
                imgIdx++;
                try {
                    java.awt.image.BufferedImage bim = img.getImage();
                    byte[] bytes = toPngBytes(bim);
                    List<String> texts = ocrStrategy.extractText(bytes, fileName + "-p" + pageNum + "-img" + imgIdx);
                    for (int i = 0; i < texts.size(); i++) {
                        String text = texts.get(i);
                        if (text.isBlank()) continue;
                        // 去重：与同页已有正文/表格高度重合则丢弃
                        if (isDuplicate(text, pageExistingTexts)) {
                            log.debug("[PdfParser] 内嵌图OCR文本与正文重复，跳过: file={}, page={}, img={}",
                                    fileName, pageNum, imgIdx);
                            continue;
                        }
                        out.add(ParsedDocument.builder()
                                .docId(fileName + "-p" + pageNum + "-img" + imgIdx + "-ocr" + i)
                                .title("图片OCR: " + fileName + " 第" + pageNum + "页-图" + imgIdx)
                                .content(text)
                                .sourceUrl(sourceUrl)
                                .pageNumber(pageNum)
                                .section("第" + pageNum + "页-图片" + imgIdx)
                                .contentType("pdf-image-ocr")
                                .contentHash(sha256(text))
                                .metadata(Map.of(
                                        "pdf.image", "1",
                                        "pdf.imageIndex", String.valueOf(imgIdx),
                                        "pdf.ocr", "1",
                                        "pdf.ocrChars", String.valueOf(text.length()),
                                        "pdf.ocrEngine", ocrStrategy.engineName(),
                                        "pdf.ocrRegion", "image"))
                                .build());
                    }
                } catch (Exception e) {
                    log.warn("[PdfParser] 内嵌图OCR失败: file={}, page={}, img={}, error={}",
                            fileName, pageNum, imgIdx, e.getMessage());
                }
            }
            if (!out.isEmpty()) {
                log.info("[PdfParser] 内嵌图区域OCR: file={}, page={}, docs={}", fileName, pageNum, out.size());
            }
        } catch (Exception e) {
            log.debug("[PdfParser] 读取页面资源失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 文本页内嵌图语义说明：逐个抽取页面中的 {@link PDImageXObject}，交图片说明策略（多模态模型）
     * 理解图片内容，输出 pdf-image-caption 文档。与 {@link #runImageOcr} 互补（OCR 抽字，
     * caption 释义），用于让图表/截图含义进入检索。策略不可用时自动跳过。永不抛异常。
     */
    private List<ParsedDocument> runImageCaption(int pageNum, PDPage page, String sourceUrl, String fileName) {
        List<ParsedDocument> out = new ArrayList<>();
        if (imageCaptionStrategy == null || !imageCaptionStrategy.isAvailable()) {
            return out;
        }
        try {
            PDResources res = page.getResources();
            if (res == null) return out;
            int imgIdx = 0;
            for (COSName name : res.getXObjectNames()) {
                Object xobj;
                try {
                    xobj = res.getXObject(name);
                } catch (IOException e) {
                    continue;
                }
                if (!(xobj instanceof PDImageXObject img)) continue;
                imgIdx++;
                try {
                    java.awt.image.BufferedImage bim = img.getImage();
                    byte[] bytes = toPngBytes(bim);
                    String caption = imageCaptionStrategy.caption(bytes, fileName + "-p" + pageNum + "-img" + imgIdx);
                    if (caption == null || caption.isBlank()) continue;
                    out.add(ParsedDocument.builder()
                            .docId(fileName + "-p" + pageNum + "-img" + imgIdx + "-cap")
                            .title("图片说明: " + fileName + " 第" + pageNum + "页-图" + imgIdx)
                            .content(caption)
                            .sourceUrl(sourceUrl)
                            .pageNumber(pageNum)
                            .section("第" + pageNum + "页-图片" + imgIdx)
                            .contentType("pdf-image-caption")
                            .contentHash(sha256(caption))
                            .metadata(Map.of(
                                    "pdf.image", "1",
                                    "pdf.imageIndex", String.valueOf(imgIdx),
                                    "pdf.caption", "1",
                                    "pdf.captionEngine", imageCaptionStrategy.engineName()))
                            .build());
                } catch (Exception e) {
                    log.warn("[PdfParser] 内嵌图语义说明失败: file={}, page={}, img={}, error={}",
                            fileName, pageNum, imgIdx, e.getMessage());
                }
            }
            if (!out.isEmpty()) {
                log.info("[PdfParser] 内嵌图语义说明: file={}, page={}, docs={}", fileName, pageNum, out.size());
            }
        } catch (Exception e) {
            log.debug("[PdfParser] 读取页面资源失败: {}", e.getMessage());
        }
        return out;
    }

    /** OCR 文本是否与已有文本高度重合（互为包含即判重，过短不判以免误删） */
    private static boolean isDuplicate(String candidate, List<String> existing) {
        String c = normalize(candidate);
        if (c.length() < 8) return false;
        for (String e : existing) {
            String n = normalize(e);
            if (n.length() < 8) continue;
            if (c.contains(n) || n.contains(c)) return true;
        }
        return false;
    }

    /** 归一化：转小写并去除空白与标点，用于重复判定 */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[\\s\\p{P}]+", "");
    }

    /**
     * 多栏 + 表格感知 PDF 文本提取器。
     * <p>
     * 重写 {@code writeString} 收集每一行文本块的 (x, y, 宽度, 字号)，解析结束后：
     * <ol>
     *   <li>按 y 坐标聚类成行，检测"对齐的多列多行"区域作为表格（列桶算法，支持合并单元格），重构为 Markdown；</li>
     *   <li>非表格文本块按 x 坐标聚类得到栏数，按 (栏, y) 重排，消除多栏乱序，作为正文输出。</li>
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
            // 暂记栏（后续 buildProse 会基于聚类重算，这里仅占位）
            int provisionalCol = (x < pageWidth / 2f) ? 0 : 1;
            cells.add(new Cell(cells.size(), provisionalCol, x, y, width, fontSize, text));
        }

        /** 执行表格检测 + 正文重组，返回整页解析结果 */
        PageParseResult getPageResult(PDDocument document) {
            // 触发 PDFBox 文本提取（writeString 收集坐标），再执行检测
            if (cells.isEmpty()) {
                try {
                    getText(document);
                } catch (IOException e) {
                    log.warn("[PdfParser] 文本提取失败: {}", e.getMessage());
                    return PageParseResult.EMPTY;
                }
            }
            if (cells.isEmpty()) return PageParseResult.EMPTY;

            // 计算行容忍度（基于中位数字号）
            double medianFont = median(cells.stream().map(c -> c.fontSize).sorted().toList());
            double rowTol = Math.max(10, medianFont * 1.8);
            double colTol = Math.max(15, medianFont * 1.5);

            List<TableBlock> tables = detectTables(colTol, medianFont);
            Set<Integer> tableCellIdx = new HashSet<>();
            for (TableBlock t : tables) tableCellIdx.addAll(t.cellIndices());

            ProseResult prose = buildProse(tableCellIdx, colTol);
            return new PageParseResult(prose, tables);
        }

        /** 检测表格：按 y 聚类成行 → 寻找对齐的多列多行区域 → 列桶重构 Markdown（支持合并单元格） */
        private List<TableBlock> detectTables(double colTol, double medianFont) {
            if (cells.size() < 4) return List.of();

            // 1) 按 y 升序排序（PDFBox getY() 自页面顶部向下递增，故升序即视觉自上而下列序），附带原始索引
            List<IndexedCell> sorted = new ArrayList<>();
            for (Cell c : cells) sorted.add(new IndexedCell(c.index, c));
            sorted.sort(Comparator.comparingDouble(ic -> ic.cell.y));

            // 2) 聚合成行
            List<Row> rows = new ArrayList<>();
            Row current = null;
            double rowTol = Math.max(10, medianFont * 1.8);
            for (IndexedCell ic : sorted) {
                if (current == null || Math.abs(ic.cell.y - current.y) > rowTol) {
                    current = new Row(ic.cell.y);
                    rows.add(current);
                }
                current.cells.add(ic);
            }

            // 3) 寻找对齐的多列多行区域（连续 >=2 行，每行 >=2 列，列结构对齐或子集对齐）
            List<TableBlock> tables = new ArrayList<>();
            int i = 0;
            while (i < rows.size()) {
                Row r = rows.get(i);
                if (r.cells.size() < 2) { i++; continue; }

                List<Row> run = new ArrayList<>();
                run.add(r);
                List<Double> canonLefts = clusterLefts(r, colTol); // 列左边界聚类中心
                int j = i + 1;
                while (j < rows.size()) {
                    Row nxt = rows.get(j);
                    if (nxt.cells.size() >= 2 && alignsWithTableStructure(nxt, canonLefts, colTol)) {
                        run.add(nxt);
                        // 把新行的列左边界并入 canon（允许合并单元格导致的列数差异）
                        for (Double nl : clusterLefts(nxt, colTol)) {
                            if (canonLefts.stream().noneMatch(c -> Math.abs(c - nl) <= colTol)) {
                                canonLefts.add(nl);
                            }
                        }
                        canonLefts.sort(Double::compareTo);
                        j++;
                    } else {
                        break;
                    }
                }

                if (run.size() >= 2) {
                    tables.add(buildTable(run, canonLefts, colTol));
                    i = j; // 跳过整个表格区域
                } else {
                    i++;
                }
            }
            return tables;
        }

        /** 某行的所有单元格左边界是否都落在 canon 列边界附近（允许合并单元格跨列的子集对齐） */
        private static boolean alignsWithCanon(Row row, List<Double> canonLefts, double colTol) {
            if (row.cells.size() < 2) return false;
            for (IndexedCell ic : row.cells) {
                boolean aligned = canonLefts.stream()
                        .anyMatch(c -> Math.abs(ic.cell.x - c) <= colTol * 1.5);
                if (!aligned) return false;
            }
            return true;
        }

        /**
         * 判断一行是否与表格列结构一致（缺陷 B 修复）。
         * <p>真实表格行会把单元格分布到表格的<b>多个不同列</b>（跨列对齐），而紧贴表格的左对齐正文行
         * 即使被拆成多个 token，也只会落在同一列（左边界）附近，不应并入表格。因此在
         * {@link #alignsWithCanon} 的基础上，额外要求该行映射到的“不同列数” ≥ 2 且列填充比例达标。</p>
         *
         * @param row        待判定行
         * @param canonLefts 当前表格 canon 列左边界
         * @param colTol     列容差
         * @return 该行与表格列结构一致（应纳入表格）返回 true
         */
        private static boolean alignsWithTableStructure(Row row, List<Double> canonLefts, double colTol) {
            if (!alignsWithCanon(row, canonLefts, colTol)) return false;
            // 统计该行单元格映射到的不同列集合
            Set<Integer> covered = new HashSet<>();
            for (IndexedCell ic : row.cells) {
                covered.add(columnIndex(ic.cell.x, ic.cell.x + ic.cell.width, canonLefts, colTol));
            }
            int colCount = canonLefts.size();
            // 至少跨 2 个不同列（左对齐正文单行只落一列，应排除）
            if (covered.size() < 2) return false;
            // 列填充比例：覆盖列数 / 表格总列数 需 ≥ 0.5
            return (double) covered.size() / colCount >= 0.5;
        }

        /** 将一组对齐行用列桶算法重构为 Markdown 表格（支持合并单元格/跨列） */
        private TableBlock buildTable(List<Row> run, List<Double> canonLefts, double colTol) {
            // 缺陷 A 修复：按 y 坐标自上而下排序，确保视觉顶部行作为 Markdown 表头
            run.sort(Comparator.comparingDouble(r -> r.y));
            int colCount = canonLefts.size();
            List<Integer> idx = new ArrayList<>();

            // 表头行
            List<String> headerCells = new ArrayList<>(Collections.nCopies(colCount, ""));
            for (IndexedCell ic : run.get(0).cells) {
                int c = columnIndex(ic.cell.x, ic.cell.x + ic.cell.width, canonLefts, colTol);
                headerCells.set(c, ic.cell.text.strip());
                idx.add(ic.index);
            }
            StringBuilder md = new StringBuilder();
            md.append("| ").append(String.join(" | ", headerCells)).append(" |").append("\n");
            md.append("|").append("---|".repeat(colCount)).append("\n");

            // 数据行：合并单元格置于其首个覆盖列，其余列留空（Markdown 无 colspan 语义）
            for (int r = 1; r < run.size(); r++) {
                List<String> dataCells = new ArrayList<>(Collections.nCopies(colCount, ""));
                for (IndexedCell ic : run.get(r).cells) {
                    int c = columnIndex(ic.cell.x, ic.cell.x + ic.cell.width, canonLefts, colTol);
                    if (dataCells.get(c).isEmpty()) {
                        dataCells.set(c, ic.cell.text.strip());
                    }
                    idx.add(ic.index);
                }
                md.append("| ").append(String.join(" | ", dataCells)).append(" |").append("\n");
            }
            return new TableBlock(md.toString().strip(), idx);
        }

        /** 排除表格文本块后，按 (栏, y) 重排为正文纯文本，并返回栏数指标 */
        private ProseResult buildProse(Set<Integer> excluded, double colTol) {
            List<Cell> proseCells = new ArrayList<>();
            for (Cell c : cells) {
                if (!excluded.contains(c.index)) proseCells.add(c);
            }
            if (proseCells.isEmpty()) return new ProseResult("", 1);

            // 基于 x 坐标聚类得到栏数
            List<Double> lefts = proseCells.stream().map(c -> c.x).sorted().toList();
            List<Double> colCenters = cluster(lefts, colTol * 1.2);
            int colCount = colCenters.size();

            for (Cell c : proseCells) {
                c.col = nearestCol(c.x, colCenters);
            }
            proseCells.sort(Comparator.comparingInt((Cell c) -> c.col).thenComparingDouble(c -> -c.y));

            StringBuilder sb = new StringBuilder();
            Integer lastCol = null;
            for (Cell c : proseCells) {
                if (lastCol != null && c.col != lastCol) sb.append("\n\n");
                sb.append(c.text).append("\n");
                lastCol = c.col;
            }
            return new ProseResult(sb.toString(), colCount);
        }

        // ==================== 聚类/索引辅助 ====================

        /** 单行的单元格左边界聚类，返回排序后的列中心列表 */
        private static List<Double> clusterLefts(Row row, double colTol) {
            List<Double> xs = row.cells.stream().map(ic -> ic.cell.x).sorted().toList();
            return cluster(xs, colTol);
        }

        /** 单遍聚类：将排序后的点按容差归并为簇中心 */
        private static List<Double> cluster(List<Double> sorted, double tol) {
            List<Double> centers = new ArrayList<>();
            if (sorted.isEmpty()) return centers;
            double center = sorted.get(0);
            int n = 1;
            double sum = sorted.get(0);
            for (int k = 1; k < sorted.size(); k++) {
                if (sorted.get(k) - center <= tol) {
                    sum += sorted.get(k);
                    n++;
                    center = sum / n;
                } else {
                    centers.add(center);
                    center = sorted.get(k);
                    sum = sorted.get(k);
                    n = 1;
                }
            }
            centers.add(center);
            return centers;
        }

        /** 给定 x 左右边界，返回最近列中心的下标；单元格覆盖某列中心即归该列（支持合并单元格） */
        private static int columnIndex(double x, double xRight, List<Double> centers, double colTol) {
            int best = 0;
            double bestDist = Double.MAX_VALUE;
            for (int k = 0; k < centers.size(); k++) {
                double c = centers.get(k);
                // 单元格覆盖列中心即视为属于该列
                double d = (x - colTol <= c && c <= xRight + colTol) ? Math.abs(c - x) : Math.abs(c - x) + 1e6;
                if (d < bestDist) {
                    bestDist = d;
                    best = k;
                }
            }
            return best;
        }

        /** 给定 x，返回最近列中心下标 */
        private static int nearestCol(double x, List<Double> centers) {
            int best = 0;
            double bestDist = Double.MAX_VALUE;
            for (int k = 0; k < centers.size(); k++) {
                double d = Math.abs(centers.get(k) - x);
                if (d < bestDist) {
                    bestDist = d;
                    best = k;
                }
            }
            return best;
        }

        private static double median(List<Double> sorted) {
            if (sorted.isEmpty()) return 12;
            int n = sorted.size();
            return (n % 2 == 1) ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        }

        /** 单页解析结果 */
        private record PageParseResult(ProseResult prose, List<TableBlock> tables) {
            static final PageParseResult EMPTY =
                    new PageParseResult(new ProseResult("", 1), List.of());
            boolean isEmpty() { return prose.text().isBlank() && tables.isEmpty(); }
        }

        /** 检测到的表格块（Markdown + 组成该表格的原始 cell 索引） */
        private record TableBlock(String markdown, List<Integer> cellIndices) {}

        /** 一行文本块（含原始索引） */
        private record IndexedCell(int index, Cell cell) {}

        /** 聚类后的行 */
        private static class Row {
            final double y;
            final List<IndexedCell> cells = new ArrayList<>();
            Row(double y) { this.y = y; }
        }
    }

    /** 正文重组结果（含栏数指标） */
    private record ProseResult(String text, int columnCount) {}

    /** 文本块（writeString 收集的最小单元，col 在 buildProse 中重算） */
    private static class Cell {
        final int index;
        int col;
        final double x;
        final double y;
        final double width;
        final double fontSize;
        final String text;
        Cell(int index, int col, double x, double y, double width, double fontSize, String text) {
            this.index = index;
            this.col = col;
            this.x = x;
            this.y = y;
            this.width = width;
            this.fontSize = fontSize;
            this.text = text;
        }
    }

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
