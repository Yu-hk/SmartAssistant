/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import java.util.List;

/**
 * 文档解析器接口——将不同格式的源文档解析为统一的 {@link ParsedDocument} 列表。
 * <p>
 * 参考 RAG 文章的最佳实践：对每种文档类型使用专门的解析策略，
 * 保留页号、章节、来源等元数据供后续权限过滤/引用溯源。
 * </p>
 */
@FunctionalInterface
public interface DocumentParser {

    /**
     * 解析源文档，返回解析后的文档元素列表。
     * <p>
     * 输入文件可能包含多个逻辑段落/章节，每个段落输出为一个 {@link ParsedDocument}。
     * 调用者应在解析后对输出进行 Chunking（语义分块或递归分块）。
     * </p>
     *
     * @param filePath 文档的绝对路径
     * @return 解析出的文档元素列表，不会为 null
     * @throws DocumentParseException 解析失败时抛出
     */
    List<ParsedDocument> parse(String filePath) throws DocumentParseException;
}
