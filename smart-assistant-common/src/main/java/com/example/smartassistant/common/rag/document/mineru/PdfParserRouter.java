/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParseException;
import com.example.smartassistant.common.rag.document.DocumentParser;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.document.PdfDocumentParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

/**
 * PDF 解析路由（R2）：PDFBox 主、MinerU 补。
 * <p>
 * 按文件级预扫描 {@link #needsMinerU(String)} 决策：
 * <ul>
 *   <li><b>数字 PDF</b>（含文本）→ PDFBox（零子进程）。</li>
 *   <li><b>扫描件 / 复杂混排</b>（含图片且任一页文本为空）→ MinerU。</li>
 * </ul>
 * MinerU 路径下结构性不复用 PDFBox（R5 caption 独占：同图不双重 caption）。MinerU 失败且
 * {@code fallbackToPdfbox=true} 时，回退 PDFBox 全链路（含其图片 caption/OCR 兜底）。
 * </p>
 */
public class PdfParserRouter implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfParserRouter.class);

    private final PdfDocumentParser pdfParser;
    private final MinerUDocumentParser minerUParser;
    private final MinerUProperties properties;

    /** 便捷构造：内部构建默认 PDFBox 与 MinerU 解析器 */
    public PdfParserRouter(MinerUClient minerUClient, MinerUProperties properties) {
        this(new PdfDocumentParser(), new MinerUDocumentParser(minerUClient, properties), properties);
    }

    /** 完全可控构造（便于测试注入 spy / 假客户端） */
    public PdfParserRouter(PdfDocumentParser pdfParser, MinerUDocumentParser minerUParser,
                           MinerUProperties properties) {
        this.pdfParser = pdfParser;
        this.minerUParser = minerUParser;
        this.properties = properties;
    }

    @Override
    public List<ParsedDocument> parse(String filePath) throws DocumentParseException {
        String fileName = Paths.get(filePath).getFileName().toString();
        if (needsMinerU(filePath)) {
            log.info("[PdfRouter] 路由到 MinerU（扫描件/复杂混排）: {}", fileName);
            try {
                return minerUParser.parse(filePath);
            } catch (DocumentParseException e) {
                if (properties != null && properties.isFallbackToPdfbox()) {
                    log.warn("[PdfRouter] MinerU 解析失败，回退 PDFBox 全链路（含 caption 兜底）: {}",
                            e.getMessage());
                    return fallbackToPdfBox(filePath);
                }
                throw e;
            }
        }
        log.info("[PdfRouter] 路由到 PDFBox（数字 PDF，零子进程）: {}", fileName);
        return pdfParser.parse(filePath);
    }

    /** 回退路径：完整 PDFBox（含图片 caption / OCR 兜底）。复用注入的 pdfParser（默认 caption 开启）。 */
    private List<ParsedDocument> fallbackToPdfBox(String filePath) throws DocumentParseException {
        return pdfParser.parse(filePath);
    }

    /**
     * v1 文件级预扫描：含图片且存在任一扫描页（无文本）→ 需要 MinerU。
     * 数字 PDF（有文本）走 PDFBox；扫描件/复杂混排走 MinerU。
     *
     * @param filePath PDF 绝对路径
     * @return true 表示应路由到 MinerU
     */
    public boolean needsMinerU(String filePath) {
        try (PDDocument document = Loader.loadPDF(Paths.get(filePath).toFile())) {
            int totalPages = document.getNumberOfPages();
            boolean anyImage = false;
            for (int i = 1; i <= totalPages; i++) {
                if (PdfDocumentParser.hasImages(document.getPage(i - 1))) {
                    anyImage = true;
                    break;
                }
            }
            if (!anyImage) {
                // 数字 PDF 无图，必走 PDFBox
                return false;
            }
            // 含图片：任一页为扫描件（无文本）→ 需 MinerU
            for (int i = 1; i <= totalPages; i++) {
                if (PdfDocumentParser.isScannedPage(document, i)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            log.warn("[PdfRouter] 预扫描失败，保守走 PDFBox: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 保留既有静态工具（避免误用） ====================
    // 注：hasImages / isScannedPage 已提升为 PdfDocumentParser 的 public static 方法复用。
}
