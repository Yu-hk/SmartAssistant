import { useEffect, useId, useRef, useState } from 'react';
import { Download, FileText, Upload } from 'lucide-react';
import { DOCUMENT_EXAMPLES, documentExampleUrl } from '../config/documentExamples';
import { buildDocumentQuestion, readDocumentFile, MAX_DOCUMENT_BYTES } from '../utils/documentImport';

export function DocumentExamples({ disabled = false, hasDraft = false, onSelect }: {
  disabled?: boolean; hasDraft?: boolean; onSelect: (message: string) => void;
}) {
  const id = useId();
  const [document, setDocument] = useState<{ name: string; content: string } | null>(null);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [replaceDraft, setReplaceDraft] = useState(false);
  const version = useRef(0);
  useEffect(() => { version.current++; setBusy(false); }, [disabled]);
  useEffect(() => () => { version.current++; }, []);
  const locked = disabled || busy;

  const load = async (file: Pick<File, 'name' | 'size' | 'arrayBuffer'>, exampleQuestion?: string) => {
    if (disabled) return;
    const current = ++version.current;
    setBusy(true); setError(''); setNotice('');
    try {
      const content = await readDocumentFile(file);
      if (current !== version.current) return;
      setDocument({ name: file.name, content });
      setQuestion(exampleQuestion || '请概括这份资料的主要内容。');
      setReplaceDraft(false);
      setNotice('正文已在本地读取，尚未发送。请预览并确认问题。');
    } catch (failure) {
      if (current === version.current) setError(failure instanceof Error ? failure.message : '读取失败，请重试。');
    } finally { if (current === version.current) setBusy(false); }
  };

  const loadExample = async (example: typeof DOCUMENT_EXAMPLES[number]) => {
    if (locked) return;
    const current = ++version.current;
    setBusy(true); setError(''); setNotice('');
    try {
      const response = await fetch(documentExampleUrl(example.fileName));
      if (!response.ok) throw new Error('示例下载失败，请重试。');
      if (response.headers.get('content-type')?.includes('text/html')) throw new Error('示例文件未正确发布，请联系管理员。');
      const blob = await response.blob();
      if (blob.size > MAX_DOCUMENT_BYTES) throw new Error('示例文件大小异常。');
      if (current !== version.current) return;
      await load(new File([blob], example.fileName), example.question);
    } catch (failure) {
      if (current === version.current) { setError(failure instanceof Error ? failure.message : '读取失败。'); setBusy(false); }
    }
  };

  return <section className="document-examples" aria-labelledby={`${id}-title`}>
    <div className="home-section-heading"><h2 id={`${id}-title`}><FileText size={18} /> 文档导入验证</h2><small>示例均为虚构测试资料</small></div>
    <p>先下载示例再导入，或直接载入示例。预览正文后填入提问，确认发送才提交给服务端；不会写入公共知识库。</p>
    <div className="document-example-grid">
      {DOCUMENT_EXAMPLES.map(example => <article key={example.id}>
        <h3>{example.title}</h3><p>{example.description}</p>
        <p className="document-example-question">试着问：{example.question}</p>
        <div className="document-example-actions">
          <a href={documentExampleUrl(example.fileName)} download={example.fileName}><Download size={14} /> 下载 {example.fileName.endsWith('.txt') ? 'TXT' : 'Markdown'}</a>
          <button type="button" disabled={locked} onClick={() => void loadExample(example)}>载入示例</button>
        </div>
        <details><summary>查看验证参考</summary><p>{example.expected}</p><p>边界测试：{example.missingQuestion} 应说明资料未提及，不要编造。</p></details>
      </article>)}
    </div>
    <label className="document-file-label" htmlFor={`${id}-file`}><Upload size={16} /> 导入本地文档</label>
    <input id={`${id}-file`} type="file" accept=".txt,.md,text/plain,text/markdown" disabled={locked}
      aria-describedby={`${id}-limits`} onChange={event => {
        const file = event.target.files?.[0]; event.target.value = '';
        if (file && !locked) void load(file);
      }} />
    <p id={`${id}-limits`}>UTF-8 编码 · TXT / Markdown · 最多20 KB、6000字。不支持 PDF、Word；请勿导入敏感资料。</p>
    {busy && <p role="status">正在读取文档…</p>}
    {error && <p className="document-error" role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {document && <div className="document-import-preview">
      <strong>{document.name} · {document.content.length} 字</strong>
      <details><summary>预览正文</summary><pre>{document.content}</pre></details>
      <label htmlFor={`${id}-question`}>向这份资料提问</label>
      <textarea id={`${id}-question`} value={question} maxLength={300} disabled={locked}
        onChange={event => setQuestion(event.target.value)} rows={2} />
      {hasDraft && <label className="document-replace"><input type="checkbox" checked={replaceDraft} disabled={locked}
        onChange={event => setReplaceDraft(event.target.checked)} /> 我确认替换上方输入框中的现有草稿</label>}
      <button type="button" disabled={locked || !question.trim() || (hasDraft && !replaceDraft)} onClick={() => {
        if (locked || (hasDraft && !replaceDraft)) return;
        try { onSelect(buildDocumentQuestion(document.content, question)); setError(''); setReplaceDraft(false); setNotice('资料和问题已填入上方输入框，确认后点击发送。'); }
        catch (failure) { setError(failure instanceof Error ? failure.message : '无法填入问题。'); }
      }}>将资料和问题填入输入框</button>
    </div>}
  </section>;
}
