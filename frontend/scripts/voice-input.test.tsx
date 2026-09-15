import { after, beforeEach, afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { JSDOM } from 'jsdom';
import { useVoiceInput } from '../src/hooks/useVoiceInput';
import { encodePcmWav, appendTranscript, recordingToWav } from '../src/audio/voiceAudio';
import { transcribeSpeech } from '../src/api/speech';

const dom = new JSDOM('<div id="root"></div>', { url: 'https://voice.test' });
const originals = new Map<string, PropertyDescriptor | undefined>();
function global(name: string, value: unknown) {
  if (!originals.has(name)) originals.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}
global('window', dom.window); global('document', dom.window.document); global('navigator', dom.window.navigator);
global('localStorage', dom.window.localStorage); global('sessionStorage', dom.window.sessionStorage);
global('IS_REACT_ACT_ENVIRONMENT', true);
Object.defineProperty(dom.window, 'isSecureContext', { value: true, configurable: true });
let tracks: { onended: (() => void) | null; stopped: boolean; stop: () => void }[];
let created: FakeRecorder[];
let uploads: Blob[];
let transcripts: string[];
let voice: ReturnType<typeof useVoiceInput>;
let root: Root;
let decodedSeconds: number;

class FakeRecorder {
  static isTypeSupported(type: string) { return type.startsWith('audio/webm'); }
  state = 'inactive'; mimeType = 'audio/webm';
  ondataavailable: ((event: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor() { created.push(this); }
  start() { this.state = 'recording'; }
  stop() {
    this.state = 'inactive';
    queueMicrotask(() => { this.ondataavailable?.({ data: new Blob(['fake-recorded-audio']) }); this.onstop?.(); });
  }
}
class FakeAudioContext {
  async decodeAudioData() { return { duration: decodedSeconds }; }
  async close() {}
}
class FakeOfflineContext {
  frames: number;
  destination = {};
  constructor(_channels: number, frames: number) { this.frames = frames; }
  createBufferSource() { return { buffer: null, connect() {}, start() {} }; }
  async startRendering() { return { getChannelData: () => new Float32Array(this.frames).fill(.1) }; }
}
function media() { return { getTracks: () => tracks, getAudioTracks: () => tracks }; }
function Harness({ disabled = false }: { disabled?: boolean }) {
  voice = useVoiceInput(disabled, text => transcripts.push(text)); return null;
}
async function flush() { await act(async () => { await new Promise(resolve => setTimeout(resolve, 10)); }); }
async function start() { await act(async () => { await voice.start(); }); }
async function stop() { await act(async () => voice.stop()); await flush(); }
function respond(text = '请查订单 ORD-1001') {
  return new Response(JSON.stringify({ text, model: 'qwen3-asr-flash', durationSeconds: 1,
    usage: { promptTokens: 25, completionTokens: 8, totalTokens: 33 } }), { status: 200 });
}

beforeEach(async () => {
  tracks = [{ onended: null, stopped: false, stop() { this.stopped = true; } }];
  created = []; uploads = []; transcripts = []; decodedSeconds = 1;
  global('MediaRecorder', FakeRecorder); global('AudioContext', FakeAudioContext); global('OfflineAudioContext', FakeOfflineContext);
  Object.defineProperty(dom.window.navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: async () => media() } });
  global('fetch', async (input: string, options?: RequestInit) => {
    if (input.endsWith('/capabilities')) return new Response('{"enabled":true}');
    uploads.push(options?.body as Blob); return respond();
  });
  root = createRoot(document.getElementById('root')!);
  await act(async () => root.render(<Harness />));
});
afterEach(async () => { await act(async () => root.unmount()); });
after(() => {
  dom.window.close();
  for (const [name, descriptor] of originals) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor); else Reflect.deleteProperty(globalThis, name);
  }
});

