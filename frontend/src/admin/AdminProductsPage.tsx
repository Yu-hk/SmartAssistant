import { useEffect, useRef, useState, type FormEvent } from 'react';
import { createAdminProduct, extractProductFeatures, type ProductExtraction } from '../api/adminProducts';
import { AdminPageIntro } from './AdminState';
import { getErrorMessage } from './adminFormat';
import { featureDraft, featureValues, hasKnownFeatures, type FeatureDraft } from './productIntake';
import './productIntake.css';

const EMPTY_FORM = { productCode: '', productName: '', category: '', price: '', stock: '缺货', description: '', spec: '', color: '' };
const EMPTY_FEATURES: FeatureDraft = { weightGrams: '', batteryLifeHours: '', batteryLifeScenario: '', noiseCancelling: 'unknown' };

/** Separate administrator intake, with extraction before the explicit product commit. */
export function AdminProductsPage() {
  const [form, setForm] = useState(EMPTY_FORM);
  const [preview, setPreview] = useState<ProductExtraction | null>(null);
  const [draft, setDraft] = useState(EMPTY_FEATURES);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState<'extract' | 'save' | null>(null);
  const [error, setError] = useState('');
  const [savedCode, setSavedCode] = useState('');
  const generation = useRef(0);
  useEffect(() => () => { generation.current += 1; }, []);

  const update = (key: keyof typeof form, value: string) => {
    setForm(previous => ({ ...previous, [key]: value }));
    if (key === 'description' || key === 'spec') {
      generation.current += 1;
      setPreview(null); setDraft(EMPTY_FEATURES); setConfirmed(false);
    }
  };
  const editFeature = (key: keyof FeatureDraft, value: string) => {
    setDraft(previous => ({ ...previous, [key]: value }));
    setConfirmed(false);
  };
  const extract = async () => {
    const current = ++generation.current;
    setBusy('extract'); setError(''); setConfirmed(false);
    try {
      const result = await extractProductFeatures(form.description, form.spec);
      if (generation.current !== current) return;
      setPreview(result); setDraft(featureDraft(result.features));
    } catch (failure) {
      if (generation.current === current) setError(getErrorMessage(failure, '提取失败，请重试'));
    } finally {
      if (generation.current === current) setBusy(null);
    }
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy || savedCode) return;
    if (!preview) { await extract(); return; }
    const current = ++generation.current;
    setBusy('save'); setError('');
    try {
      const result = await createAdminProduct({ ...form, price: Number(form.price),
        features: featureValues(draft), featuresConfirmed: confirmed });
      if (generation.current === current) setSavedCode(result.productCode);
    } catch (failure) {
      if (generation.current === current) setError(getErrorMessage(failure, '录入未确认成功，请先按商品编码核对，避免重复录入'));
    } finally {
      if (generation.current === current) setBusy(null);
    }
  };
  const reset = () => {
    generation.current += 1;
    setForm(EMPTY_FORM); setPreview(null); setDraft(EMPTY_FEATURES);
    setConfirmed(false); setError(''); setSavedCode(''); setBusy(null);
  };

  return <div className="admin-page product-intake-page">
    <AdminPageIntro eyebrow="CATALOG" title="商品录入" description="填写商品资料，自动提取明确参数。核对后与商品一起保存，供咨询与推荐使用。" />
    {error && <div className="admin-notice is-error" role="alert">{error}</div>}
    {savedCode && <div className="admin-notice is-success" role="status">
      <span>商品 {savedCode} 及结构化参数已保存。</span>
      <button type="button" onClick={reset}>录入下一件</button>
    </div>}
    <form onSubmit={submit}>
      <fieldset disabled={!!busy || !!savedCode} className="product-intake-grid">
        <section className="admin-panel product-intake-panel" aria-label="商品基本资料">
          <h2>1. 填写商品资料</h2>
          <div className="product-intake-fields">
            <label className="admin-form-field"><span>商品编码 *</span><input required maxLength={50} pattern={'[A-Za-z0-9][A-Za-z0-9._\\-]{0,49}'} value={form.productCode} onChange={e => update('productCode', e.target.value)} placeholder="唯一编码，如 SKU-001" /></label>
            <label className="admin-form-field"><span>商品名称 *</span><input required maxLength={200} value={form.productName} onChange={e => update('productName', e.target.value)} /></label>
            <label className="admin-form-field"><span>品类 *</span><input required maxLength={50} value={form.category} onChange={e => update('category', e.target.value)} placeholder="如：笔记本电脑" /></label>
            <label className="admin-form-field"><span>售价（元）*</span><input required type="number" min="0" max="99999999.99" step="0.01" value={form.price} onChange={e => update('price', e.target.value)} /></label>
            <label className="admin-form-field"><span>目录库存状态 *</span><select value={form.stock} onChange={e => update('stock', e.target.value)}><option>缺货</option><option>充足</option><option>紧张</option></select></label>
            <label className="admin-form-field"><span>颜色</span><input maxLength={200} value={form.color} onChange={e => update('color', e.target.value)} /></label>
            <label className="admin-form-field product-intake-wide"><span>商品简介</span><textarea aria-label="商品简介" maxLength={10000} value={form.description} onChange={e => update('description', e.target.value)} placeholder="粘贴真实商品简介。数值示例仅说明格式：整机净重1.2kg，视频播放续航12小时。" /></label>
            <label className="admin-form-field product-intake-wide"><span>规格说明</span><textarea aria-label="规格说明" maxLength={10000} value={form.spec} onChange={e => update('spec', e.target.value)} placeholder="补充厂商规格及测试场景，如主动降噪的支持情况。" /></label>
          </div>
        </section>
        <section className="admin-panel product-intake-panel" aria-label="结构化参数核对">
          <h2>2. 核对提取参数</h2>
          <p>先从简介和规格提取，再人工确认。未明确说明的字段留空，不会根据“轻便、续航长”猜测数值。</p>
          {!preview ? <div className="product-intake-placeholder">填写左侧资料后，点击下方“提取参数并预览”。此步骤不会写入商品目录。</div> : <>
            <div className="product-intake-fields">
              <label className="admin-form-field"><span>设备净重（克）</span><input aria-label="设备净重（克）" aria-describedby="intake-weight-evidence" type="number" min="0.001" max="9999999.999" step="0.001" value={draft.weightGrams} onChange={e => editFeature('weightGrams', e.target.value)} placeholder="未知" /><small id="intake-weight-evidence">{preview.evidence.weightGrams || '原文未提取到明确净重'}</small></label>
              <label className="admin-form-field"><span>标称续航（小时）</span><input aria-label="标称续航（小时）" aria-describedby="intake-battery-evidence" type="number" min="0.01" max="999999.99" step="0.01" value={draft.batteryLifeHours} onChange={e => editFeature('batteryLifeHours', e.target.value)} placeholder="未知" /><small id="intake-battery-evidence">{preview.evidence.batteryLifeHours || '原文未提取到可比较的续航'}</small></label>
              <label className="admin-form-field"><span>续航测试场景</span><select value={draft.batteryLifeScenario} onChange={e => editFeature('batteryLifeScenario', e.target.value)}><option value="">未知</option><option value="video_playback">视频播放</option><option value="audio_anc_on">开启降噪听歌</option><option value="audio_anc_off">关闭降噪听歌</option><option value="mixed_use">综合使用</option></select></label>
              <label className="admin-form-field"><span>主动降噪</span><select aria-label="主动降噪" aria-describedby="intake-anc-evidence" value={draft.noiseCancelling} onChange={e => editFeature('noiseCancelling', e.target.value)}><option value="unknown">未知</option><option value="yes">支持</option><option value="no">不支持</option></select><small id="intake-anc-evidence">{preview.evidence.noiseCancelling || '原文未明确支持情况'}</small></label>
            </div>
            {preview.warnings.length > 0 && <ul className="product-intake-warnings">{preview.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>}
            <label className="product-intake-confirm"><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} /><span>我已核对参数与原始资料一致。人工修改也会记录；这不代表系统完成了外部事实核验。</span></label>
            <button type="button" className="admin-button secondary" onClick={() => void extract()}>重新提取（替换当前参数）</button>
          </>}
        </section>
      </fieldset>
      <div className="product-intake-footer">
        <p>简介或规格修改后须重新提取。保存后仍可通过现有参数维护接口修正。</p>
        <button type="submit" className="admin-button primary" disabled={!!busy || !!savedCode || (!!preview && hasKnownFeatures(draft) && !confirmed)}>
          {busy === 'extract' ? '正在提取…' : busy === 'save' ? '正在保存…' : preview ? '确认并录入商品' : '提取参数并预览'}
        </button>
      </div>
    </form>
  </div>;
}
