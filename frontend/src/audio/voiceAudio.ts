export const MAX_VOICE_SECONDS = 60;
export const VOICE_SAMPLE_RATE = 16000;

export function encodePcmWav(samples: Float32Array): Blob {
  if (!samples.length || samples.length > VOICE_SAMPLE_RATE * MAX_VOICE_SECONDS) {
    throw new Error('录音时长无效，请重新录音');
  }
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const text = (offset: number, value: string) => {
    for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i));
  };
  text(0, 'RIFF'); view.setUint32(4, buffer.byteLength - 8, true); text(8, 'WAVE');
  text(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
  view.setUint16(22, 1, true); view.setUint32(24, VOICE_SAMPLE_RATE, true);
  view.setUint32(28, VOICE_SAMPLE_RATE * 2, true); view.setUint16(32, 2, true);
  view.setUint16(34, 16, true); text(36, 'data'); view.setUint32(40, samples.length * 2, true);
  samples.forEach((sample, i) => {
    const value = Number.isFinite(sample) ? Math.max(-1, Math.min(1, sample)) : 0;
    view.setInt16(44 + i * 2, Math.round(value < 0 ? value * 32768 : value * 32767), true);
  });
  return new Blob([buffer], { type: 'audio/wav' });
}

/** Browser-native conversion avoids sending browser-specific WebM/MP4 to an incompatible ASR API. */
export async function recordingToWav(recording: Blob): Promise<Blob> {
  if (!recording.size) throw new Error('录音为空，请检查麦克风后重试');
  const context = new AudioContext();
  try {
    const decoded = await context.decodeAudioData(await recording.arrayBuffer());
    if (decoded.duration < .3) throw new Error('录音太短，请至少说一句话');
    if (decoded.duration > MAX_VOICE_SECONDS + 1) throw new Error('录音超时，请重新录制不超过 60 秒的语音');
    // Allow the recorder's final packet at the deadline, but upload at most exactly 60 seconds.
    const frames = Math.min(Math.ceil(decoded.duration * VOICE_SAMPLE_RATE), MAX_VOICE_SECONDS * VOICE_SAMPLE_RATE);
    const offline = new OfflineAudioContext(1, frames, VOICE_SAMPLE_RATE);
    const source = offline.createBufferSource();
    source.buffer = decoded; source.connect(offline.destination); source.start();
    const samples = (await offline.startRendering()).getChannelData(0);
    if (!samples.some(value => Math.abs(value) > .001)) throw new Error('录音中未检测到声音，请检查麦克风');
    return encodePcmWav(samples);
  } finally {
    await context.close();
  }
}

export function appendTranscript(draft: string, text: string): string {
  return draft ? `${draft}${/\s$/.test(draft) ? '' : '\n'}${text}` : text;
}
