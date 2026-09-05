import { useState, useEffect, useCallback } from 'react';
import { Session, Message, SessionStatus, normalizeIntentType } from '../types';
import { sessions as sessionApi } from '../api';
import { ApiError } from '../api/client';
import { normalizeTelemetry } from '../utils/sessionTelemetry';

function normalizeSessionStatus(value: unknown): SessionStatus {
  const status = String(value ?? '').trim().toUpperCase();
  if (['CLOSED', 'CLOSE', 'ENDED', 'TERMINATED'].includes(status)) return 'closed';
  if (['SUSPENDED', 'FROZEN'].includes(status)) return 'suspended';
  if (['HUMAN_TRANSFER', 'TRANSFERRED', 'HANDOFF', 'HUMAN_HANDOFF'].includes(status)) {
    return 'human_transfer';
  }
  // SUCCESS/FAILED/TIMEOUT describe the latest workflow turn, not session lifecycle.
  return 'active';
}

function normalizeSatisfaction(value: unknown): number | null {
  const score = Number(value);
  return Number.isFinite(score) && score >= 1 && score <= 5 ? score : null;
}

function normalizeDate(value: unknown): Date {
  const date = value ? new Date(String(value)) : new Date();
  return Number.isNaN(date.getTime()) ? new Date() : date;
}

function normalizeSession(raw: any): Session {
  return {
    id: raw.id ?? raw.sessionId ?? raw.session_id,
    title: raw.title || '未命名对话',
    model: raw.model || raw.modelName || raw.model_name || 'deepseek-v4-flash',
    sdk_session_id: raw.sdkSessionId ?? raw.sdk_session_id ?? null,
    intent: normalizeIntentType(raw.intent),
    status: normalizeSessionStatus(raw.status),
    satisfaction: normalizeSatisfaction(raw.satisfaction),
    satisfaction_comment: raw.satisfactionComment ?? raw.satisfaction_comment ?? null,
    user_name: raw.userName ?? raw.user_name ?? raw.username ?? '用户',
    agent_name: raw.agentName ?? raw.agent_name ?? null,
    messageCount: Number(raw.messageCount ?? raw.message_count ?? 0),
    createdAt: normalizeDate(raw.createdAt ?? raw.created_at),
    messages: [],
  };
}

function normalizeMessage(raw: any): Message {
  const requestId = raw.requestId ?? raw.request_id ?? undefined;
  const status = String(raw.status ?? '').toUpperCase();
  return {
    id: raw.id,
    role: raw.role,
    content: raw.content,
    model: raw.model,
    intent: raw.intent ? normalizeIntentType(raw.intent) : undefined,
    requestId,
    deliveryStatus: status === 'FAILED' || status === 'TIMEOUT' ? 'failed' : 'completed',
    recoverable: Boolean(requestId) && (status === 'FAILED' || status === 'TIMEOUT'),
    timestamp: normalizeDate(raw.createdAt ?? raw.created_at),
    ...normalizeTelemetry(raw),
  };
}

