type RemoteSession = { id?: string; sessionId?: string; status?: string };

type SessionSwitchApi = {
  fetchSessions: () => Promise<RemoteSession[]>;
  closeSession: (sessionId: string) => Promise<unknown>;
  resumeSession: (sessionId: string) => Promise<unknown>;
};

const lifecycle = (status: unknown) => String(status ?? '').trim().toUpperCase();
const sessionId = (session: RemoteSession) => session.id || session.sessionId || '';
const isClosed = (status: unknown) => ['CLOSED', 'CLOSE', 'ENDED', 'TERMINATED'].includes(lifecycle(status));
const isSuspended = (status: unknown) => ['SUSPENDED', 'FROZEN'].includes(lifecycle(status));

/** A user-initiated switch may release an idle owner, but never an in-flight turn. */
export async function prepareConversationSwitch(
  targetSessionId: string | null,
  api: SessionSwitchApi,
): Promise<{ closedSessionIds: string[]; resumed: boolean }> {
  const sessions = await api.fetchSessions();
  const target = sessions.find(session => sessionId(session) === targetSessionId);
  if (target && isClosed(target.status)) {
    throw new Error('这条对话已结束，请新建会话。');
  }

  const closedSessionIds: string[] = [];
  for (const session of sessions) {
    const id = sessionId(session);
    // The sessions API exposes the last turn result (SUCCESS/FAILED/TIMEOUT)
    // for an unfinished owner. Those values are active lifecycle sessions too.
    if (!id || id === targetSessionId || isClosed(session.status) || isSuspended(session.status)) {
      continue;
    }
    // The close API rejects BUSY while a request is running. Stop immediately;
    // never submit a new turn that would become a suspended ghost conversation.
    await api.closeSession(id);
    closedSessionIds.push(id);
  }

  const resumed = Boolean(target && isSuspended(target.status));
  if (resumed && targetSessionId) await api.resumeSession(targetSessionId);
  return { closedSessionIds, resumed };
}
