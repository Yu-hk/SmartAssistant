package com.example.smartassistant.common.rag.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordDocumentParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesTopLevelTableWithoutRecursiveLoop() throws Exception {
        Path file = tempDir.resolve("customer-reference.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("客服知识参考");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("订单号");
            table.getRow(0).getCell(1).setText("状态");
            table.getRow(1).getCell(0).setText("ORD-E2E-0001");
            table.getRow(1).getCell(1).setText("运输中");
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        WordDocumentParser parser = new WordDocumentParser();
        List<ParsedDocument> parsed = assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> parser.parse(file.toString()));

        assertFalse(parsed.isEmpty());
        assertTrue(parsed.stream().anyMatch(doc -> doc.getContent().contains("ORD-E2E-0001")));
    }
}
