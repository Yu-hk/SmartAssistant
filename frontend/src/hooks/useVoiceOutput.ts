import { useCallback, useEffect, useRef, useState } from 'react';
import { authenticatedFetch } from '../api/client';

export type PlaybackPhase = 'idle' | 'loading' | 'playing' | 'paused' | 'blocked' | 'ended' | 'error';
export function useVoiceOutput(scope: string) {
  const [state, setState] = useState<{requestId?: string; phase: PlaybackPhase; notice: string}>({phase:'idle',notice:''});
  const audio = useRef<HTMLAudioElement | null>(null);
  const pending = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const cache = useRef(new Map<string,string>());
  const stop = useCallback(() => {
    generation.current++;pending.current?.abort();pending.current=null;
    if(audio.current){audio.current.pause();audio.current.removeAttribute('src');audio.current.load();audio.current=null;}
    setState({phase:'idle',notice:''});
  },[]);
  useEffect(() => {
    stop();
    return () => {stop();cache.current.forEach(url=>URL.revokeObjectURL(url));cache.current.clear();};
  },[scope,stop]);
  useEffect(()=>{
    const hide=()=>{if(document.hidden)stop();};
    document.addEventListener('visibilitychange',hide);
    return ()=>document.removeEventListener('visibilitychange',hide);
  },[stop]);
  const play = useCallback(async(requestId: string) => {
    stop();const id=generation.current;const controller=new AbortController();pending.current=controller;
    setState({requestId,phase:'loading',notice:'正在生成语音…'});
    const timer=setTimeout(()=>controller.abort(),60000);
    try {
      let url=cache.current.get(requestId);
      if(!url){
        const response=await authenticatedFetch('/api/speech/syntheses',{
          method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({requestId}),signal:controller.signal,
        });
        if(!response.ok){
          const errors:Record<number,string>={401:'登录已失效，请重新登录',404:'回复已过期或不可访问，请查看文字',409:'回复尚未完成，暂不能播报',422:'回复较长或不适合播报，请查看完整文字',429:'语音请求过于频繁，请稍后重试',503:'语音回复暂不可用，请查看文字',504:'语音生成超时，请重试'};
          throw new Error(errors[response.status]||'语音生成失败，可稍后重试，文字回复不受影响');
        }
        if(!response.headers.get('content-type')?.startsWith('audio/mpeg'))throw new Error('语音格式异常');
        const blob=await response.blob();
        if(id!==generation.current)return;
        if(!blob.size || blob.size>8*1024*1024)throw new Error('语音内容异常，请查看文字回复');
        url=URL.createObjectURL(blob);cache.current.set(requestId,url);
        if(cache.current.size>3){const oldest=cache.current.keys().next().value!;URL.revokeObjectURL(cache.current.get(oldest)!);cache.current.delete(oldest);}
      }
      if(id!==generation.current)return;
      const player=new Audio(url);audio.current=player;
      player.onended=()=>{if(id===generation.current)setState({requestId,phase:'ended',notice:''});};
      player.onerror=()=>{if(id===generation.current)setState({requestId,phase:'error',notice:'播放失败，请重试或查看文字'});};
      try{await player.play();if(id===generation.current)setState({requestId,phase:'playing',notice:''});}
      catch{if(id===generation.current)setState({requestId,phase:'blocked',notice:'浏览器未自动播放，请点击播放'});}
    }catch(error){
      if(id===generation.current)setState({requestId,phase:'error',notice:controller.signal.aborted?'语音生成超时，请重试':error instanceof Error?error.message:'语音暂不可用'});
    }finally{clearTimeout(timer);if(id===generation.current)pending.current=null;}
  },[stop]);
  const pause=useCallback(()=>{audio.current?.pause();setState(s=>({...s,phase:'paused'}));},[]);
  const resume=useCallback(async()=>{
    const id=generation.current;const player=audio.current;if(!player)return;
    try{await player.play();if(id===generation.current)setState(s=>({...s,phase:'playing',notice:''}));}
    catch{if(id===generation.current)setState(s=>({...s,phase:'blocked',notice:'请点击播放或检查浏览器声音权限'}));}
  },[]);
  return {state,play,pause,resume,stop};
}
