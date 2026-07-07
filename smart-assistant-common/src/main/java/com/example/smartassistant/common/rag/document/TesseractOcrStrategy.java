/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于系统 Tesseract OCR 引擎的真实 {@link OcrStrategy} 实现。
 * <p>
 * 通过 {@link ProcessBuilder} 调用系统 {@code tesseract} 命令行完成 OCR，
 * 不引入额外的 Java 原生依赖（如 tess4j），部署侧只需在镜像/主机安装 Tesseract 即可启用。
 * </p>
 *
 * <p>激活条件：构造时探测 {@code tesseract --version} 是否可成功执行；
 * 仅当可执行文件存在于 PATH 时 {@link #isAvailable()} 返回 true，否则应降级为
 * {@link NoopOcrStrategy}（由 {@link OcrStrategies#autoDetect()} 自动选择）。</p>
 *
 * <p>中文文档可注入语言参数（如 {@code "chi_sim+eng"}），但需对应 traineddata 已安装。</p>
 */
public class TesseractOcrStrategy implements OcrStrategy {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrStrategy.class);

    private final boolean available;
    private final String languages;

    public TesseractOcrStrategy() {
        this("eng");
    }

    public TesseractOcrStrategy(String languages) {
        this.languages = (languages != null && !languages.isBlank()) ? languages : "eng";
        this.available = detect();
    }

    private static boolean detect() {
        try {
            Process p = new ProcessBuilder("tesseract", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean ok = p.waitFor() == 0;
            if (!ok) {
                log.debug("[TesseractOcr] tesseract --version 退出码非 0，视为不可用");
            }
            return ok;
        } catch (IOException e) {
            log.debug("[TesseractOcr] 未找到 tesseract 可执行文件: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public List<String> extractText(byte[] imageData, String fileName) {
        if (!available || imageData == null || imageData.length == 0) {
            return List.of();
        }
        Path input = null;
        Path outBase = null;
        try {
            input = Files.createTempFile("ocr-in-", ".png");
            Files.write(input, imageData);
            outBase = Files.createTempFile("ocr-out-", "");
            // 先删除可能残留的 .txt（tesseract 会在 outBase 后追加 .txt）
            Path txtOut = Path.of(outBase.toString() + ".txt");
            Files.deleteIfExists(txtOut);

            List<String> cmd = new ArrayList<>();
            cmd.add("tesseract");
            cmd.add(input.toString());
            cmd.add(outBase.toString());
            cmd.add("-l");
            cmd.add(languages);
            cmd.add("txt"); // 输出格式：plain text

            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            int code = p.waitFor();
            if (code != 0) {
                log.warn("[TesseractOcr] OCR 失败: file={}, exitCode={}", fileName, code);
                return List.of();
            }
            if (!Files.exists(txtOut)) {
                log.warn("[TesseractOcr] OCR 未生成输出文件: file={}", fileName);
                return List.of();
            }
            String text = Files.readString(txtOut, StandardCharsets.UTF_8).strip();
            return text.isBlank() ? List.of() : List.of(text);
        } catch (IOException e) {
            log.warn("[TesseractOcr] 提取异常: file={}, error={}", fileName, e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[TesseractOcr] 提取被中断: file={}", fileName);
            return List.of();
        } finally {
            safeDelete(input);
            safeDelete(outBase);
            if (outBase != null) safeDelete(Path.of(outBase.toString() + ".txt"));
        }
    }

    private static void safeDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主流程
        }
    }
}
