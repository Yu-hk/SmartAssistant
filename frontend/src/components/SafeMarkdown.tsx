import type { ReactNode } from 'react';

interface SafeMarkdownProps {
  content: string;
}

const INLINE_TOKEN = /(\[[^\]]+\]\([^)]+\)|`[^`]+`|\*\*[^*]+\*\*|~~[^~]+~~)/g;

function safeHref(rawHref: string): string | null {
  try {
    const url = new URL(rawHref, window.location.origin);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : null;
  } catch {
    return null;
  }
}

function renderInline(text: string, keyPrefix: string): ReactNode[] {
  return text.split(INLINE_TOKEN).filter(Boolean).map((part, index) => {
    const key = `${keyPrefix}-${index}`;
    const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    if (link) {
      const href = safeHref(link[2].trim());
      return href ? (
        <a key={key} href={href} target="_blank" rel="noopener noreferrer">
          {link[1]}
        </a>
      ) : <span key={key}>{link[1]}</span>;
    }
    if (part.startsWith('`') && part.endsWith('`')) {
      return <code key={key}>{part.slice(1, -1)}</code>;
    }
    if (part.startsWith('**') && part.endsWith('**')) {
      return <strong key={key}>{part.slice(2, -2)}</strong>;
    }
    if (part.startsWith('~~') && part.endsWith('~~')) {
      return <del key={key}>{part.slice(2, -2)}</del>;
    }
    return <span key={key}>{part}</span>;
  });
}

function renderMultilineInline(text: string, keyPrefix: string): ReactNode[] {
  return text.split('\n').flatMap((line, index) => [
    ...renderInline(line, `${keyPrefix}-${index}`),
    ...(index < text.split('\n').length - 1 ? [<br key={`${keyPrefix}-br-${index}`} />] : []),
  ]);
}

function splitTableRow(line: string): string[] {
  return line.trim().replace(/^\||\|$/g, '').split('|').map(cell => cell.trim());
}

function isTableDivider(line: string): boolean {
  const cells = splitTableRow(line);
  return cells.length > 0 && cells.every(cell => /^:?-{3,}:?$/.test(cell));
}

function startsBlock(lines: string[], index: number): boolean {
  const line = lines[index] ?? '';
  return !line.trim()
    || /^```/.test(line.trim())
    || /^#{1,6}\s+/.test(line)
    || /^>\s?/.test(line)
    || /^\s*[-*+]\s+/.test(line)
    || /^\s*\d+[.)]\s+/.test(line)
    || /^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)
    || (line.includes('|') && isTableDivider(lines[index + 1] ?? ''));
}

export function SafeMarkdown({ content }: SafeMarkdownProps) {
  const lines = content.replace(/\r\n?/g, '\n').split('\n');
  const blocks: ReactNode[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim()) {
      index += 1;
      continue;
    }

    const fence = line.trim().match(/^```([^\s`]*)/);
    if (fence) {
      const code: string[] = [];
      index += 1;
      while (index < lines.length && !/^```/.test(lines[index].trim())) {
        code.push(lines[index]);
        index += 1;
      }
      if (index < lines.length) index += 1;
      blocks.push(
        <pre key={`code-${index}`} data-language={fence[1] || undefined}>
          <code>{code.join('\n')}</code>
        </pre>,
      );
      continue;
    }

    const heading = line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      const level = heading[1].length;
      const children = renderInline(heading[2], `heading-${index}`);
      if (level === 1) blocks.push(<h1 key={`heading-${index}`}>{children}</h1>);
      else if (level === 2) blocks.push(<h2 key={`heading-${index}`}>{children}</h2>);
      else if (level === 3) blocks.push(<h3 key={`heading-${index}`}>{children}</h3>);
      else blocks.push(<h4 key={`heading-${index}`}>{children}</h4>);
      index += 1;
      continue;
    }

    if (/^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)) {
      blocks.push(<hr key={`rule-${index}`} />);
      index += 1;
      continue;
    }

    if (/^>\s?/.test(line)) {
      const quoted: string[] = [];
      while (index < lines.length && /^>\s?/.test(lines[index])) {
        quoted.push(lines[index].replace(/^>\s?/, ''));
        index += 1;
      }
      blocks.push(<blockquote key={`quote-${index}`}>{renderMultilineInline(quoted.join('\n'), `quote-${index}`)}</blockquote>);
      continue;
    }

    const unordered = /^\s*[-*+]\s+/.test(line);
    const ordered = /^\s*\d+[.)]\s+/.test(line);
    if (unordered || ordered) {
      const items: string[] = [];
      const matcher = unordered ? /^\s*[-*+]\s+(.+)$/ : /^\s*\d+[.)]\s+(.+)$/;
      while (index < lines.length) {
        const item = lines[index].match(matcher);
        if (!item) break;
        items.push(item[1]);
        index += 1;
      }
      const children = items.map((item, itemIndex) => (
        <li key={`item-${index}-${itemIndex}`}>{renderInline(item, `item-${index}-${itemIndex}`)}</li>
      ));
      blocks.push(unordered
        ? <ul key={`list-${index}`}>{children}</ul>
        : <ol key={`list-${index}`}>{children}</ol>);
      continue;
    }

    if (line.includes('|') && isTableDivider(lines[index + 1] ?? '')) {
      const headers = splitTableRow(line);
      const rows: string[][] = [];
      index += 2;
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
        rows.push(splitTableRow(lines[index]));
        index += 1;
      }
      blocks.push(
        <div className="chat-markdown-table" key={`table-${index}`}>
          <table>
            <thead><tr>{headers.map((header, cell) => <th key={`th-${cell}`}>{renderInline(header, `th-${cell}`)}</th>)}</tr></thead>
            <tbody>{rows.map((row, rowIndex) => (
              <tr key={`tr-${rowIndex}`}>{headers.map((_, cell) => <td key={`td-${cell}`}>{renderInline(row[cell] ?? '', `td-${rowIndex}-${cell}`)}</td>)}</tr>
            ))}</tbody>
          </table>
        </div>,
      );
      continue;
    }

    const paragraph: string[] = [line];
    index += 1;
    while (index < lines.length && !startsBlock(lines, index)) {
      paragraph.push(lines[index]);
      index += 1;
    }
    blocks.push(<p key={`paragraph-${index}`}>{renderMultilineInline(paragraph.join('\n'), `paragraph-${index}`)}</p>);
  }

  return <>{blocks}</>;
}
