import { useEffect, useRef, useState, type FormEvent } from 'react';
import { createAdminProduct, extractProductFeatures, getProductAliases, saveProductAliases, type ProductAliasState, type ProductExtraction } from '../api/adminProducts';
import { AdminPageIntro } from './AdminState';
import { getErrorMessage } from './adminFormat';
import { featureDraft, featureValues, hasKnownFeatures, productAliases, suitabilityTags, type FeatureDraft } from './productIntake';
import './productIntake.css';

const EMPTY_FORM = { productCode: '', productName: '', category: '', price: '', stock: '缺货', description: '', spec: '', color: '' };
const EMPTY_FEATURES: FeatureDraft = { weightGrams: '', batteryLifeHours: '', batteryLifeScenario: '', noiseCancelling: 'unknown' };

/** Separate administrator intake, with extraction before the explicit product commit. */
export function AdminProductsPage() {
  const [form, setForm] = useState(EMPTY_FORM);
  const [preview, setPreview] = useState<ProductExtraction | null>(null);
  const [draft, setDraft] = useState(EMPTY_FEATURES);
  const [confirmed, setConfirmed] = useState(false);
  const [audiences, setAudiences] = useState('');
  const [useCases, setUseCases] = useState('');
  const [suitabilitySource, setSuitabilitySource] = useState('');
  const [suitabilityConfirmed, setSuitabilityConfirmed] = useState(false);
  const [aliases, setAliases] = useState('');
  const [editCode, setEditCode] = useState('');
  const [existingAliases, setExistingAliases] = useState<ProductAliasState | null>(null);
  const [editAliases, setEditAliases] = useState('');
  const [editBusy, setEditBusy] = useState(false);
  const [editMessage, setEditMessage] = useState('');
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
      const audienceTags = suitabilityTags(audiences);
      const useCaseTags = suitabilityTags(useCases);
      const aliasesList = productAliases(aliases);
      if (aliasesList.some(alias => alias.toLocaleUpperCase() === form.productName.trim().toLocaleUpperCase())) {
        throw new Error('别名不能与正式商品名称相同');
      }
      if (!(audienceTags.length || useCaseTags.length) && suitabilitySource.trim()) {
        throw new Error('填写标注依据时也请填写适用人群或用途；未知请全部留空');
      }
      if ((audienceTags.length || useCaseTags.length) && (!suitabilityConfirmed || !suitabilitySource.trim())) {
        throw new Error('适用标签须填写依据并人工确认');
      }
      const result = await createAdminProduct({ ...form, price: Number(form.price),
        features: featureValues(draft), featuresConfirmed: confirmed,
        ...(aliasesList.length ? { aliases: aliasesList } : {}),
        ...((audienceTags.length || useCaseTags.length) ? { suitability: {
          audiences: audienceTags, useCases: useCaseTags,
          source: suitabilitySource.trim(), confirmed: suitabilityConfirmed } } : {}) });
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
    setAudiences(''); setUseCases(''); setSuitabilitySource(''); setSuitabilityConfirmed(false); setAliases('');
  };

  const loadExistingAliases = async () => {
    setEditBusy(true); setEditMessage(''); setExistingAliases(null);
    try {
      const state = await getProductAliases(editCode.trim());
      setExistingAliases(state); setEditAliases(state.aliases.join('，'));
    } catch (failure) { setEditMessage(getErrorMessage(failure, '读取别名失败')); }
    finally { setEditBusy(false); }
  };
  const updateExistingAliases = async () => {
    if (!existingAliases) return;
    setEditBusy(true); setEditMessage('');
    try {
      const values = productAliases(editAliases);
      if (values.some(alias => alias.toLocaleUpperCase() === existingAliases.productName.toLocaleUpperCase())) {
        throw new Error('别名不能与正式商品名称相同');
      }
      const state = await saveProductAliases(existingAliases.productCode, existingAliases.revision, values);
      setExistingAliases(state); setEditMessage('别名已保存；商品检索索引会在下一次刷新后更新。');
    } catch (failure) { setEditMessage(getErrorMessage(failure, '保存失败，请重新读取商品别名后重试')); }
    finally { setEditBusy(false); }
  };

  return <div className="admin-page product-intake-page">
    <AdminPageIntro eyebrow="CATALOG" title="商品录入" description="填写商品资料，自动提取明确参数。核对后与商品一起保存，供咨询与推荐使用。" />
    {error && <div className="admin-notice is-error" role="alert">{error}</div>}
    {savedCode && <div className="admin-notice is-success" role="status">
      <span>商品 {savedCode}、结构化参数及适用标签已保存。</span>
      <button type="button" onClick={reset}>录入下一件</button>
    </div>}
    <form onSubmit={submit}>
      <fieldset disabled={!!busy || !!savedCode} className="product-intake-grid">
        <section className="admin-panel product-intake-panel" aria-label="商品基本资料">
          <h2>1. 填写商品资料</h2>
          <div className="product-intake-fields">
            <label className="admin-form-field"><span>商品编码 *</span><input required maxLength={50} pattern={'[A-Za-z0-9][A-Za-z0-9._\\-]{0,49}'} value={form.productCode} onChange={e => update('productCode', e.target.value)} placeholder="唯一编码，如 SKU-001" /></label>
            <label className="admin-form-field"><span>商品名称 *</span><input required maxLength={200} value={form.productName} onChange={e => update('productName', e.target.value)} /></label>
            <label className="admin-form-field product-intake-wide"><span>商品别名（可选）</span><textarea aria-label="商品别名" value={aliases} onChange={e => setAliases(e.target.value)} placeholder="如：厂商明确使用的简称；逗号或换行分隔，不会从简介自动推断" /></label>
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
        <section className="admin-panel product-intake-panel" aria-label="适用人群与用途">
          <h2>3. 标注适用人群与用途（可选）</h2>
          <p>这类标签是目录声明，不是可测量参数或性能保证。只录入有资料支持的内容；未知请留空。</p>
          <div className="product-intake-fields">
            <label className="admin-form-field"><span>适用人群</span><textarea aria-label="适用人群" value={audiences} onChange={e => { setAudiences(e.target.value); setSuitabilityConfirmed(false); }} placeholder="如：学生、通勤人群；用逗号或换行分隔" /></label>
            <label className="admin-form-field"><span>用途</span><textarea aria-label="用途" value={useCases} onChange={e => { setUseCases(e.target.value); setSuitabilityConfirmed(false); }} placeholder="如：学习、通勤；用逗号或换行分隔" /></label>
            <label className="admin-form-field product-intake-wide"><span>标注依据</span><input aria-label="标注依据" maxLength={500} value={suitabilitySource} onChange={e => { setSuitabilitySource(e.target.value); setSuitabilityConfirmed(false); }} placeholder="填写厂商资料或已核对的目录来源，不要填写主观推断" /></label>
          </div>
          <label className="product-intake-confirm"><input type="checkbox" checked={suitabilityConfirmed} onChange={e => setSuitabilityConfirmed(e.target.checked)} /><span>我已核对适用人群和用途标签有上述资料支持。</span></label>
        </section>
      </fieldset>
      <div className="product-intake-footer">
        <p>简介或规格修改后须重新提取。保存后仍可通过现有参数维护接口修正。</p>
        <button type="submit" className="admin-button primary" disabled={!!busy || !!savedCode || (!!preview && hasKnownFeatures(draft) && !confirmed) || (!!(audiences.trim() || useCases.trim()) && (!suitabilityConfirmed || !suitabilitySource.trim()))}>
          {busy === 'extract' ? '正在提取…' : busy === 'save' ? '正在保存…' : preview ? '确认并录入商品' : '提取参数并预览'}
        </button>
      </div>
    </form>
    <section className="admin-panel product-intake-panel" aria-label="已有商品别名维护">
      <h2>维护已有商品别名</h2>
      <p>按商品编码读取并替换别名。删除全部别名时清空列表再保存；并发修改会要求重新读取。</p>
      <label className="admin-form-field"><span>商品编码</span><input aria-label="待维护商品编码" value={editCode} onChange={e => { setEditCode(e.target.value); setExistingAliases(null); setEditMessage(''); }} /></label>
      <button type="button" className="admin-button secondary" disabled={editBusy || !editCode.trim()} onClick={() => void loadExistingAliases()}>读取商品别名</button>
      {existingAliases && <>
        <p>当前商品：{existingAliases.productName}（修订号 {existingAliases.revision}）</p>
        <label className="admin-form-field"><span>别名列表</span><textarea aria-label="已有商品别名" value={editAliases} onChange={e => setEditAliases(e.target.value)} /></label>
        <button type="button" className="admin-button primary" disabled={editBusy} onClick={() => void updateExistingAliases()}>保存别名</button>
      </>}
      {editMessage && <p role="status">{editMessage}</p>}
    </section>
  </div>;
}
