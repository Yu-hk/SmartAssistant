export interface ClarificationField {
  key: string;
  label: string;
  type: 'text' | 'number';
  unit: string;
  value: string;
}
export interface ClarificationFormData { version: 2; token: string; expiresAt: number; fields: ClarificationField[] }
export interface ClarificationSubmission { token: string; values: Record<string, string> }

const definitions: Record<string, [string, 'text' | 'number', string]> = {
  weight: ['重量上限', 'number', '公斤'], budget: ['预算上限', 'number', '元'],
  city: ['城市', 'text', ''], orderNumber: ['订单号', 'text', ''],
  product: ['商品名称或类型', 'text', ''], quantity: ['购买数量', 'number', '件'],
  recipientName: ['收货人姓名', 'text', ''], recipientPhone: ['联系电话', 'text', ''],
  shippingAddress: ['收货地址', 'text', ''], reason: ['具体原因', 'text', ''], afterSalesType: ['售后类型', 'text', ''],
};
export const clarificationMaxLengths: Record<string, number> = {
  weight: 20, budget: 20, quantity: 10, city: 40, orderNumber: 69, product: 100,
  recipientName: 40, recipientPhone: 11, shippingAddress: 200, reason: 200, afterSalesType: 4,
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
    if (!field || !Object.prototype.hasOwnProperty.call(definitions, field.key) || seen.has(field.key)) return;
    seen.add(field.key);
    const [label, type, unit] = definitions[field.key];
    fields.push({ key: field.key, label, type, unit,
      value: typeof field.value === 'string' ? field.value.slice(0, clarificationMaxLengths[field.key]) : '' });
  }
  return { version: 2, token: form.token, expiresAt: form.expiresAt, fields };
}

export function clarificationReply(form: ClarificationFormData, values: Record<string, string>): string | null {
  if (form.expiresAt <= Date.now() || Object.keys(values).length !== form.fields.length) return null;
  const parts: string[] = [];
  for (const field of form.fields) {
    const value = (values[field.key] || '').trim();
    if (!value || (values[field.key] || '').length > clarificationMaxLengths[field.key]
        || /[\u0000-\u001f\u007f-\u009f]/.test(values[field.key] || '')) return null;
    if (field.type === 'number') {
      const [min, max, decimals] = field.key === 'weight' ? [0.001, 1000, 3]
        : field.key === 'budget' ? [0.01, 10000000, 2] : [1, 10000, 0];
      if (!/^\d+(?:\.\d+)?$/.test(value) || Number(value) < min || Number(value) > max
          || (value.split('.')[1]?.length || 0) > decimals) return null;
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
