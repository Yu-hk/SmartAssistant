import { authenticatedFetch } from './client';

export interface SpeechTranscript {
  text: string;
  model: string;
  durationSeconds: number;
  usage: { promptTokens: number | null; completionTokens: number | null; totalTokens: number | null };
}

export async function speechCapabilities(signal: AbortSignal): Promise<boolean> {
  const response = await authenticatedFetch('/api/speech/capabilities', { signal });
  if (!response.ok) throw new Error('暂时无法连接语音服务，请使用文字输入');
  return (await response.json()).enabled === true;
}

export async function transcribeSpeech(audio: Blob, signal: AbortSignal): Promise<SpeechTranscript> {
  const response = await authenticatedFetch('/api/speech/transcriptions', {
    method: 'POST', headers: { 'Content-Type': 'audio/wav' }, body: audio, signal,
  });
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: '录音格式无效，请重新录音', 401: '登录已失效，请重新登录',
      413: '录音过大，请缩短录音时间', 422: '未识别到有效语音，请重新录音',
      429: '语音请求过于频繁，请稍后重试', 503: '语音识别暂未启用，请使用文字输入',
      504: '语音识别超时，请重试或使用文字输入',
    };
    // Do not display raw reverse-proxy/provider errors or HTML to users.
    throw new Error(messages[response.status] || '语音识别失败，请重试或使用文字输入');
  }
  const result = await response.json() as SpeechTranscript;
  if (typeof result.text !== 'string' || !result.text.trim() || result.text.length > 4000) {
    throw new Error('未获得有效的识别文字，请重新录音');
  }
  return result;
}