test('PCM encoder emits the exact server contract with bounded samples', async () => {
  const file = encodePcmWav(new Float32Array([-2, -.5, 0, .5, 2]));
  const view = new DataView(await file.arrayBuffer());
  assert.equal(file.type, 'audio/wav'); assert.equal(file.size, 54);
  assert.equal(view.getUint32(24, true), 16000); assert.equal(view.getUint16(22, true), 1);
  assert.equal(view.getInt16(44, true), -32768); assert.equal(view.getInt16(52, true), 32767);
  assert.throws(() => encodePcmWav(new Float32Array(960001)));
});
test('transcript preserves the entire existing draft', () => {
  assert.equal(appendTranscript('原有问题', '查询物流'), '原有问题\n查询物流');
  assert.equal(appendTranscript('', '查询物流'), '查询物流');
  assert.equal(appendTranscript('问题\n', '查询物流'), '问题\n查询物流');
});
test('records, releases microphone, converts to WAV and returns text without sending chat', async () => {
  await start(); assert.equal(voice.phase, 'recording');
  await stop(); assert.equal(voice.phase, 'idle');
  assert.deepEqual(transcripts, ['请查订单 ORD-1001']); assert.equal(uploads.length, 1);
  assert.equal(uploads[0].type, 'audio/wav'); assert.equal(uploads[0].size, 32044);
  assert.equal(tracks[0].stopped, true); assert.match(voice.notice, /核对/);
});
test('cancel discards recording and sends no audio', async () => {
  await start(); await act(async () => voice.cancel()); await flush();
  assert.equal(voice.phase, 'idle'); assert.equal(tracks[0].stopped, true);
  assert.equal(uploads.length, 0); assert.equal(transcripts.length, 0);
});
test('missing capability never asks for microphone or uploads', async () => {
  global('fetch', async () => new Response('{"enabled":false}'));
  await start(); assert.equal(created.length, 0); assert.equal(uploads.length, 0);
  assert.match(voice.error, /暂未启用/); assert.equal(voice.busy, false);
});
test('permission denied gives actionable feedback and leaves text entry unlocked', async () => {
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: {
    getUserMedia: async () => { throw new DOMException('denied', 'NotAllowedError'); },
  } });
  await start(); assert.match(voice.error, /权限/); assert.equal(voice.busy, false);
});
test('permission granted after cancel immediately closes microphone', async () => {
  let grant!: (value: unknown) => void;
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: {
    getUserMedia: () => new Promise(resolve => { grant = resolve; }),
  } });
  let pending!: Promise<void>;
  await act(async () => { pending = voice.start(); }); await flush();
  await act(async () => voice.cancel());
  await act(async () => { grant(media()); await pending; });
  assert.equal(tracks[0].stopped, true); assert.equal(created.length, 0);
});
test('late model response after cancellation never updates another draft', async () => {
  let resolveModel!: (response: Response) => void;
  global('fetch', async (url: string) => url.endsWith('capabilities') ? new Response('{"enabled":true}')
    : new Promise<Response>(resolve => { resolveModel = resolve; }));
  await start(); await stop(); assert.equal(voice.phase, 'transcribing');
  await act(async () => voice.cancel());
  await act(async () => { resolveModel(respond('迟到文字')); }); await flush();
  assert.equal(transcripts.length, 0); assert.equal(voice.phase, 'idle');
});
test('closed session or in-flight chat disables and cancels voice capture', async () => {
  await start(); await act(async () => root.render(<Harness disabled />)); await flush();
  assert.equal(tracks[0].stopped, true); assert.equal(uploads.length, 0);
  await start(); assert.equal(created.length, 1); assert.equal(voice.phase, 'idle');
});
test('switching session unmounts capture and discards data', async () => {
  await start(); await act(async () => root.render(<Harness key="another-session" />)); await flush();
  assert.equal(tracks[0].stopped, true); assert.equal(uploads.length, 0); assert.equal(transcripts.length, 0);
});
test('double click does not create multiple recordings', async () => {
  await act(async () => { await Promise.all([voice.start(), voice.start()]); });
  assert.equal(created.length, 1);
});
test('automatic deadline stops at 60 seconds', async () => {
  const timers: { callback: () => void; ms: number }[] = [];
  const original = globalThis.setTimeout;
  globalThis.setTimeout = ((callback: () => void, ms: number) => {
    if (ms >= 10000) { timers.push({ callback, ms }); return original(() => {}, 1); }
    return original(callback, ms);
  }) as typeof setTimeout;
  try {
    await start(); assert.equal(voice.phase, 'recording');
    await act(async () => timers.find(timer => timer.ms === 60000)!.callback()); await flush();
    assert.equal(tracks[0].stopped, true); assert.equal(transcripts.length, 1);
  } finally { globalThis.setTimeout = original; }
});
test('decode rejects empty, too-short and excessive recordings', async () => {
  await assert.rejects(recordingToWav(new Blob()), /为空/);
  decodedSeconds = .1; await assert.rejects(recordingToWav(new Blob(['audio'])), /太短/);
  decodedSeconds = 62; await assert.rejects(recordingToWav(new Blob(['audio'])), /超时/);
});
test('unsupported browser reports text fallback without requesting audio', async () => {
  Object.defineProperty(window, 'isSecureContext', { value: false, configurable: true });
  try {
    await start(); assert.match(voice.error, /HTTPS/); assert.equal(created.length, 0);
  } finally { Object.defineProperty(window, 'isSecureContext', { value: true, configurable: true }); }
});
test('microphone removal releases resources without uploading partial data', async () => {
  await start(); await act(async () => tracks[0].onended?.()); await flush();
  assert.equal(tracks[0].stopped, true); assert.match(voice.error, /断开/); assert.equal(uploads.length, 0);
});
test('model timeout unlocks input and ignores a later successful result', async () => {
  let resolveModel!: (response: Response) => void;
  let expire!: () => void;
  global('fetch', async (url: string) => url.endsWith('capabilities') ? new Response('{"enabled":true}')
    : new Promise<Response>(resolve => { resolveModel = resolve; }));
  await start();
  const original = globalThis.setTimeout;
  globalThis.setTimeout = ((callback: () => void, ms: number) => {
    if (ms === 60000) { expire = callback; return original(() => {}, 1); }
    return original(callback, ms);
  }) as typeof setTimeout;
  try {
    await stop(); assert.equal(voice.phase, 'transcribing');
    await act(async () => expire()); assert.equal(voice.busy, false); assert.match(voice.error, /超时/);
    await act(async () => resolveModel(respond())); await flush(); assert.equal(transcripts.length, 0);
  } finally { globalThis.setTimeout = original; }
});
test('ASR API maps provider errors without exposing raw response or injecting HTML', async () => {
  global('fetch', async () => new Response('private-provider-key-canary', { status: 502 }));
  await assert.rejects(transcribeSpeech(new Blob(['audio']), new AbortController().signal), error => {
    assert.ok(error instanceof Error); assert.match(error.message, /识别失败/);
    assert.ok(!error.message.includes('canary')); return true;
  });
});
