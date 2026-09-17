import { test } from 'node:test';
import assert from 'node:assert/strict';
import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { JSDOM } from 'jsdom';
import { useSessions } from '../src/hooks/useSessions';
import { useChat } from '../src/hooks/useChat';

test('conflict refresh exposes owning session; resume errors retain owner; busy delete stays visible', async () => {
 const dom = new JSDOM('<div id="root"></div>', {url:'https://gate.test'});
 const saved = new Map<string, PropertyDescriptor | undefined>();
 const set = (name: string, value: unknown) => { saved.set(name, Object.getOwnPropertyDescriptor(globalThis,name)); Object.defineProperty(globalThis,name,{value,writable:true,configurable:true}); };
 for (const [name,value] of Object.entries({window:dom.window,document:dom.window.document,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true})) set(name,value);
 let hooks: ReturnType<typeof useSessions>, chat: ReturnType<typeof useChat>, owner: string|undefined;
 const requests: string[]=[];
 set('fetch', async (input: RequestInfo|URL, init?:RequestInit) => {
   const url=String(input);requests.push(url);
   if(url.includes('/stream/chat')) return new Response('event: conversation_suspended\ndata: {"type":"conversation_suspended","activeSessionId":"owner","sessionId":"new"}\n\nevent: done\ndata: {"type":"done"}\n\n',{headers:{'Content-Type':'text/event-stream'}});
   if(url.endsWith('/resume')) return new Response(JSON.stringify({message:'已有进行中的会话',activeSessionId:'owner'}),{status:409});
   if(init?.method==='DELETE') return new Response(JSON.stringify({message:'当前对话仍在处理请求'}),{status:409});
   if(url.endsWith('/sessions')) return Response.json([{id:'owner',title:'占用对话',status:'ACTIVE_IDLE'}]);
   return Response.json({id:'new',status:'SUSPENDED',messages:[]});
 });
 function Harness(){hooks=useSessions();chat=useChat({currentSession:hooks.currentSession,currentSessionId:hooks.currentSessionId,selectedModel:'test',setSessions:hooks.setSessions,setCurrentSessionId:hooks.setCurrentSessionId,onConversationConflict:id=>{owner=id;void hooks.fetchSessions();}});return null;}
 const root=createRoot(dom.window.document.getElementById('root')!);
 try {
   await act(async()=>{root.render(<Harness/>);});
   await act(async()=>{await chat!.sendMessage('查订单','new');});
   assert.equal(owner,'owner');assert.ok(requests.some(url=>url.endsWith('/sessions')));
   assert.ok(hooks!.sessions.some(s=>s.id==='owner'));
   await act(async()=>{await hooks!.resumeSession('new');});
   assert.equal(hooks!.blockingSessionId,'owner');
   await act(async()=>{await hooks!.deleteSession('owner');});
   assert.ok(hooks!.sessions.some(s=>s.id==='owner'));assert.match(hooks!.sessionActionError!,/仍在处理/);
 } finally {await act(async()=>root.unmount());for(const [name,descriptor] of saved){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}dom.window.close();}
});
