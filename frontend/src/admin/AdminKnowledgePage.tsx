import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { BookOpenText, Edit3, FileText, Plus, Search, Tag, Trash2, Upload, X } from 'lucide-react';
import * as adminApi from '../api/admin';
import type { FaqItem } from '../types';
import {
  formatDateTime,
  formatKnowledgeCategory,
  getErrorMessage,
  KNOWLEDGE_CATEGORIES,
} from './adminFormat';
import { AdminEmptyState, AdminErrorState, AdminLoadingState, AdminPageIntro } from './AdminState';
import { parseKnowledgeFile, type ParsedKnowledgeFile } from './knowledgeImport';

interface KnowledgeForm {
  category: string;
  question: string;
  answer: string;
  keywords: string;
}

const EMPTY_FORM: KnowledgeForm = { category: 'general', question: '', answer: '', keywords: '' };

export function AdminKnowledgePage({ refreshVersion }: { refreshVersion: number }) {
  const [items, setItems] = useState<FaqItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [mutationError, setMutationError] = useState('');
  const [notice, setNotice] = useState('');
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('');
  const [editorOpen, setEditorOpen] = useState(false);
  const [importerOpen, setImporterOpen] = useState(false);
  const [editingItem, setEditingItem] = useState<FaqItem | null>(null);

  const loadItems = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setItems(await adminApi.fetchAdminFaqs());
    } catch (loadError) {
      setError(getErrorMessage(loadError, '无法获取知识库内容'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadItems(); }, [loadItems, refreshVersion]);
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(''), 3000);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const filteredItems = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    return items.filter(item => {
      if (category && item.category !== category) return false;
      if (!keyword) return true;
      return `${item.question} ${item.answer} ${item.keywords} ${item.sourceName || ''}`
        .toLowerCase().includes(keyword);
    });
  }, [category, items, query]);

  const openCreate = () => {
    setEditingItem(null);
    setMutationError('');
    setEditorOpen(true);
  };

  const openEdit = (item: FaqItem) => {
    setEditingItem(item);
    setMutationError('');
    setEditorOpen(true);
  };

  const deleteItem = async (item: FaqItem) => {
    if (!window.confirm(`确认删除知识条目“${item.question}”？此操作无法撤销。`)) return;
    setMutationError('');
    try {
      await adminApi.deleteAdminFaq(item.id);
      setItems(current => current.filter(candidate => candidate.id !== item.id));
      setNotice('知识条目已删除');
    } catch (deleteError) {
      setMutationError(getErrorMessage(deleteError, '删除失败，请稍后重试'));
    }
  };

  return (
    <div className="admin-page admin-knowledge-page">
      <AdminPageIntro
        eyebrow="KNOWLEDGE BASE"
        title="知识库"
        description="维护用户可检索的标准问答，支持从外部 JSON、CSV、Markdown 文件批量导入。"
        actions={(
          <div className="admin-page-actions">
            <button type="button" className="admin-button secondary" onClick={() => setImporterOpen(true)}><Upload size={16} /> 导入知识库</button>
            <button type="button" className="admin-button primary" onClick={openCreate}><Plus size={16} /> 新建知识</button>
          </div>
        )}
      />

      {(notice || mutationError) && (
        <div className={`admin-notice ${mutationError ? 'is-error' : 'is-success'}`} role={mutationError ? 'alert' : 'status'}>
          {mutationError || notice}
          <button type="button" aria-label="关闭提示" onClick={() => { setNotice(''); setMutationError(''); }}><X size={15} /></button>
        </div>
      )}

      <div className="admin-knowledge-toolbar">
        <label className="admin-search-field">
          <span className="sr-only">搜索知识库</span><Search size={16} />
          <input value={query} onChange={event => setQuery(event.target.value)} placeholder="搜索问题、答案或关键词" />
        </label>
        <label className="admin-filter-field">
          <span>知识领域</span>
          <select value={category} onChange={event => setCategory(event.target.value)}>
            <option value="">全部领域</option>
            {KNOWLEDGE_CATEGORIES.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
        <span className="admin-result-count">显示 {filteredItems.length} / {items.length} 条</span>
      </div>

      {loading && items.length === 0 ? (
        <AdminLoadingState label="正在加载知识库…" />
      ) : error ? (
        <AdminErrorState message={error} onRetry={() => void loadItems()} />
      ) : filteredItems.length === 0 ? (
        <AdminEmptyState
          title={items.length === 0 ? '知识库还没有内容' : '没有匹配的知识条目'}
          description={items.length === 0 ? '创建第一条标准问答，让智能助手快速命中准确内容。' : '请调整搜索词或知识领域。'}
          action={items.length === 0
            ? <button type="button" className="admin-button primary" onClick={openCreate}><Plus size={15} /> 新建知识</button>
            : <button type="button" className="admin-button secondary" onClick={() => { setQuery(''); setCategory(''); }}>清除筛选</button>}
        />
      ) : (
        <section className="admin-knowledge-grid" aria-label="知识条目列表">
          {filteredItems.map(item => (
            <article className="admin-knowledge-card" key={item.id}>
              <div className="admin-knowledge-card-top">
                <span className="admin-category-tag"><BookOpenText size={13} /> {formatKnowledgeCategory(item.category)}</span>
                <span className="admin-hit-count">被引用 {(item.hitCount ?? item.hit_count ?? 0).toLocaleString('zh-CN')} 次</span>
              </div>
              <h2>{item.question}</h2>
              <p>{item.answer}</p>
              {item.keywords && (
                <div className="admin-keyword-list" aria-label="关键词">
                  {item.keywords.split(/[,，]/).map(word => word.trim()).filter(Boolean).slice(0, 6).map(word => (
                    <span key={word}><Tag size={11} /> {word}</span>
                  ))}
                </div>
              )}
              <div className="admin-knowledge-source">
                <FileText size={12} aria-hidden="true" />
                {item.sourceType !== 'manual' && item.sourceName
                  ? `外部来源：${item.sourceName}`
                  : '后台手工维护'}
              </div>
              <footer>
                <time dateTime={item.updatedAt || item.updated_at}>{formatDateTime(item.updatedAt || item.updated_at)}</time>
                <div>
                  <button type="button" className="admin-icon-button" aria-label={`编辑：${item.question}`} title="编辑" onClick={() => openEdit(item)}><Edit3 size={15} /></button>
                  <button type="button" className="admin-icon-button danger" aria-label={`删除：${item.question}`} title="删除" onClick={() => void deleteItem(item)}><Trash2 size={15} /></button>
                </div>
              </footer>
            </article>
          ))}
        </section>
      )}

      {editorOpen && (
        <KnowledgeEditor
          item={editingItem}
          onClose={() => setEditorOpen(false)}
          onError={setMutationError}
          onSaved={saved => {
            setItems(current => editingItem
              ? current.map(item => item.id === saved.id ? saved : item)
              : [saved, ...current]);
            setEditorOpen(false);
            setNotice(editingItem ? '知识条目已更新' : '知识条目已创建');
          }}
        />
      )}
      {importerOpen && (
        <KnowledgeImporter
          onClose={() => setImporterOpen(false)}
          onImported={async result => {
            await loadItems();
            setImporterOpen(false);
            setNotice(`导入完成：新增 ${result.created} 条，更新 ${result.updated} 条，跳过 ${result.skipped} 条`);
          }}
        />
      )}
    </div>
  );
}

