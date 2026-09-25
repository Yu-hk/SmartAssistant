export interface ClarificationField {
  key: string;
  label: string;
  type: 'text' | 'number';
  unit: string;
  value: string;
  min: string | null;
  max: string | null;
  decimals: number;
  minLength: number;
  maxLength: number;
  hint: string;
}
export interface ClarificationFormData { version: 2; token: string; expiresAt: number; fields: ClarificationField[] }
export interface ClarificationSubmission { token: string; values: Record<string, string> }

// This allowlist limits which controls may be rendered; business labels and bounds come from the server.
const fieldTypes: Record<string, 'text' | 'number'> = {
  weight: 'number', budget: 'number', quantity: 'number', city: 'text', orderNumber: 'text',
  product: 'text', recipientName: 'text', recipientPhone: 'text', shippingAddress: 'text',
  reason: 'text', afterSalesType: 'text',
};

/** Reject unknown schema/fields; server output cannot inject arbitrary form controls or actions. */
export function normalizeClarificationForm(raw: unknown): ClarificationFormData | undefined {
  const form = raw as Partial<ClarificationFormData> | null;
  if (form?.version !== 2 || typeof form.token !== 'string' || !form.token || form.token.length > 4096
      || typeof form.expiresAt !== 'number' || !Number.isFinite(form.expiresAt) || form.expiresAt <= Date.now()
      || !Array.isArray(form.fields) || !form.fields.length || form.fields.length > 6) return;
  const seen = new Set<string>();
  const fields: ClarificationField[] = [];
  for (const field of form.fields) {
    // Forms issued before this validation rule remain readable until their short expiry.
    const minLength = field?.minLength === undefined
      ? (field?.key === 'shippingAddress' ? 6 : 1) : field.minLength;
    if (!field || !Object.prototype.hasOwnProperty.call(fieldTypes, field.key) || seen.has(field.key)
        || field.type !== fieldTypes[field.key] || typeof field.label !== 'string' || !field.label
        || field.label.length > 40 || typeof field.unit !== 'string' || field.unit.length > 10
        || typeof field.hint !== 'string' || field.hint.length > 100
        || !Number.isInteger(field.maxLength) || field.maxLength < 1 || field.maxLength > 200
        || !Number.isInteger(minLength) || minLength < 1 || minLength > field.maxLength
        || !Number.isInteger(field.decimals) || field.decimals < 0 || field.decimals > 6) return;
    if (field.type === 'number') {
      if (typeof field.min !== 'string' || typeof field.max !== 'string'
          || !/^\d+(?:\.\d+)?$/.test(field.min) || !/^\d+(?:\.\d+)?$/.test(field.max)
          || Number(field.min) > Number(field.max) || Number(field.max) > 1e9) return;
    } else if (field.min !== null || field.max !== null) return;
    seen.add(field.key);
    fields.push({ key: field.key, label: field.label, type: field.type, unit: field.unit,
      min: field.min, max: field.max, decimals: field.decimals, minLength, maxLength: field.maxLength,
      hint: field.hint,
      value: typeof field.value === 'string' ? field.value.slice(0, field.maxLength) : '' });
  }
  return { version: 2, token: form.token, expiresAt: form.expiresAt, fields };
}

export function clarificationReply(form: ClarificationFormData, values: Record<string, string>): string | null {
  if (form.expiresAt <= Date.now() || Object.keys(values).length !== form.fields.length) return null;
  const parts: string[] = [];
  for (const field of form.fields) {
    const value = (values[field.key] || '').trim();
    if (value.length < field.minLength || (values[field.key] || '').length > field.maxLength
        || /[\u0000-\u001f\u007f-\u009f]/.test(values[field.key] || '')) return null;
    if (field.type === 'number') {
      if (!/^\d+(?:\.\d+)?$/.test(value) || Number(value) < Number(field.min)
          || Number(value) > Number(field.max)
          || (value.split('.')[1]?.length || 0) > field.decimals) return null;
    } else if (field.key === 'orderNumber') {
      if (!/^(?:ORD|BULK)-[A-Za-z0-9-]{1,64}$/.test(value)) return null;
    } else if (field.key === 'recipientPhone') {
      if (!/^1[3-9][0-9]{9}$/.test(value)) return null;
    } else if (field.key === 'afterSalesType') {
      if (!['退货', '换货', '维修'].includes(value)) return null;
    } else if (!/^[\p{L}\p{N} .·（）()＋+/,，。-]+$/u.test(value)
        || /下单|付款|确认|同意|忽略|执行|删除/.test(value)) return null;
    // Use the existing domain grammar (预算为…), not the longer display label.
    const label = field.key === 'budget' ? '预算' : field.label;
    parts.push(`${label}为${value}${field.unit}`);
  }
  // Keep UI safety copy out of the query: unrelated payment/refund words can skew intent routing.
  return `补充信息：${parts.join('；')}。`;
}
