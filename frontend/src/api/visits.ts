import { apiClient } from './client';
import { getAuthToken, getAuthUser } from './authStorage';

export interface VisitModule { code: string; label: string; audience: string; kind: string; path: string; entry: string }
export interface VisitRow {
  id?: string; visitorId: string; userId: number | null; username: string | null; role: string;
  firstSeen?: string; lastSeen?: string; views?: number; modules?: number;
  module?: string; kind?: string; browser?: string; device?: string; createdAt?: string;
}
export interface VisitResult {
  items: VisitRow[]; total: number; page: number; size: number; retentionDays: number;
  summary: { views: number; visitors: number; modules: number };
  modules: { code: string; label: string; views: number; visitors: number }[];
}
let catalog: Promise<VisitModule[]> | undefined;
export function visitModules(): Promise<VisitModule[]> {
  if (!catalog) catalog = fetch('/api/public/visit-modules', { signal: AbortSignal.timeout(5000) })
    .then(async response => { if (!response.ok) throw new Error('模块列表加载失败'); return response.json() as Promise<VisitModule[]>; })
    .catch(error => { catalog = undefined; throw error; });
  return catalog;
}
let fallbackVisitor: string | undefined;
function visitorId(): string {
  try {
    const stored = sessionStorage.getItem('sa_visit_session');
    if (stored && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(stored)) return stored;
    const id = crypto.randomUUID(); sessionStorage.setItem('sa_visit_session', id); return id;
  } catch { return fallbackVisitor ??= crypto.randomUUID(); }
}
export function moduleForPath(modules: VisitModule[], pathname: string): VisitModule | undefined {
  return modules.find(module => module.path && (module.path === pathname
    || (module.path === '/chat/:sessionId' && /^\/chat\/[^/]+$/.test(pathname))));
}
export async function sendVisit(module: VisitModule): Promise<void> {
  const token = getAuthToken();
  const role = getAuthUser()?.role;
  if (module.audience !== 'public' && !token) return;
  if (module.audience === 'admin' && role !== 'ROLE_ADMIN') return;
  if (module.audience === 'customer' && role === 'ROLE_ADMIN') return;
  const authenticated = module.audience !== 'public';
  try {
    await fetch(authenticated ? '/api/visits' : '/api/public/visits', {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...(authenticated && token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify({ eventId: crypto.randomUUID(), visitorId: visitorId(), module: module.code }),
      signal: AbortSignal.timeout(5000), keepalive: true,
    });
  } catch { /* Collection failure must not interrupt the user's current action. */ }
}
const entryTimes = new Map<string, number>();
export async function trackServiceEntry(entry: string): Promise<void> {
  try {
    const module = (await visitModules()).find(item => item.entry === entry);
    if (!module) return;
    const now = Date.now();
    if (now - (entryTimes.get(module.code) ?? 0) < 2000) return;
    entryTimes.set(module.code, now);
    await sendVisit(module);
  } catch { /* Best effort analytics. */ }
}
export function fetchVisits(params: Record<string, string | number>): Promise<VisitResult> {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value !== '') query.set(key, String(value));
  return apiClient.get<VisitResult>(`/admin/visits?${query}`);
}