export function useSessions() {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [sessionActionError, setSessionActionError] = useState<string | null>(null);

  const currentSession = sessions.find(s => s.id === currentSessionId);

  const fetchSessions = useCallback(async () => {
    try {
      const data = await sessionApi.fetchSessions();
      // 兼容 API 返回 { sessions: [...] } 或直接返回数组
      const sessionList = Array.isArray(data) ? data : (data as any).sessions || [];
      if (sessionList.length > 0) {
        const loaded: Session[] = sessionList.map(normalizeSession);
        setSessions(prev => {
          const remoteIds = new Set(loaded.map(session => session.id));
          const localOnly = prev.filter(session => !remoteIds.has(session.id));
          const mergedRemote = loaded.map(remote => {
            const local = prev.find(session => session.id === remote.id);
            return local?.messages.length
              ? { ...remote, messages: local.messages }
              : remote;
          });
          return [...localOnly, ...mergedRemote];
        });
      }
    } catch (e) { console.error('fetchSessions error:', e); }
  }, []);

  const createSession = useCallback((title = '新对话'): string => {
    const sessionId = crypto.randomUUID();
    const session: Session = {
      id: sessionId,
      title,
      model: 'claude-sonnet-4',
      intent: 'unknown',
      status: 'active',
      satisfaction: null,
      satisfaction_comment: null,
      user_name: '用户',
      agent_name: null,
      createdAt: new Date(),
      messages: [],
    };
    setSessions(prev => [session, ...prev]);
    setCurrentSessionId(sessionId);
    return sessionId;
  }, []);

  const loadSessionMessages = useCallback(async (sessionId: string) => {
    try {
      const data = await sessionApi.fetchSession(sessionId);
      const payload = data as any;
      const msgs = Array.isArray(payload.messages) ? payload.messages : [];
      const messages: Message[] = msgs.map(normalizeMessage);
      setSessions(prev => prev.map(s => s.id === sessionId ? { ...s, messages } : s));

      // The customer endpoint returns the session fields at top level, while older
      // deployments wrapped them in { session, messages }.
      const sessionData = payload.session ?? payload;
      if (sessionData) {
        setSessions(prev => prev.map(s => {
          if (s.id === sessionId) {
            const normalized = normalizeSession(sessionData);
            return {
              ...s,
              ...normalized,
              id: s.id,
              messages: s.messages,
            };
          }
          return s;
        }));
      }
    } catch (e) {
      // A newly-created browser session is local until its first request is
      // persisted, so a detail lookup can legitimately return 404.
      if (!(e instanceof ApiError) || e.status !== 404) {
        console.error('loadSessionMessages error:', e);
      }
    }
  }, []);

  const deleteSession = useCallback(async (sessionId: string): Promise<string | null> => {
    try {
      await sessionApi.deleteSession(sessionId);
    } catch (e) {
      // 本地新建会话在首次发送前不会落库，后端 404 时仍应允许从列表移除。
      if (!(e instanceof ApiError) || e.status !== 404) {
        console.error(e);
        return null;
      }
    }

    setSessions(prev => prev.filter(s => s.id !== sessionId));
    const remaining = sessions.filter(s => s.id !== sessionId);
    if (currentSessionId !== sessionId) return null;
    if (remaining.length > 0) {
      setCurrentSessionId(remaining[0].id);
      return `/chat/${remaining[0].id}`;
    }
    setCurrentSessionId(null);
    return '/';
  }, [sessions, currentSessionId]);

  const closeSession = useCallback(async (sessionId: string) => {
    setSessionActionError(null);
    try {
      await sessionApi.closeSession(sessionId);
    } catch (e) {
      // A local unsaved session can still be closed. Other failures must not be
      // rendered as a successful close.
      if (!(e instanceof ApiError) || e.status !== 404) {
        console.error(e);
        setSessionActionError('关闭对话失败，请稍后重试。');
        return false;
      }
    }
    setSessions(prev => prev.map(s => {
      if (s.id === sessionId) return { ...s, status: 'closed' };
      return s;
    }));
    return true;
  }, []);

  const resumeSession = useCallback(async (sessionId: string) => {
    setSessionActionError(null);
    try {
      await sessionApi.resumeSession(sessionId);
    } catch (e) {
      console.error(e);
      setSessionActionError(e instanceof ApiError
        ? e.message
        : '恢复会话失败，请稍后重试。');
      return false;
    }
    setSessions(prev => prev.map(s => s.id === sessionId
      ? { ...s, status: 'active' }
      : s));
    return true;
  }, []);

  const rateSession = useCallback(async (sessionId: string, score: number) => {
    setSessionActionError(null);
    try {
      await sessionApi.rateSession(sessionId, score);
    } catch (e) {
      if (!(e instanceof ApiError) || e.status !== 404) {
        console.error(e);
        setSessionActionError('评价提交失败，请稍后重试。');
        return false;
      }
    }
    setSessions(prev => prev.map(s => s.id === sessionId
      ? { ...s, satisfaction: score, status: 'closed' }
      : s));
    return true;
  }, []);

  const updateSessionModel = useCallback((sessionId: string, modelId: string) => {
    setSessions(prev => prev.map(s => s.id === sessionId ? { ...s, model: modelId } : s));
  }, []);

  const updateSession = useCallback((sessionId: string, updates: Partial<Session>) => {
    setSessions(prev => prev.map(s => s.id === sessionId ? { ...s, ...updates } : s));
  }, []);

  const updateSessionMessages = useCallback((sessionId: string, updater: (messages: Message[]) => Message[]) => {
    setSessions(prev => prev.map(s => s.id === sessionId ? { ...s, messages: updater(s.messages) } : s));
  }, []);

  useEffect(() => {
    if (currentSessionId) {
      const session = sessions.find(s => s.id === currentSessionId);
      if (session && session.messages.length === 0) {
        loadSessionMessages(currentSessionId);
      }
    }
  }, [currentSessionId, sessions, loadSessionMessages]);

  return {
    sessions, setSessions, sessionActionError, setSessionActionError,
    currentSessionId, setCurrentSessionId,
    currentSession,
    fetchSessions, loadSessionMessages, createSession,
    deleteSession, closeSession, resumeSession, rateSession,
    updateSessionModel, updateSession, updateSessionMessages,
  };
}
