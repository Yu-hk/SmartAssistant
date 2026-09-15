import { useCallback, useEffect, useRef, useState } from 'react';
import { speechCapabilities, transcribeSpeech } from '../api/speech';
import { MAX_VOICE_SECONDS, recordingToWav } from '../audio/voiceAudio';

type VoicePhase = 'idle' | 'starting' | 'recording' | 'transcribing';

export function useVoiceInput(disabled: boolean, onTranscript: (text: string) => void) {
  const [phase, setPhase] = useState<VoicePhase>('idle');
  const [seconds, setSeconds] = useState(0);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const generation = useRef(0);
  const busy = useRef(false);
  const stream = useRef<MediaStream | null>(null);
  const recorder = useRef<MediaRecorder | null>(null);
  const request = useRef<AbortController | null>(null);
  const ticker = useRef<ReturnType<typeof setInterval>>();
  const deadline = useRef<ReturnType<typeof setTimeout>>();
  const callback = useRef(onTranscript);
  callback.current = onTranscript;
  const disabledRef = useRef(disabled);
  disabledRef.current = disabled;

  const clearTimers = useCallback(() => {
    clearInterval(ticker.current); clearTimeout(deadline.current);
  }, []);
  const releaseMic = useCallback(() => {
    stream.current?.getTracks().forEach(track => { track.onended = null; track.stop(); });
    stream.current = null;
  }, []);
  const cleanup = useCallback(() => {
    generation.current++;
    clearTimers(); request.current?.abort(); request.current = null;
    const current = recorder.current;
    if (current) {
      current.onstop = null; current.ondataavailable = null; current.onerror = null;
      if (current.state !== 'inactive') current.stop();
    }
    recorder.current = null; releaseMic(); busy.current = false;
  }, [clearTimers, releaseMic]);

  const cancel = useCallback(() => {
    cleanup(); setPhase('idle'); setError(''); setNotice('已取消语音输入，原有文字已保留');
  }, [cleanup]);

  useEffect(() => () => cleanup(), [cleanup]);
  useEffect(() => { if (disabled) { cleanup(); setPhase('idle'); } }, [disabled, cleanup]);
  useEffect(() => {
    const hide = () => { if (document.hidden && busy.current) cancel(); };
    document.addEventListener('visibilitychange', hide);
    return () => document.removeEventListener('visibilitychange', hide);
  }, [cancel]);

  const stop = useCallback(() => {
    if (recorder.current?.state === 'recording') {
      clearTimers(); setPhase('transcribing'); recorder.current.stop(); releaseMic();
    }
  }, [clearTimers, releaseMic]);

  const start = useCallback(async () => {
    if (busy.current || disabledRef.current) return;
    setError(''); setNotice(''); setSeconds(0);
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia
        || typeof MediaRecorder === 'undefined' || typeof AudioContext === 'undefined'
        || typeof OfflineAudioContext === 'undefined') {
      setError('当前环境不支持录音，请使用 HTTPS 下的新版 Edge、Chrome 或 Safari，或直接输入文字');
      return;
    }
    busy.current = true; setPhase('starting');
    const id = ++generation.current;
    const current = () => id === generation.current && !disabledRef.current;
    const fail = (message: string) => {
      if (!current()) return;
      cleanup(); setPhase('idle'); setError(message);
    };
    try {
      const capabilityAbort = new AbortController(); request.current = capabilityAbort;
      deadline.current = setTimeout(() => capabilityAbort.abort(), 10000);
      const enabled = await speechCapabilities(capabilityAbort.signal);
      if (!current()) return;
      clearTimers();
      if (!enabled) { fail('语音识别暂未启用，请使用文字输入'); return; }
      // A pending permission dialog must not trap the composer indefinitely.
      deadline.current = setTimeout(() => fail('麦克风授权等待超时，请重新点击语音输入'), 30000);
      const media = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }, video: false,
      });
      if (!current()) { media.getTracks().forEach(track => track.stop()); return; }
      clearTimers(); stream.current = media;
      media.getAudioTracks().forEach(track => { track.onended = () => fail('麦克风已断开，请检查设备后重试'); });
      const mimeType = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/ogg;codecs=opus']
        .find(type => MediaRecorder.isTypeSupported(type));
      const capture = new MediaRecorder(media, mimeType ? { mimeType, audioBitsPerSecond: 64000 } : undefined);
      recorder.current = capture;
      const chunks: Blob[] = [];
      let byteCount = 0;
      capture.ondataavailable = event => {
        if (!current()) return;
        byteCount += event.data.size;
        if (byteCount > 8 * 1024 * 1024) { fail('录音过大，请缩短录音时间'); return; }
        if (event.data.size) chunks.push(event.data);
      };
      capture.onerror = () => fail('录音失败，请检查麦克风后重试');
      capture.onstop = async () => {
        if (!current()) return;
        clearTimers(); releaseMic(); recorder.current = null; setPhase('transcribing');
        const abort = new AbortController(); request.current = abort;
        // Covers decoding, resampling and upload as well as the model request.
        deadline.current = setTimeout(() => fail('语音识别超时，请重试或使用文字输入'), 60000);
        try {
          const wav = await recordingToWav(new Blob(chunks, { type: capture.mimeType }));
          chunks.length = 0;
          if (!current()) return;
          const result = await transcribeSpeech(wav, abort.signal);
          if (!current()) return;
          const apply = callback.current;
          cleanup(); setPhase('idle');
          setNotice('已转为文字，请核对商品名、金额和订单号后发送');
          apply(result.text.trim());
        } catch (e) {
          fail(e instanceof Error ? e.message : '语音识别失败，请使用文字输入');
        }
      };
      capture.start(250); setPhase('recording');
      const startedAt = Date.now();
      ticker.current = setInterval(() => setSeconds(Math.min(MAX_VOICE_SECONDS, Math.floor((Date.now() - startedAt) / 1000))), 250);
      deadline.current = setTimeout(stop, MAX_VOICE_SECONDS * 1000);
    } catch (e) {
      const name = e instanceof Error ? e.name : '';
      fail(name === 'NotAllowedError' ? '麦克风权限未开启，请在浏览器站点设置中允许麦克风访问'
        : name === 'NotFoundError' ? '未找到麦克风，请连接设备后重试'
        : name === 'AbortError' ? '连接语音服务超时，请稍后重试'
        : '无法启动语音输入，请检查麦克风和网络，或使用文字输入');
    }
  }, [cleanup, clearTimers, releaseMic, stop]);

  return { phase, seconds, notice, error, busy: phase !== 'idle', start, stop, cancel };
}
