import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyTelemetryEvent, normalizeTelemetry, summarizeTelemetry, tokenNumber } from '../src/utils/sessionTelemetry';
import { getToolCapabilityLabel } from '../src/utils/toolDisplay';
import type { Message } from '../src/types';

const assistant = (id: string, patch: Partial<Message> = {}): Message => ({
  id, role: 'assistant', content: '', timestamp: new Date(), ...patch,
});

test('token snapshots replace instead of accumulating duplicate events', () => {
  const event = { type: 'token_usage', promptTokens: 100, completionTokens: 20, totalTokens: 120 };
  let message = assistant('a');
  message = { ...message, ...applyTelemetryEvent(message, event) };
  message = { ...message, ...applyTelemetryEvent(message, event) };
  assert.equal(summarizeTelemetry([message]).totalTokens, 120);
});

test('missing usage remains unknown and measured zero stays zero', () => {
  assert.equal(summarizeTelemetry([assistant('a')]).totalTokens, null);
  assert.equal(summarizeTelemetry([assistant('a', normalizeTelemetry({ totalTokens: 0 }))]).totalTokens, 0);
  for (const invalid of [null, undefined, '', true, -1, NaN, Infinity, 'bad']) assert.equal(tokenNumber(invalid), null);
});

test('partial node snapshots update during execution and unknown final usage stays partial', () => {
  let message = assistant('a', { isStreaming: true });
  message = { ...message, ...applyTelemetryEvent(message,
    { type: 'token_usage', totalTokens: 100, tokenUsageComplete: false }) };
  assert.equal(summarizeTelemetry([message]).totalTokens, 100);
  assert.equal(summarizeTelemetry([message]).tokensComplete, false);
  message = { ...message, ...applyTelemetryEvent(message,
    { type: 'token_usage', tokenUsageComplete: false }), isStreaming: false };
  assert.equal(summarizeTelemetry([message]).totalTokens, 100);
  assert.equal(summarizeTelemetry([message]).tokensComplete, false);
  message = { ...message, ...applyTelemetryEvent(message,
    { type: 'token_usage', totalTokens: 250, tokenUsageComplete: true }) };
  assert.equal(summarizeTelemetry([message]).totalTokens, 250);
  assert.equal(summarizeTelemetry([message]).tokensComplete, true);
});

test('history snake-case fields and server tool outcomes are normalized', () => {
  const data = normalizeTelemetry({ prompt_tokens: 70, completion_tokens: 30, tool_usage_complete: true,
    tool_calls: [{ name: 'queryWeather', status: 'SUCCESS', duration_ms: 42 }] });
  assert.equal(data.totalTokens, 100);
  assert.equal(data.toolCalls?.[0].status, 'completed');
  assert.equal(data.toolCalls?.[0].durationMs, 42);
  assert.equal(data.toolUsageComplete, true);
});

test('parallel tool results update matching calls and duplicate starts are ignored', () => {
  let message = assistant('a');
  for (const event of [
    { type: 'tool_call', id: 'weather', toolName: 'queryWeather' },
    { type: 'tool', id: 'search', name: 'searchWeb' },
    { type: 'tool_call', id: 'weather', toolName: 'queryWeather' },
    { type: 'tool_result', toolId: 'search', isError: true, durationMs: 30 },
  ]) message = { ...message, ...applyTelemetryEvent(message, event) };
  assert.equal(message.toolCalls?.length, 2);
  assert.equal(message.toolCalls?.[0].status, 'running');
  assert.equal(message.toolCalls?.[1].status, 'error');
});

test('final tool snapshot replaces live records and repeated same-name calls are retained', () => {
  let message = assistant('a', { toolCalls: [{ id: 'live', name: 'queryWeather', status: 'running' }] });
  const event = { type: 'tool_usage', toolUsageComplete: true, toolCalls: [
    { name: 'queryWeather', status: 'SUCCESS', durationMs: 10 },
    { name: 'queryWeather', status: 'SUCCESS', durationMs: 20 },
  ] };
  for (let i = 0; i < 2; i++) message = { ...message, ...applyTelemetryEvent(message, event) };
  assert.equal(summarizeTelemetry([message]).calls.length, 2);
  assert.equal(summarizeTelemetry([message]).toolsComplete, true);
});

test('session switching, multi-turn totals and partial collection remain isolated', () => {
  const first = [assistant('a', { totalTokens: 100, promptTokens: 80, completionTokens: 20 }), assistant('b')];
  const second = [assistant('c', { totalTokens: 40, toolUsageComplete: true })];
  assert.equal(summarizeTelemetry(first).totalTokens, 100);
  assert.equal(summarizeTelemetry(first).tokensComplete, false);
  assert.equal(summarizeTelemetry(second).totalTokens, 40);
  assert.equal(summarizeTelemetry(second).tokensComplete, true);
  assert.equal(summarizeTelemetry(second).calls.length, 0);
  assert.equal(summarizeTelemetry(second).toolsComplete, true);
});

test('capability labels do not reveal internal function names', () => {
  assert.equal(getToolCapabilityLabel('query_weather'), '天气查询');
  assert.equal(getToolCapabilityLabel('searchWeb'), '联网搜索');
  assert.equal(getToolCapabilityLabel('internal_private_fn'), '智能能力');
});
