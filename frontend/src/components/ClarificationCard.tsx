import { useRef, useState } from 'react';
import { clarificationMaxLengths, clarificationReply, type ClarificationFormData, type ClarificationSubmission } from '../utils/clarificationForm';
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
      : '请检查必填信息、数值范围及订单号格式，不要在字段中填写操作指令。'); return; }
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
          inputMode={field.key === 'recipientPhone' ? 'tel' : field.type === 'number' ? 'decimal' : 'text'} maxLength={clarificationMaxLengths[field.key]}
          autoComplete="off" required value={values[field.key] || ''}
          onChange={event => setValues(previous => ({ ...previous, [field.key]: event.target.value }))} />
        <small>{field.key === 'weight' ? '0.001–1000 公斤，最多 3 位小数'
          : field.key === 'budget' ? '0.01–10000000 元，最多 2 位小数'
          : field.key === 'quantity' ? '1–10000 件，填写整数'
          : field.key === 'orderNumber' ? '以 ORD- 或 BULK- 开头的订单编号'
          : field.key === 'recipientPhone' ? '填写 11 位中国大陆手机号码'
          : field.key === 'afterSalesType' ? '填写：退货、换货或维修'
          : `填写${field.label}，最多 ${clarificationMaxLengths[field.key]} 字`}</small>
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
