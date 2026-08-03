/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 * Licensed under the MIT License.
 */

package com.example.smartassistant.common.rag.document;

import com.example.smartassistant.common.rag.ingestion.DocumentMetadataEnricher;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfParserInterferenceQualityTest {

    @TempDir
    Path tempDir;

    @Test
    void repeatedHeadersAndFootersAreRemovedButBodyIsPreserved() throws Exception {
        Path pdf = tempDir.resolve("repeated-margins.pdf");
        try (PDDocument document = new PDDocument()) {
            for (int pageNumber = 1; pageNumber <= 3; pageNumber++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    writeText(stream, "SMART ASSISTANT KNOWLEDGE BASE", 50, 760);
                    writeText(stream, "Shared body sentence must remain.", 50, 680);
                    writeText(stream, "Unique body marker " + pageNumber, 50, 640);
                    writeText(stream, "Confidential - Page " + pageNumber, 50, 30);
                }
            }
            document.save(pdf.toFile());
        }

        List<ParsedDocument> docs = parserWithoutImageEngines().parse(pdf.toString());
        String allText = docs.stream().map(ParsedDocument::getContent)
                .reduce("", (left, right) -> left + "\n" + right);

        assertFalse(allText.contains("SMART ASSISTANT KNOWLEDGE BASE"));
        assertFalse(allText.contains("Confidential - Page"));
        assertEquals(3, countOccurrences(allText, "Shared body sentence must remain."));
        assertTrue(docs.stream().allMatch(d -> "2".equals(
                d.getMetadata().get("pdf.headerFooterFiltered"))));
    }

    @Test
    void singlePageMarginTextIsNotRemoved() throws Exception {
        Path pdf = tempDir.resolve("single-page.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                writeText(stream, "A legitimate title in the top margin", 50, 760);
                writeText(stream, "Body text", 50, 680);
            }
            document.save(pdf.toFile());
        }

        String allText = parserWithoutImageEngines().parse(pdf.toString()).stream()
                .map(ParsedDocument::getContent).reduce("", (left, right) -> left + right);
        assertTrue(allText.contains("A legitimate title in the top margin"));
    }

    @Test
    void textPageWithImageAndNoOcrCarriesVisibleQualityWarning() throws Exception {
        Path pdf = buildImagePdf("Body text remains searchable.");

        List<ParsedDocument> docs = parserWithoutImageEngines().parse(pdf.toString());
        ParsedDocument prose = docs.stream().filter(d -> "pdf".equals(d.getContentType()))
                .findFirst().orElseThrow();

        assertTrue(prose.getContent().contains("Body text remains searchable."));
        assertEquals("1", prose.getMetadata().get("pdf.image"));
        assertEquals("unavailable", prose.getMetadata().get("pdf.imageTextStatus"));
        assertEquals("0", prose.getMetadata().get("pdf.parseComplete"));
        assertEquals("IMAGE_TEXT_UNAVAILABLE", prose.getMetadata().get("pdf.parseWarning"));

        ParsedDocument enriched = new DocumentMetadataEnricher().enrich(prose);
        assertEquals(prose.getMetadata(), enriched.getMetadata(),
                "quality metadata must survive enrichment");
    }

    @Test
    void imageOnlyPageWithoutOcrFailsInsteadOfSilentlyReturningEmpty() throws Exception {
        Path pdf = buildImagePdf("");
        DocumentParseException error = assertThrows(DocumentParseException.class,
                () -> parserWithoutImageEngines().parse(pdf.toString()));
        assertTrue(error.getMessage().contains("image-only"));
        assertTrue(error.getMessage().contains("OCR is unavailable"));
    }

    private PdfDocumentParser parserWithoutImageEngines() {
        PdfDocumentParser parser = new PdfDocumentParser();
        parser.setOcrStrategy(new NoopOcrStrategy());
        parser.setImageCaptionStrategy(new NoopImageCaptionStrategy());
        return parser;
    }

    private Path buildImagePdf(String body) throws Exception {
        Path pdf = tempDir.resolve(body.isBlank() ? "image-only.pdf" : "text-with-image.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                if (!body.isBlank()) writeText(stream, body, 50, 700);
                BufferedImage image = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setColor(Color.BLACK);
                graphics.drawString("IMAGE TEXT", 8, 30);
                graphics.dispose();
                PDImageXObject imageObject = LosslessFactory.createFromImage(document, image);
                stream.drawImage(imageObject, 50, 520);
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private static void writeText(PDPageContentStream stream, String text, float x, float y)
            throws Exception {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
