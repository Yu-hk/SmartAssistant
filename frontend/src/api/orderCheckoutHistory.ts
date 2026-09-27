import { apiClient } from './client';

export interface CheckoutSuggestion {
  recipientName: string;
  recipientPhone: string;
  shippingAddress: string;
  orderDate: string;
}

export interface CheckoutSuggestions {
  options: CheckoutSuggestion[];
  conflictingHistory: boolean;
}

export async function getCheckoutSuggestions(): Promise<CheckoutSuggestions> {
  const raw = await apiClient.request<CheckoutSuggestions>('/order/checkout-history', { cache: 'no-store' });
  if (!raw || !Array.isArray(raw.options) || raw.options.length > 3
      || typeof raw.conflictingHistory !== 'boolean') throw new Error('历史收货信息暂不可用');
  const options = raw.options.filter(option => option
    && typeof option.recipientName === 'string' && option.recipientName.length <= 40
    && typeof option.recipientPhone === 'string' && /^1[3-9][0-9]{9}$/.test(option.recipientPhone)
    && typeof option.shippingAddress === 'string' && option.shippingAddress.length <= 200
    && typeof option.orderDate === 'string' && /^\d{4}-\d{2}-\d{2}/.test(option.orderDate));
  return { options, conflictingHistory: raw.conflictingHistory && options.length > 1 };
}
