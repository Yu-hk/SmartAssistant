import { apiClient } from './client';

export interface ProfileRow {
  id: number; username: string; analysis_enabled: boolean;
  profile_version: number | null; schema_version: string | null;
  reliable: boolean | null; purchase_stage: string | null;
  profile_updated_at: string | null; job_id: string | null;
  cleanup_state: string | null; cleanup_requested_at: string | null;
}
export interface ProfileJob { job_id: string; generation: number; state: string; created_at: string; updated_at: string }
export interface ProfileReceipt { job_id: string; target: string; state: string; attempts: number; error_code: string | null; updated_at: string }
export interface ProfileAudit { action_id: string; actor_user_id: number; reason_code: string; job_id: string; created_at: string }
export interface ProfileDetail extends Omit<ProfileRow, 'job_id' | 'cleanup_state' | 'cleanup_requested_at'> {
  generation: number | null; lifecycle_updated_at: string | null;
  jobs: ProfileJob[]; receipts: ProfileReceipt[]; audit: ProfileAudit[];
}
export interface ProfilePage { page: number; size: number; total: number; items: ProfileRow[] }

export const listAdminProfiles = (query: string, page: number) =>
  apiClient.get<ProfilePage>(`/admin/profiles?query=${encodeURIComponent(query)}&page=${page}&size=20`);
export const getAdminProfile = (id: number) => apiClient.get<ProfileDetail>(`/admin/profiles/${id}`);
export const requestAdminCleanup = (id: number, expectedUsername: string, reasonCode: string, idempotencyKey: string) =>
  apiClient.post<{ jobId: string }>(`/admin/profiles/${id}/deletions`, {
    expectedUsername, reasonCode, idempotencyKey, confirmation: 'ADMIN_DELETE_PROFILE_PAUSE_ANALYSIS',
  });
