import { useRef, useState } from 'react';
import { clarificationReply, type ClarificationFormData, type ClarificationSubmission } from '../utils/clarificationForm';
import { getCheckoutSuggestions, type CheckoutSuggestion } from '../api/orderCheckoutHistory';
import './clarification-card.css';

export function ClarificationCard({ form, disabled, onSubmit }: {
  form: ClarificationFormData; disabled: boolean; onSubmit: (text: string, submission: ClarificationSubmission) => void;
}) {
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(form.fields.map(field => [field.key, field.value])));
  const [dismissed, setDismissed] = useState(false);
  const [error, setError] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyOptions, setHistoryOptions] = useState<CheckoutSuggestion[]>([]);
  const [historyConflicting, setHistoryConflicting] = useState(false);
  const [historyNotice, setHistoryNotice] = useState('');
  const [pendingSuggestion, setPendingSuggestion] = useState<CheckoutSuggestion | null>(null);
  const [historyApplied, setHistoryApplied] = useState(false);
  const [historyConfirmed, setHistoryConfirmed] = useState(false);
  const submitting = useRef(false);
  const orderHistoryAvailable = form.domain === 'order' && form.operation === 'CREATE_ORDER';
  const suggestionValue = (option: CheckoutSuggestion, key: string) => {
    switch (key) {
      case 'recipientName': return option.recipientName;
      case 'recipientPhone': return option.recipientPhone;
      case 'shippingAddress': return option.shippingAddress;
      default: return null;
    }
  };
  const applySuggestion = (option: CheckoutSuggestion, replace = false) => {
    const changed = form.fields.filter(field => {
      const suggested = suggestionValue(option, field.key);
      return suggested !== null && values[field.key]?.trim()
        && values[field.key].trim() !== suggested.trim();
    });
    if (changed.length && !replace) {
      setPendingSuggestion(option);
      setHistoryNotice(`当前填写的${changed.map(field => field.label).join('、')}与所选历史订单不同，请核对后确认是否替换。`);
      return;
    }
    setValues(previous => Object.fromEntries(form.fields.map(field =>
      [field.key, suggestionValue(option, field.key) ?? previous[field.key] ?? ''])));
    setPendingSuggestion(null);
    setHistoryApplied(true);
    setHistoryConfirmed(false);
    setHistoryNotice('已填入所选历史收货信息，请逐项核对；本次订单尚未创建。');
    setError('');
  };
  const loadHistory = async () => {
    if (historyLoading || historyOpen) return;
    setHistoryLoading(true);
    try {
      const history = await getCheckoutSuggestions();
      setHistoryOptions(history.options);
      setHistoryConflicting(history.conflictingHistory);
      setHistoryOpen(true);
      setHistoryNotice(history.options.length ? '' : '没有可用的历史收货信息，请填写本次收货资料。');
    } catch {
      setHistoryNotice('暂时无法读取历史收货信息，请手动填写；本次没有创建订单。');
    } finally {
      setHistoryLoading(false);
    }
  };
  if (dismissed) return <p className="clarification-note">您可以在下方输入框中继续补充信息。</p>;
  return <form className="clarification-card" aria-label="补充信息" onSubmit={event => {
    event.preventDefault();
    if (disabled || submitting.current) return;
    if (pendingSuggestion || (historyApplied && !historyConfirmed)) {
      setError('请先核对并确认本次收货信息，或继续修改表单。'); return;
    }
    const reply = clarificationReply(form, values);
    if (!reply) { setError(form.expiresAt <= Date.now() ? '表单已过期，请刷新会话或改用文字回复。'
      : '请按各字段下方的提示检查必填信息、格式和范围，不要在字段中填写操作指令。'); return; }
    submitting.current = true;
    setSubmitted(true);
    setError('');
    onSubmit(reply, { token: form.token, values });
  }}>
    <strong>补充一下，方便继续为您处理</strong>
    <p className="clarification-note">只需填写以下信息，也可以直接用文字回复。</p>
    {orderHistoryAvailable && <div className="clarification-history">
      <button type="button" disabled={disabled || submitted || historyLoading} onClick={loadHistory}>
        {historyLoading ? '正在读取…' : '从历史订单选择收货信息'}
      </button>
      {historyConflicting && <p role="status">历史订单中的收货信息不一致，请选择并核对本次要使用的资料。</p>}
      {historyOpen && historyOptions.map((option, index) => <div className="clarification-history-option" key={`${option.orderDate}-${index}`}>
        <span>{option.orderDate.slice(0, 10)} · {option.recipientName} · {option.recipientPhone.slice(0, 3)}****{option.recipientPhone.slice(-4)}</span>
        <span>{option.shippingAddress}</span>
        <button type="button" disabled={disabled || submitted} onClick={() => applySuggestion(option)}>使用这条资料</button>
      </div>)}
      {pendingSuggestion && <button type="button" disabled={disabled || submitted}
        onClick={() => applySuggestion(pendingSuggestion, true)}>确认替换当前填写</button>}
      {historyNotice && <p role="status">{historyNotice}</p>}
    </div>}
    <fieldset disabled={disabled || submitted}>
      {form.fields.map(field => <label key={field.key}>
        <span>{field.label}{field.unit && `（${field.unit}）`}</span>
        <input name={field.key} aria-label={field.label} type="text"
          inputMode={field.key === 'recipientPhone' ? 'tel' : field.type === 'number' ? 'decimal' : 'text'}
          minLength={field.minLength} maxLength={field.maxLength}
          autoComplete="off" required value={values[field.key] || ''}
          onChange={event => {
            setValues(previous => ({ ...previous, [field.key]: event.target.value }));
            if (historyApplied) setHistoryConfirmed(false);
            setPendingSuggestion(null);
          }} />
        <small>{field.type === 'number' ? `${field.hint}（${field.min}–${field.max}${field.unit}）`
          : `${field.hint}，最多 ${field.maxLength} 字`}</small>
      </label>)}
      {historyApplied && <label className="clarification-history-confirm">
        <input type="checkbox" checked={historyConfirmed}
          onChange={event => setHistoryConfirmed(event.target.checked)} />
        <span>我已核对本次要使用的收货信息</span>
      </label>}
      <div className="clarification-actions">
        <button type="submit">{submitted ? '已提交' : '补充并继续'}</button>
        <button type="button" onClick={() => setDismissed(true)}>改用文字回复</button>
      </div>
    </fieldset>
    {error && <p role="alert">{error}</p>}
    <p className="clarification-note">提交仅补充信息，不会确认下单、付款或退款。</p>
  </form>;
}
