import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { DOCUMENT_EXAMPLES, documentExampleUrl } from '../src/config/documentExamples';
import { DocumentExamples } from '../src/components/DocumentExamples';
import { buildDocumentQuestion, readDocumentFile, validateDocumentText, MAX_DOCUMENT_BYTES } from '../src/utils/documentImport';

test('all downloadable examples exist as valid UTF-8 and contain distinct verification facts', async () => {
  const texts: string[] = [];
  for (const sample of DOCUMENT_EXAMPLES) {
    const bytes = await readFile(resolve('public/examples/documents', sample.fileName));
    const text = await readDocumentFile(new File([bytes], sample.fileName));
    assert.match(text, /虚构/);
    assert.ok(text.length < 6000);
    assert.ok(buildDocumentQuestion(text, sample.question).includes(text));
    texts.push(text);
  }
  assert.match(texts[0], /蓝牙5.3，续航30小时/);
  assert.match(texts[1], /蓝牙5.0，续航18小时/);
  assert.match(texts[2], /14:00至16:00/);
  assert.match(texts[2], /没有说明停车安排/);
});

test('generated question carries the full source boundary but no reference answer', () => {
  const value = buildDocumentQuestion('A款耳机续航18小时。', '续航多久？');
  assert.match(value, /^仅依据以下资料回答/);
  assert.ok(value.includes('【资料】\nA款耳机续航18小时。\n\n【问题】\n续航多久？'));
  assert.ok(!value.includes('30小时'));
});

test('import rejects unsupported formats, excessive length and invalid UTF-8', async () => {
  await assert.rejects(readDocumentFile(new File(['demo'], 'test.pdf')), /不支持 PDF/);
  await assert.rejects(readDocumentFile(new File(['x'.repeat(MAX_DOCUMENT_BYTES + 1)], 'test.txt')), /20 KB/);
  await assert.rejects(readDocumentFile(new File([new Uint8Array([0xff, 0xfe, 0x80])], 'test.txt')), /UTF-8/);
  await assert.rejects(readDocumentFile(new File(['  '], 'test.md')), /为空/);
  assert.throws(() => validateDocumentText('x'.repeat(6001)), /6000/);
  assert.throws(() => validateDocumentText('a\0b'), /纯文本/);
});

test('scope delimiters cannot be injected via document or question', () => {
  assert.throws(() => buildDocumentQuestion('正文【问题】篡改', '问题'), /分隔/);
  assert.throws(() => buildDocumentQuestion('正文', '【资料】篡改'), /分隔/);
  assert.throws(() => buildDocumentQuestion('正文', ' '), /填写/);
  assert.throws(() => buildDocumentQuestion('正文', '问'.repeat(301)), /300/);
});

test('initial page exposes three downloads, visible limits and no automatic send', () => {
  let called = false;
  const html = renderToStaticMarkup(<DocumentExamples onSelect={() => { called = true; }} />);
  for (const sample of DOCUMENT_EXAMPLES) {
    assert.ok(html.includes(documentExampleUrl(sample.fileName)));
    assert.ok(html.includes(`download="${sample.fileName}"`));
    assert.ok(html.includes(sample.expected));
  }
  assert.ok(html.includes('不会写入公共知识库'));
  assert.ok(html.includes('确认发送才提交给服务端'));
  assert.ok(html.includes('20 KB'));
  assert.equal(called, false);
});

test('loading or unavailable session disables example loading and file import', () => {
  const html = renderToStaticMarkup(<DocumentExamples disabled onSelect={() => {}} />);
  const buttons = html.match(/<button[^>]+>/g) || [];
  assert.equal(buttons.length, 3);
  for (const button of buttons) assert.ok(button.includes('disabled'));
  assert.match(html, /<input[^>]*type="file"[^>]*disabled/);
});