function KnowledgeImporter({
  onClose,
  onImported,
}: {
  onClose: () => void;
  onImported: (result: adminApi.AdminFaqImportResult) => Promise<void>;
}) {
  const [parsed, setParsed] = useState<ParsedKnowledgeFile | null>(null);
  const [overwrite, setOverwrite] = useState(false);
  const [reading, setReading] = useState(false);
  const [importing, setImporting] = useState(false);
  const [error, setError] = useState('');
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeRef.current?.focus();
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !reading && !importing) onClose();
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [importing, onClose, reading]);

  const chooseFile = async (file: File | undefined) => {
    if (!file) return;
    setReading(true);
    setError('');
    setParsed(null);
    try {
      setParsed(await parseKnowledgeFile(file));
    } catch (parseError) {
      setError(getErrorMessage(parseError, '无法解析知识库文件'));
    } finally {
      setReading(false);
    }
  };

  const importItems = async () => {
    if (!parsed) return;
    setImporting(true);
    setError('');
    try {
      await onImported(await adminApi.importAdminFaqs({
        sourceName: parsed.sourceName,
        sourceType: parsed.sourceType,
        overwrite,
        items: parsed.items,
      }));
    } catch (importError) {
      setError(getErrorMessage(importError, '导入失败，请检查文件内容后重试'));
      setImporting(false);
    }
  };

  return (
    <div className="admin-modal-backdrop" role="presentation" onMouseDown={() => { if (!reading && !importing) onClose(); }}>
      <section className="admin-knowledge-editor admin-knowledge-importer" role="dialog" aria-modal="true" aria-labelledby="knowledge-import-title" onMouseDown={event => event.stopPropagation()}>
        <header>
          <div><span className="admin-eyebrow">EXTERNAL KNOWLEDGE</span><h2 id="knowledge-import-title">导入外部知识库</h2></div>
          <button ref={closeRef} type="button" className="admin-icon-button" aria-label="关闭导入窗口" disabled={reading || importing} onClick={onClose}><X size={18} /></button>
        </header>
        <div className="admin-import-body">
          <label className="admin-import-dropzone">
            <Upload size={24} aria-hidden="true" />
            <strong>{reading ? '正在解析文件…' : '选择知识库文件'}</strong>
            <span>支持 JSON、CSV、Markdown，单个文件不超过 2 MB、500 条</span>
            <input type="file" accept=".json,.csv,.md,.markdown,application/json,text/csv,text/markdown" disabled={reading || importing} onChange={event => void chooseFile(event.target.files?.[0])} />
          </label>

          <div className="admin-import-help">
            <strong>字段说明</strong>
            <p>JSON/CSV 使用 question、answer、category、keywords 字段；Markdown 用二级或三级标题作为问题，标题下正文作为答案，可选填写“分类：”和“关键词：”。</p>
          </div>

          {parsed && (
            <section className="admin-import-preview" aria-label="导入预览">
              <div>
                <span><FileText size={14} /> {parsed.sourceName}</span>
                <strong>{parsed.items.length} 条知识</strong>
              </div>
              <ul>
                {parsed.items.slice(0, 5).map((item, index) => (
                  <li key={`${item.question}-${index}`}><span>{item.category}</span><strong>{item.question}</strong></li>
                ))}
              </ul>
              {parsed.items.length > 5 && <small>另外还有 {parsed.items.length - 5} 条，将在确认后一起导入。</small>}
            </section>
          )}

          <label className="admin-import-option">
            <input type="checkbox" checked={overwrite} disabled={importing} onChange={event => setOverwrite(event.target.checked)} />
            <span><strong>覆盖同名知识</strong><small>开启后更新已有问题的答案、分类、关键词和来源；关闭时跳过重复项。</small></span>
          </label>

          {error && <div className="admin-inline-error" role="alert">{error}</div>}
          <footer>
            <button type="button" className="admin-button secondary" disabled={reading || importing} onClick={onClose}>取消</button>
            <button type="button" className="admin-button primary" disabled={!parsed || reading || importing} onClick={() => void importItems()}>{importing ? '正在导入…' : '确认导入'}</button>
          </footer>
        </div>
      </section>
    </div>
  );
}

