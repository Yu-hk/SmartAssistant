import { Message } from '../types';
import { useVoiceOutput } from '../hooks/useVoiceOutput';

export function VoiceReplyControls({message,playback}:{message:Message;playback:ReturnType<typeof useVoiceOutput>}) {
  if(!message.requestId || message.role!=='assistant' || message.isStreaming
    || (message.deliveryStatus && message.deliveryStatus!=='completed') || !message.content?.trim()
    || message.content.trimStart().startsWith('⚠️'))return null;
  const current=playback.state.requestId===message.requestId;
  const phase=current?playback.state.phase:'idle';
  return <div className="voice-reply-controls" aria-label="语音回复控制">
    {phase==='playing'?<button type="button" onClick={playback.pause}>暂停朗读</button>
      : phase==='paused'||phase==='blocked'?<button type="button" onClick={()=>void playback.resume()}>播放语音</button>
      : <button type="button" disabled={phase==='loading'} onClick={()=>void playback.play(message.requestId!)}>
        {phase==='loading'?'正在生成语音…':phase==='error'?'重试朗读':'朗读回复'}</button>}
    {current && ['loading','playing','paused','blocked'].includes(phase) && <button type="button" onClick={playback.stop}>停止播报</button>}
    <span>AI 合成语音</span>
    {current && playback.state.notice && <span role="status">{playback.state.notice}</span>}
  </div>;
}
