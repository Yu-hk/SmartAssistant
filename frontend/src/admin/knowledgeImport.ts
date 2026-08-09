import type { AdminFaqImportPayload, AdminFaqPayload } from '../api/admin';

const MAX_FILE_BYTES = 2 * 1024 * 1024;
const MAX_ITEMS = 500;

type ImportSourceType = AdminFaqImportPayload['sourceType'];
type UnknownRecord = Record<string, unknown>;

export interface ParsedKnowledgeFile {
  sourceName: string;
  sourceType: ImportSourceType;
  items: AdminFaqPayload[];
}

function asRecord(value: unknown): UnknownRecord {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as UnknownRecord : {};
}

function asText(value: unknown): string {
  if (Array.isArray(value)) return value.map(asText).filter(Boolean).join(',');
  return typeof value === 'string' || typeof value === 'number' ? String(value).trim() : '';
}

function normalizeEntry(value: unknown, index: number): AdminFaqPayload {
  const row = asRecord(value);
  const question = asText(row.question ?? row.query ?? row.title ?? row.q);
  const answer = asText(row.answer ?? row.content ?? row.text ?? row.a);
  const category = asText(row.category ?? row.type ?? row.domain) || 'general';
  const keywords = asText(row.keywords ?? row.tags ?? row.keyword);

  if (!question || !answer) throw new Error(`第 ${index + 1} 条缺少 question 或 answer`);
  if (question.length > 500) throw new Error(`第 ${index + 1} 条问题超过 500 字`);
  if (answer.length > 20_000) throw new Error(`第 ${index + 1} 条答案超过 20000 字`);
  if (category.length > 50) throw new Error(`第 ${index + 1} 条分类超过 50 字`);
  if (keywords.length > 1_000) throw new Error(`第 ${index + 1} 条关键词超过 1000 字`);
  return { category, question, answer, keywords };
}

function finish(sourceName: string, sourceType: ImportSourceType, rows: unknown[]): ParsedKnowledgeFile {
  if (rows.length === 0) throw new Error('文件中没有可导入的知识条目');
  if (rows.length > MAX_ITEMS) throw new Error(`单次最多导入 ${MAX_ITEMS} 条知识`);
  return { sourceName, sourceType, items: rows.map(normalizeEntry) };
}

function parseJson(sourceName: string, content: string): ParsedKnowledgeFile {
  let value: unknown;
  try {
    value = JSON.parse(content);
  } catch {
    throw new Error('JSON 格式无效，请检查逗号、引号和括号');
  }
  const body = asRecord(value);
  const rows = Array.isArray(value)
    ? value
    : Array.isArray(body.items) ? body.items
      : Array.isArray(body.faqs) ? body.faqs
        : Array.isArray(body.documents) ? body.documents : [];
  return finish(sourceName, 'json', rows);
}

function parseCsvRows(content: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  for (let index = 0; index < content.length; index += 1) {
    const char = content[index];
    if (quoted) {
      if (char === '"' && content[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (char === '"') {
        quoted = false;
      } else {
        field += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ',') {
      row.push(field.trim());
      field = '';
    } else if (char === '\n' || char === '\r') {
      if (char === '\r' && content[index + 1] === '\n') index += 1;
      row.push(field.trim());
      field = '';
      if (row.some(Boolean)) rows.push(row);
      row = [];
    } else {
      field += char;
    }
  }
  if (quoted) throw new Error('CSV 中存在未闭合的引号');
  row.push(field.trim());
  if (row.some(Boolean)) rows.push(row);
  return rows;
}

function parseCsv(sourceName: string, content: string): ParsedKnowledgeFile {
  const rows = parseCsvRows(content.replace(/^\uFEFF/, ''));
  if (rows.length < 2) throw new Error('CSV 至少需要表头和一条数据');
  const headers = rows[0].map(header => header.trim().toLowerCase());
  const data = rows.slice(1).map(cells => Object.fromEntries(
    headers.map((header, index) => [header, cells[index] ?? '']),
  ));
  return finish(sourceName, 'csv', data);
}

function parseMarkdown(sourceName: string, content: string): ParsedKnowledgeFile {
  const heading = /^#{2,3}\s+(.+)$/gm;
  const matches = [...content.matchAll(heading)];
  const rows = matches.map((match, index) => {
    const start = (match.index ?? 0) + match[0].length;
    const end = matches[index + 1]?.index ?? content.length;
    const section = content.slice(start, end).trim();
    const categoryMatch = section.match(/^\s*(?:category|分类)\s*[:：]\s*(.+)$/im);
    const keywordsMatch = section.match(/^\s*(?:keywords?|关键词)\s*[:：]\s*(.+)$/im);
    const answer = section
      .replace(/^\s*(?:category|分类)\s*[:：].+$/gim, '')
      .replace(/^\s*(?:keywords?|关键词)\s*[:：].+$/gim, '')
      .trim();
    return {
      question: match[1].trim(),
      answer,
      category: categoryMatch?.[1].trim() || 'general',
      keywords: keywordsMatch?.[1].trim() || '',
    };
  });
  return finish(sourceName, 'markdown', rows);
}

export async function parseKnowledgeFile(file: File): Promise<ParsedKnowledgeFile> {
  if (file.size > MAX_FILE_BYTES) throw new Error('文件不能超过 2 MB');
  const extension = file.name.split('.').pop()?.toLowerCase();
  const content = await file.text();
  if (extension === 'json') return parseJson(file.name, content);
  if (extension === 'csv') return parseCsv(file.name, content);
  if (extension === 'md' || extension === 'markdown') return parseMarkdown(file.name, content);
  throw new Error('仅支持 JSON、CSV 或 Markdown 文件');
}
