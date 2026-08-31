import { apiClient } from './client';
import type { UserNotification } from '../types';

export async function fetchUnread(limit = 50): Promise<UserNotification[]> {
  return apiClient.get<UserNotification[]>(`/notifications?limit=${limit}`);
}

export async function markRead(notificationId: string): Promise<void> {
  return apiClient.post(`/notifications/${encodeURIComponent(notificationId)}/read`);
}
