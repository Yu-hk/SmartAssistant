import { test } from 'node:test';
import assert from 'node:assert/strict';
import { prepareConversationSwitch } from '../src/utils/conversationSwitch';

test('starting a new conversation closes an idle owner reported as SUCCESS', async () => {
  const calls: string[] = [];
  const result = await prepareConversationSwitch(null, {
    fetchSessions: async () => [{ id: 'old', status: 'SUCCESS' }],
    closeSession: async id => { calls.push(`close:${id}`); },
    resumeSession: async id => { calls.push(`resume:${id}`); },
  });
  assert.deepEqual(calls, ['close:old']);
  assert.deepEqual(result, { closedSessionIds: ['old'], resumed: false });
});

test('a failed or timed-out last turn still leaves a conversation occupying the account', async () => {
  const calls: string[] = [];
  await prepareConversationSwitch(null, {
    fetchSessions: async () => [
      { id: 'failed', status: 'FAILED' },
      { id: 'timed-out', status: 'TIMEOUT' },
      { id: 'already-ended', status: 'CLOSED' },
      { id: 'paused', status: 'SUSPENDED' },
    ],
    closeSession: async id => { calls.push(id); },
    resumeSession: async () => {},
  });
  assert.deepEqual(calls, ['failed', 'timed-out']);
});

test('a busy owner blocks switching without resuming or sending a new turn', async () => {
  const calls: string[] = [];
  await assert.rejects(prepareConversationSwitch('paused', {
    fetchSessions: async () => [
      { id: 'old', status: 'ACTIVE_RUNNING' },
      { id: 'paused', status: 'SUSPENDED' },
    ],
    closeSession: async id => { calls.push(`close:${id}`); throw new Error('BUSY'); },
    resumeSession: async id => { calls.push(`resume:${id}`); },
  }), /BUSY/);
  assert.deepEqual(calls, ['close:old']);
});

test('restoring a paused conversation releases the idle owner first', async () => {
  const calls: string[] = [];
  const result = await prepareConversationSwitch('paused', {
    fetchSessions: async () => [
      { id: 'old', status: 'ACTIVE_IDLE' },
      { id: 'paused', status: 'SUSPENDED' },
    ],
    closeSession: async id => { calls.push(`close:${id}`); },
    resumeSession: async id => { calls.push(`resume:${id}`); },
  });
  assert.deepEqual(calls, ['close:old', 'resume:paused']);
  assert.deepEqual(result, { closedSessionIds: ['old'], resumed: true });
});

test('continuing the owner never closes itself and closed sessions cannot reopen', async () => {
  const calls: string[] = [];
  const api = {
    fetchSessions: async () => [{ id: 'owner', status: 'ACTIVE_IDLE' }],
    closeSession: async (id: string) => { calls.push(`close:${id}`); },
    resumeSession: async (id: string) => { calls.push(`resume:${id}`); },
  };
  assert.deepEqual(await prepareConversationSwitch('owner', api), { closedSessionIds: [], resumed: false });
  assert.deepEqual(calls, []);
  await assert.rejects(prepareConversationSwitch('ended', {
    ...api,
    fetchSessions: async () => [{ id: 'owner', status: 'ACTIVE_IDLE' }, { id: 'ended', status: 'CLOSED' }],
  }), /已结束/);
  assert.deepEqual(calls, []);
});