function KnowledgeEditor({
  item,
  onClose,
  onSaved,
  onError,
}: {
  item: FaqItem | null;
  onClose: () => void;
  onSaved: (item: FaqItem) => void;
  onError: (message: string) => void;
}) {
  const [form, setForm] = useState<KnowledgeForm>(() => item ? {
    category: item.category,
    question: item.question,
    answer: item.answer,
    keywords: item.keywords,
  } : EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeRef.current?.focus();
    const handleKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && !saving) onClose(); };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [onClose, saving]);

  const save = async (event: FormEvent) => {
    event.preventDefault();
    const normalized = {
      category: form.category,
      question: form.question.trim(),
      answer: form.answer.trim(),
      keywords: form.keywords.trim(),
    };
    if (!normalized.question || !normalized.answer) {
      setFormError('请完整填写标准问题和标准答案');
      return;
    }
    setSaving(true);
    setFormError('');
    try {
      const saved = item
        ? await adminApi.updateAdminFaq(item.id, normalized)
        : await adminApi.createAdminFaq(normalized);
      onSaved(saved);
    } catch (saveError) {
      const message = getErrorMessage(saveError, '保存失败，请稍后重试');
      setFormError(message);
      onError(message);
      setSaving(false);
    }
  };

  return (
    <div className="admin-modal-backdrop" role="presentation" onMouseDown={() => { if (!saving) onClose(); }}>
      <section className="admin-knowledge-editor" role="dialog" aria-modal="true" aria-labelledby="knowledge-editor-title" onMouseDown={event => event.stopPropagation()}>
        <header>
          <div><span className="admin-eyebrow">KNOWLEDGE EDITOR</span><h2 id="knowledge-editor-title">{item ? '编辑知识条目' : '新建知识条目'}</h2></div>
          <button ref={closeRef} type="button" className="admin-icon-button" aria-label="关闭编辑器" disabled={saving} onClick={onClose}><X size={18} /></button>
        </header>
        <form onSubmit={save}>
          <label className="admin-form-field">
            <span>知识领域</span>
            <select value={form.category} onChange={event => setForm(current => ({ ...current, category: event.target.value }))}>
              {KNOWLEDGE_CATEGORIES.map(category => <option key={category.value} value={category.value}>{category.label}</option>)}
            </select>
          </label>
          <label className="admin-form-field">
            <span>标准问题 <b aria-hidden="true">*</b></span>
            <input value={form.question} maxLength={200} onChange={event => setForm(current => ({ ...current, question: event.target.value }))} placeholder="例如：如何查询订单物流？" required />
            <small>{form.question.length} / 200</small>
          </label>
          <label className="admin-form-field">
            <span>标准答案 <b aria-hidden="true">*</b></span>
            <textarea value={form.answer} rows={8} maxLength={2000} onChange={event => setForm(current => ({ ...current, answer: event.target.value }))} placeholder="填写准确、完整且可直接面向用户的答案" required />
            <small>{form.answer.length} / 2000</small>
          </label>
          <label className="admin-form-field">
            <span>关键词</span>
            <input value={form.keywords} maxLength={300} onChange={event => setForm(current => ({ ...current, keywords: event.target.value }))} placeholder="多个关键词请用逗号分隔" />
            <small>用于提高知识检索命中率</small>
          </label>
          {formError && <div className="admin-inline-error" role="alert">{formError}</div>}
          <footer>
            <button type="button" className="admin-button secondary" disabled={saving} onClick={onClose}>取消</button>
            <button type="submit" className="admin-button primary" disabled={saving}>{saving ? '正在保存…' : item ? '保存修改' : '创建条目'}</button>
          </footer>
        </form>
      </section>
    </div>
  );
}
