import {test,beforeEach,afterEach} from 'node:test';
import assert from 'node:assert/strict';
import React,{act} from 'react';
import {createRoot,Root} from 'react-dom/client';
import {JSDOM} from 'jsdom';
import {useVoiceOutput} from '../src/hooks/useVoiceOutput';
const dom=new JSDOM('<div id="root"></div>',{url:'https://voice.test'});
Object.assign(globalThis,{window:dom.window,document:dom.window.document,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true});
let hook:ReturnType<typeof useVoiceOutput>,root:Root,calls:number,revoked:number,players:FakeAudio[],blocked:boolean;
class FakeAudio {
  onended:(()=>void)|null=null;onerror:(()=>void)|null=null;paused=false;
  constructor(public src:string){players.push(this);}
  async play(){if(blocked)throw new Error('autoplay blocked');this.paused=false;}
  pause(){this.paused=true;}removeAttribute(){this.src='';}load(){}
}
function Harness({scope='session'}:{scope?:string}){hook=useVoiceOutput(scope);return null;}
beforeEach(async()=>{
  calls=0;revoked=0;players=[];blocked=false;
  Object.assign(globalThis,{Audio:FakeAudio,fetch:async(_url:string,init:RequestInit)=>{calls++;assert.deepEqual(JSON.parse(init.body as string),{requestId:'request'});return new Response(new Blob(['ID3audio']),{headers:{'content-type':'audio/mpeg'}});}});
  URL.createObjectURL=()=>`blob:audio-${calls}`;URL.revokeObjectURL=()=>{revoked++;};
  localStorage.setItem('smart-assistant-token','test-only');
  root=createRoot(document.getElementById('root')!);await act(async()=>root.render(<Harness/>));
});
afterEach(async()=>{await act(async()=>root.unmount());});
test('plays pauses resumes and stops without another synthesis',async()=>{
 await act(async()=>hook.play('request'));assert.equal(hook.state.phase,'playing');
 await act(async()=>hook.pause());assert.equal(hook.state.phase,'paused');
 await act(async()=>hook.resume());assert.equal(hook.state.phase,'playing');
 await act(async()=>hook.stop());assert.equal(hook.state.phase,'idle');assert.equal(calls,1);assert.ok(players[0].paused);
});
test('replay uses bounded page-local audio cache',async()=>{
 await act(async()=>hook.play('request'));await act(async()=>hook.stop());await act(async()=>hook.play('request'));assert.equal(calls,1);
});
test('autoplay rejection allows explicit playback without resynthesis',async()=>{
 blocked=true;await act(async()=>hook.play('request'));assert.equal(hook.state.phase,'blocked');
 blocked=false;await act(async()=>hook.resume());assert.equal(hook.state.phase,'playing');assert.equal(calls,1);
});
test('session switch stops audio and revokes cached private URLs',async()=>{
 await act(async()=>hook.play('request'));await act(async()=>root.render(<Harness scope="another"/>));
 assert.ok(players[0].paused);assert.equal(revoked,1);assert.equal(hook.state.phase,'idle');
});
test('late synthesis after cancellation cannot play',async()=>{
 let resolve!:(response:Response)=>void;
 globalThis.fetch=()=>new Promise(r=>{resolve=r;});
 let pending!:Promise<void>;await act(async()=>{pending=hook.play('request');});
 await act(async()=>hook.stop());
 await act(async()=>{resolve(new Response(new Blob(['ID3audio']),{headers:{'content-type':'audio/mpeg'}}));await pending;});
 assert.equal(players.length,0);assert.equal(hook.state.phase,'idle');
});
test('provider errors do not expose raw bodies and text remains usable',async()=>{
 globalThis.fetch=async()=>new Response('secret raw error',{status:502});
 await act(async()=>hook.play('request'));assert.equal(hook.state.phase,'error');assert.ok(!hook.state.notice.includes('secret'));
});
test('invalid audio MIME is rejected',async()=>{
 globalThis.fetch=async()=>new Response('<html>error</html>',{headers:{'content-type':'text/html'}});
 await act(async()=>hook.play('request'));assert.equal(hook.state.phase,'error');assert.equal(players.length,0);
});
