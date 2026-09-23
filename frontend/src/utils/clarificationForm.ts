export interface ClarificationField {
  key: string;
  label: string;
  type: 'text' | 'number';
  unit: string;
  value: string;
}
export interface ClarificationFormData { version: 1; fields: ClarificationField[] }

const definitions: Record<string, [string, 'text' | 'number', string]> = {
  weight: ['重量上限', 'number', '公斤'], budget: ['预算上限', 'number', '元'],
  city: ['城市', 'text', ''], orderNumber: ['订单号', 'text', ''],
  product: ['商品名称或类型', 'text', ''], quantity: ['购买数量', 'number', '件'],
};

/** Reject unknown schema/fields; server output cannot inject arbitrary form controls or actions. */
export function normalizeClarificationForm(raw: unknown): ClarificationFormData | undefined {
  const form = raw as Partial<ClarificationFormData> | null;
  if (form?.version !== 1 || !Array.isArray(form.fields) || !form.fields.length || form.fields.length > 6) return;
  const seen = new Set<string>();
  const fields: ClarificationField[] = [];
  for (const field of form.fields) {
    if (!field || !Object.prototype.hasOwnProperty.call(definitions, field.key) || seen.has(field.key)) return;
    seen.add(field.key);
    const [label, type, unit] = definitions[field.key];
    fields.push({ key: field.key, label, type, unit,
      value: typeof field.value === 'string' ? field.value.slice(0, 120) : '' });
  }
  return { version: 1, fields };
}

export function clarificationReply(form: ClarificationFormData, values: Record<string, string>): string | null {
  const parts: string[] = [];
  for (const field of form.fields) {
    const value = (values[field.key] || '').trim();
    if (!value || value.length > 120 || /[\r\n]/.test(value)) return null;
    if (field.type === 'number' && (!/^\d+(?:\.\d{1,3})?$/.test(value)
        || Number(value) <= 0 || Number(value) > 1e9
        || (field.key === 'quantity' && !Number.isInteger(Number(value))))) return null;
    // Use the existing domain grammar (预算为…), not the longer display label.
    const label = field.key === 'budget' ? '预算' : field.label;
    parts.push(`${label}为${value}${field.unit}`);
  }
  // Keep UI safety copy out of the query: unrelated payment/refund words can skew intent routing.
  return `补充信息：${parts.join('；')}。`;
}
