import { useState, useEffect, useCallback } from 'react';
import { Session, Message, IntentType } from '../types';
import { sessions as sessionApi } from '../api';

export function useSessions() {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);

  const currentSession = sessions.find(s => s.id === currentSessionId);

  const fetchSessions = useCallback(async () => {
    try {
      const data = await sessionApi.fetchSessions();
      // 兼容 API 返回 { sessions: [...] } 或直接返回数组
      const sessionList = Array.isArray(data) ? data : (data as any).sessions || [];
      if (sessionList.length > 0) {
        const loaded: Session[] = sessionList.map((s: any) => ({
          id: s.id,
          title: s.title,
          model: s.model,
          sdk_session_id: s.sdk_session_id || null,
          intent: (s.intent || 'unknown') as IntentType,
          status: s.status || 'active',
          satisfaction: s.satisfaction ?? null,
          satisfaction_comment: s.satisfaction_comment ?? null,
          user_name: s.user_name || '访客',
          agent_name: s.agent_name || null,
          messageCount: s.messageCount || 0,
          createdAt: new Date(s.created_at),
          messages: [],
        }));
        setSessions(loaded);
      }
    } catch (e) { console.error('fetchSessions error:', e); }
  }, []);

  const loadSessionMessages = useCallback(async (sessionId: string) => {
    try {
      const data = await sessionApi.fetchSession(sessionId);
      const msgs = (data as any).messages || [];
      if (msgs.length > 0) {
        const messages: Message[] = msgs.map((m: any) => ({
          id: m.id,
          role: m.role,
          content: m.content,
          model: m.model,
          intent: m.intent || undefined,
          timestamp: new Date(m.created_at),
          toolCalls: m.tool_calls || undefined,
        }));
        setSessions(prev => prev.map(s => s.id === sessionId ? { ...s, messages } : s));
      }
      const sessionData = (data as any).session;
      if (sessionData) {
        setSessions(prev => prev.map(s => {
          if (s.id === sessionId) {
            return {
              ...s,
              intent: sessionData.intent || s.intent,
              status: sessionData.status || s.status,
              satisfaction: sessionData.satisfaction ?? s.satisfaction,
            };
          }
          return s;
        }));
      }
    } catch (e) { console.error('loadSessionMessages error:', e); }
  }, []);

  const deleteSession = useCallback(async (sessionId: string): Promise<string | null> => {
    try {
      await sessionApi.deleteSession(sessionId);
      let navigateTo: string | null = null;
      setSessions(prev => {
        const filtered = prev.filter(s => s.id !== sessionId);
        return filtered;
      });
      const remaining = sessions.filter(s => s.id !== sessionId);
      if (currentSessionId === sessionId) {
        if (remaining.length > 0) { navigateTo = `/chat/${remaining[0].id}`; setCurrentSessionId(remaining[0].id); }
        else { navigateTo = '/'; setCurrentSessionId(null); }
      }
      return navigateTo;
    } catch (e) { console.error(e); return null; }
  }, [sessions, currentSessionId]);

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
    sessions, setSessions,
    currentSessionId, setCurrentSessionId,
    currentSession,
    fetchSessions, loadSessionMessages,
    deleteSession,
    updateSessionModel, updateSession, updateSessionMessages,
  };
}
