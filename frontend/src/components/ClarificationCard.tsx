import { useRef, useState } from 'react';
import { clarificationReply, type ClarificationFormData, type ClarificationSubmission } from '../utils/clarificationForm';
import './clarification-card.css';

export function ClarificationCard({ form, disabled, onSubmit }: {
  form: ClarificationFormData; disabled: boolean; onSubmit: (text: string, submission: ClarificationSubmission) => void;
}) {
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(form.fields.map(field => [field.key, field.value])));
  const [dismissed, setDismissed] = useState(false);
  const [error, setError] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const submitting = useRef(false);
  if (dismissed) return <p className="clarification-note">您可以在下方输入框中继续补充信息。</p>;
  return <form className="clarification-card" aria-label="补充信息" onSubmit={event => {
    event.preventDefault();
    if (disabled || submitting.current) return;
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
    <fieldset disabled={disabled || submitted}>
      {form.fields.map(field => <label key={field.key}>
        <span>{field.label}{field.unit && `（${field.unit}）`}</span>
        <input name={field.key} aria-label={field.label} type="text"
          inputMode={field.key === 'recipientPhone' ? 'tel' : field.type === 'number' ? 'decimal' : 'text'}
          minLength={field.minLength} maxLength={field.maxLength}
          autoComplete="off" required value={values[field.key] || ''}
          onChange={event => setValues(previous => ({ ...previous, [field.key]: event.target.value }))} />
        <small>{field.type === 'number' ? `${field.hint}（${field.min}–${field.max}${field.unit}）`
          : `${field.hint}，最多 ${field.maxLength} 字`}</small>
      </label>)}
      <div className="clarification-actions">
        <button type="submit">{submitted ? '已提交' : '补充并继续'}</button>
        <button type="button" onClick={() => setDismissed(true)}>改用文字回复</button>
      </div>
    </fieldset>
    {error && <p role="alert">{error}</p>}
    <p className="clarification-note">提交仅补充信息，不会确认下单、付款或退款。</p>
  </form>;
}
