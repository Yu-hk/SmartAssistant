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

    /** ⭐ OCR 策略（可插拔，默认按环境自动检测：系统 Tesseract 可用则启用，否则降级为空操作） */
    private OcrStrategy ocrStrategy = OcrStrategies.autoDetect();

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

            // ⭐ 跨页标题接力：若某页以"孤立标题行"结尾（正文在下一页），
            //   将该标题携带到下一页，作为下一页首个 section 的标题与前缀，
            //   避免出现"标题-正文错配"（标题挂在上一页、正文成无题元素）。
            String carryHeading = null;

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

                // 2) 正文：排除表格文本块后，按空行分割为段落，再【按标题/大小合并为长元素】
                //    —— 修复 Parent-Child 父子粒度退化：原实现逐段落输出独立 ParsedDocument，
                //       导致 chunkParentChild 在每个 ~150 token 段落内独立分块，parentMaxTokens=1024 永不可达。
                String pageText = pageResult.prose().trim();
                if (!pageText.isBlank()) {
                    List<String> paraList = new ArrayList<>(
                            List.of(pageText.split("\n\\s*\n")));

                    // ⭐ 页尾孤立标题（正文在下一页）→ 摘下并接力到下一页，
                    //   避免"标题挂上一页、正文成无题元素"的标题-正文错配
                    String nextCarry = null;
                    if (pageNum < totalPages && !paraList.isEmpty()) {
                        String lastPara = paraList.get(paraList.size() - 1).trim();
                        if (!lastPara.contains("\n") && lastPara.length() <= 60
                                && isHeadingLine(lastPara)) {
                            nextCarry = lastPara;
                            paraList.remove(paraList.size() - 1);
                            log.debug("[PdfParser] 页尾孤立标题接力到下一页: page={}, heading={}",
                                    pageNum, nextCarry);
                        }
                    }

                    List<ParsedDocument> sectionDocs = mergeParagraphsToSections(
                            paraList.toArray(new String[0]), fileName, pageNum, sourceUrl, carryHeading);
                    carryHeading = nextCarry;
                    results.addAll(sectionDocs);
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

    /** ⭐ 正文段落合并上限（约 token 数），接近 Parent-Child 的 parentMaxTokens(1024)，避免父块粒度退化 */
    private static final int SECTION_SOFT_CAP = 1000;

    /**
     * ⭐ 标题切分的最小 flush 阈值（约 token 数）。
     * 阅读顺序修复后标题识别变准，若每个标题都切一刀，section 会退化为 ~150 token
     * 的段落级粒度（Parent-Child 双粒度失效）。因此遇到新标题时仅当已积累内容
     * ≥ 该阈值才切分；小章节合并进同一父块，子块检索定位由标题前缀注入（P1）保证。
     */
    private static final int SECTION_MIN_FLUSH = 600;

    /**
     * ⭐ 将一页的正文段落合并为"长元素"（按标题切分 + 大小封顶）。
     * <p>修复 Parent-Child 父子粒度退化：原实现逐段落输出独立 ParsedDocument，
     * 导致 chunkParentChild 在每个 ~150 token 段落内独立分块，parentMaxTokens=1024 永不可达。
     * 合并后每个元素接近父块粒度，父子双粒度才能真正成立；标题文本并入内容以保留上下文。</p>
     */
    private List<ParsedDocument> mergeParagraphsToSections(
            String[] paragraphs, String fileName, int pageNum, String sourceUrl,
            String carryHeading) {
        List<ParsedDocument> out = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        String sectionTitle = null;
        int sectionIdx = 0;

        // ⭐ 上一页接力来的孤立标题：作为本页首个 section 的标题与内容前缀
        if (carryHeading != null && !carryHeading.isBlank()) {
            sectionTitle = carryHeading;
            content.append(carryHeading);
        }

        for (String raw : paragraphs) {
            String para = raw.trim();
            if (para.isBlank()) continue;

            if (isHeadingLine(para)) {
                // 遇到新标题：仅当已积累内容达到最小 flush 阈值才切分，
                // 否则将小章节继续并入当前 section（保持父块章节级粒度）
                if (content.length() > 0
                        && estimateTokens(content.toString()) >= SECTION_MIN_FLUSH) {
                    out.add(buildSectionDoc(fileName, pageNum, sourceUrl,
                            ++sectionIdx, sectionTitle, content.toString().trim(), sha256(content.toString())));
                    content.setLength(0);
                }
                String headingTitle = para.length() > 80 ? para.substring(0, 80) + "..." : para;
                if (content.length() == 0) {
                    sectionTitle = headingTitle;
                } else {
                    content.append("\n\n");
                    if (sectionTitle == null) sectionTitle = headingTitle;
                }
                content.append(para);
            } else {
                if (content.length() == 0) {
                    sectionTitle = extractTitle(para, fileName);
                } else {
                    content.append("\n\n");
                }
                content.append(para);

                // 超过软上限则切分，保持每个元素接近父块粒度（后续交给 Parent-Child 再切子块）
                if (estimateTokens(content.toString()) > SECTION_SOFT_CAP) {
                    out.add(buildSectionDoc(fileName, pageNum, sourceUrl,
                            ++sectionIdx, sectionTitle, content.toString().trim(), sha256(content.toString())));
                    content.setLength(0);
                    sectionTitle = null;
                }
            }
        }
        if (content.length() > 0) {
            out.add(buildSectionDoc(fileName, pageNum, sourceUrl,
                    ++sectionIdx, sectionTitle, content.toString().trim(), sha256(content.toString())));
        }
        return out;
    }

    /** 构造一个合并后的 section ParsedDocument（contentType=pdf） */
    private ParsedDocument buildSectionDoc(String fileName, int pageNum, String sourceUrl,
                                           int sectionIdx, String title, String content, String hash) {
        return ParsedDocument.builder()
                .docId(fileName + "-p" + pageNum + "-sec" + sectionIdx)
                .title(title != null ? title : fileName)
                .content(content)
                .sourceUrl(sourceUrl)
                .pageNumber(pageNum)
                .section("第" + pageNum + "页-" + sectionIdx)
                .contentType("pdf")
                .contentHash(hash)
                .build();
    }

    /** 判断是否为标题行（仅保守匹配首行，避免把编号列表当作标题再次碎片化） */
    private static boolean isHeadingLine(String line) {
        String t = line.trim();
        if (t.isEmpty()) return false;
        // 仅看首行：PDF 标题常与正文同处一个段落块（无空行分隔），需按首行判断
        String first = t.split("\n", 2)[0].trim();
        if (first.matches("^#{1,6}\\s+.*")) return true;                              // Markdown 标题
        if (first.matches("^第[一二三四五六七八九十百千0-9]+[章节条款篇].*")) return true;   // 第X章/节/条/款
        if (first.matches("^[一二三四五六七八九十]+[、．.].*")) return true;              // 一、二、 中文数字标题
        return false;
    }

    /** 估算 token 数（中文 1 字≈1 token，与 RecursiveChunkStrategy 同公式；本地实现避免包循环依赖） */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int han = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) han++;
            else other++;
        }
        return han + (int) Math.ceil(other * 0.4);
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
            TextPosition last = positions.get(positions.size() - 1);
            float x = first.getX();
            float y = first.getY();
            // ⭐ 全行真实宽度 = 末字形右边缘 - 首字形左边缘（原实现误用首字形宽度，
            //   导致列间隙/跨栏判定全部失真）
            float width = Math.max(0, last.getX() + last.getWidth() - x);
            float fontSize = first.getFontSize();
            // ⭐ 栏归属延后判定：先收集，getPageResult 中统计整页是否真的双栏
            cells.add(new Cell(cells.size(), x, y, width, fontSize, text));
        }

        /**
         * ⭐ 判定整页是否为真正的双栏排版：
         * 大量文本行横跨页面中线 → 单栏（绝不能按栏切分重排）；
         * 仅当"跨中线行极少 且 右半页起始的行足够多"才认定为双栏。
         */
        private boolean isTwoColumnLayout() {
            if (cells.isEmpty()) return false;
            double mid = pageWidth / 2.0;
            int crossing = 0;
            int rightStart = 0;
            for (Cell c : cells) {
                if (c.x < mid && c.x + c.width > mid + pageWidth * 0.05) crossing++;
                if (c.x >= mid) rightStart++;
            }
            // 超过 5% 的行跨越中线 → 单栏；双栏还要求右半页起始行占比 ≥ 20%
            if (crossing > Math.max(2, cells.size() * 0.05)) return false;
            return rightStart >= cells.size() * 0.20;
        }

        /** 栏归属：仅在真双栏时按中线切分，单栏页恒为 0 */
        private int colOf(Cell c, boolean twoColumn) {
            return (twoColumn && c.x >= pageWidth / 2.0) ? 1 : 0;
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
            //    ⭐ rowTol 必须小于正文行距（约 1.5 倍字号），只把"同一视觉行"聚在一起。
            //      原实现 medianFont*1.8 ≈ 18.9 > 正文行距 16，相邻正文行被并成"多列行"，
            //      拼出伪表格吞掉正文（第1页文本丢失的根因之一）。同行 cell 的 y 实际完全相同。
            double medianFont = median(cells.stream().map(c -> c.fontSize).sorted().toList());
            double rowTol = Math.max(3, medianFont * 0.5);
            double colTol = Math.max(15, medianFont * 1.5);

            // 2) 按 y 升序排序（PDFTextStripper 的 TextPosition.getY 自页顶向下递增，
            //    升序即自上而下的阅读顺序；原实现按降序误把页面倒序处理，表头跑到表尾）
            List<IndexedCell> sorted = new ArrayList<>();
            for (Cell c : cells) sorted.add(new IndexedCell(c.index, c));
            sorted.sort(Comparator.comparingDouble(ic -> ic.cell.y));

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
            //    ⭐ 收紧判定：排除"编号列表 / 相邻文本伪表格"——要求列间存在真实横向间隙 +
            //       表格横向跨度足够 + 非有序列表（避免单栏中文文档被误判为 pdf-table 绕过 chunking）。
            double minColGap = Math.max(18, medianFont * 1.0);
            double minTableSpan = pageWidth * 0.25;
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

                if (run.size() >= 2
                        && hasRealColumnGaps(run, minColGap)
                        && spansEnough(run, minTableSpan)
                        && !looksLikeOrderedList(run)) {
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

        /** 真实表格判定：相邻列之间必须存在真实横向间隙（排除相邻文本被误判为列） */
        private static boolean hasRealColumnGaps(List<Row> run, double minColGap) {
            for (Row r : run) {
                for (int k = 0; k < r.cells.size() - 1; k++) {
                    Cell cur = r.cells.get(k).cell;
                    Cell nxt = r.cells.get(k + 1).cell;
                    double gap = nxt.x - (cur.x + cur.width);
                    if (gap < minColGap) return false;
                }
            }
            return true;
        }

        /** 真实表格判定：表格整体横向跨度应足够大（排除极小对齐区域） */
        private static boolean spansEnough(List<Row> run, double minSpan) {
            for (Row r : run) {
                if (r.cells.isEmpty()) return false;
                Cell first = r.cells.get(0).cell;
                Cell last = r.cells.get(r.cells.size() - 1).cell;
                double span = (last.x + last.width) - first.x;
                if (span < minSpan) return false;
            }
            return true;
        }

        /** 有序列表判定：两列且首列为项目符号/顺序编号（避免编号列表被误判为表格） */
        private static boolean looksLikeOrderedList(List<Row> run) {
            if (run.isEmpty() || run.get(0).cells.size() != 2) return false;
            List<String> col0 = new ArrayList<>();
            for (Row r : run) col0.add(r.cells.get(0).cell.text.strip());

            // 项目符号列表（所有首列均为 bullet 字符）
            if (col0.stream().allMatch(t -> t.matches("^[-•*·▪◦●○■□◆➢➤]\\s*.*"))) return true;

            // 数字顺序编号（1. 2. 3. ... 严格递增）
            if (col0.stream().allMatch(t -> t.matches("^\\d+[.、）)]\\s*.*"))) {
                List<Integer> nums = new ArrayList<>();
                boolean ok = true;
                for (String t : col0) {
                    try {
                        nums.add(Integer.parseInt(t.replaceAll("[^0-9].*$", "")));
                    } catch (NumberFormatException e) { ok = false; break; }
                }
                if (ok && nums.size() >= 2) {
                    for (int k = 1; k < nums.size(); k++) {
                        if (nums.get(k) != nums.get(k - 1) + 1) { ok = false; break; }
                    }
                    if (ok) return true;
                }
            }

            // 中文数字编号（一、二、三、...）
            if (col0.stream().allMatch(t -> t.matches("^[一二三四五六七八九十]+[.、）)．]\\s*.*"))) {
                return true;
            }
            return false;
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

        /**
         * 排除表格文本块后，重排为正文纯文本。
         * <p>⭐ 修复要点：</p>
         * <ol>
         *   <li>y 自上而下升序排序（原实现按 -y 排序导致整页倒序，标题-正文错配的根因）；</li>
         *   <li>仅在真双栏时按栏切分（原实现逐词按中线切栏，单栏页右半文本被挪到页尾）；</li>
         *   <li>同一视觉行的多个文本块按 x 合并为一行；</li>
         *   <li>按"大字号标题行 / 行距突变"插入空行段落边界，
         *       使下游 split("\\n\\s*\\n") 能切出独立标题行与真实段落。</li>
         * </ol>
         */
        private String buildProse(Set<Integer> excluded) {
            List<Cell> proseCells = new ArrayList<>();
            for (Cell c : cells) {
                if (!excluded.contains(c.index)) proseCells.add(c);
            }
            if (proseCells.isEmpty()) return "";

            boolean twoColumn = isTwoColumnLayout();
            double medianFont = median(proseCells.stream().map(c -> c.fontSize).sorted().toList());
            double rowTol = Math.max(3, medianFont * 0.5);

            // 1) 按 (栏, y 升序, x 升序) 排序 —— 自上而下的阅读顺序
            proseCells.sort(Comparator
                    .comparingInt((Cell c) -> colOf(c, twoColumn))
                    .thenComparingDouble(c -> c.y)
                    .thenComparingDouble(c -> c.x));

            // 2) 聚合"同一视觉行"（同栏且 y 接近），行内按 x 拼接
            List<ProseLine> lines = new ArrayList<>();
            ProseLine current = null;
            for (Cell c : proseCells) {
                int col = colOf(c, twoColumn);
                if (current == null || col != current.col || Math.abs(c.y - current.y) > rowTol) {
                    current = new ProseLine(col, c.y, c.fontSize);
                    lines.add(current);
                } else {
                    current.fontSize = Math.max(current.fontSize, c.fontSize);
                }
                if (current.text.length() > 0) current.text.append(' ');
                current.text.append(c.text.strip());
            }

            // 3) 典型行距（中位数），用于识别"段落间空隙"
            List<Double> gaps = new ArrayList<>();
            for (int k = 1; k < lines.size(); k++) {
                if (lines.get(k).col != lines.get(k - 1).col) continue;
                double g = lines.get(k).y - lines.get(k - 1).y;
                if (g > rowTol) gaps.add(g);
            }
            double medianGap = gaps.isEmpty() ? medianFont * 1.5 : median(gaps.stream().sorted().toList());

            // 4) 输出：标题行（字号明显大于正文）独立成段；行距突变处分段
            StringBuilder sb = new StringBuilder();
            ProseLine prev = null;
            for (ProseLine line : lines) {
                boolean heading = line.fontSize >= medianFont * 1.15
                        && line.fontSize - medianFont >= 1.0;
                if (prev != null) {
                    boolean colChanged = line.col != prev.col;
                    boolean bigGap = !colChanged && (line.y - prev.y) > medianGap * 1.6;
                    boolean prevHeading = prev.fontSize >= medianFont * 1.15
                            && prev.fontSize - medianFont >= 1.0;
                    if (colChanged || bigGap || heading || prevHeading) {
                        sb.append("\n\n");
                    } else {
                        sb.append('\n');
                    }
                }
                sb.append(line.text);
                prev = line;
            }
            return sb.toString();
        }

        /** 聚合后的一行正文（同栏、同视觉行的文本块拼接） */
        private static class ProseLine {
            final int col;
            final double y;
            double fontSize;
            final StringBuilder text = new StringBuilder();
            ProseLine(int col, double y, double fontSize) {
                this.col = col;
                this.y = y;
                this.fontSize = fontSize;
            }
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

    /** 文本块（writeString 收集的最小单元；栏归属由 colOf 延后判定） */
    private record Cell(int index, double x, double y, double width, double fontSize, String text) {}

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
