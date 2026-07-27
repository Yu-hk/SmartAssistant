import { useState, useEffect, useCallback } from 'react';
import { v4 as uuidv4 } from 'uuid';
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
    const sessionId = uuidv4();
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
    fetchSessions, loadSessionMessages, createSession,
    deleteSession,
    updateSessionModel, updateSession, updateSessionMessages,
  };
}
