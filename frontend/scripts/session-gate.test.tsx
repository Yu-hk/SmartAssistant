import { test } from 'node:test';
import assert from 'node:assert/strict';
import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { JSDOM } from 'jsdom';
import { useSessions } from '../src/hooks/useSessions';
import { useChat } from '../src/hooks/useChat';
import { deleteSessionWhenIdle } from '../src/api/sessions';

test('transport failure uses a helpful public message without encouraging duplicate operations', async () => {
 const dom = new JSDOM('<div id="root"></div>', {url:'https://gate.test'});
 const saved = new Map<string, PropertyDescriptor | undefined>();
 const set = (name: string, value: unknown) => { saved.set(name,Object.getOwnPropertyDescriptor(globalThis,name)); Object.defineProperty(globalThis,name,{value,writable:true,configurable:true}); };
 for (const [name,value] of Object.entries({window:dom.window,document:dom.window.document,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true})) set(name,value);
 let hooks: ReturnType<typeof useSessions>, chat: ReturnType<typeof useChat>;
 set('fetch', async (input:RequestInfo|URL) => {
  if(String(input).includes('/stream/chat')) return new Response('private gateway diagnostic',{status:503});
  if(String(input).endsWith('/sessions')) return Response.json([]);
  return Response.json({id:'failure-session',status:'ACTIVE_IDLE',messages:[]});
 });
 function Harness(){hooks=useSessions();chat=useChat({currentSession:hooks.currentSession,currentSessionId:hooks.currentSessionId,selectedModel:'test',setSessions:hooks.setSessions,setCurrentSessionId:hooks.setCurrentSessionId});return null;}
 const root=createRoot(dom.window.document.getElementById('root')!);
 try {
  await act(async()=>root.render(<Harness/>));
  await act(async()=>{await chat!.sendMessage('查订单');});
  const message=hooks!.sessions.flatMap(s=>s.messages).find(m=>m.role==='assistant');
  assert.ok(message);assert.equal(message.deliveryStatus,'failed');
  assert.match(message.content,/没能完整送达.*避免重复提交/);
  assert.doesNotMatch(message.content,/HTTP|private|发生错误|请重试/);
 } finally {await act(async()=>root.unmount());for(const [name,descriptor] of saved){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}dom.window.close();}
});

test('server cancellation is terminal stopped state and never offers recovery', async () => {
 const dom = new JSDOM('<div id="root"></div>', {url:'https://gate.test'});
 const saved = new Map<string, PropertyDescriptor | undefined>();
 const set = (name: string, value: unknown) => { saved.set(name,Object.getOwnPropertyDescriptor(globalThis,name)); Object.defineProperty(globalThis,name,{value,writable:true,configurable:true}); };
 for (const [name,value] of Object.entries({window:dom.window,document:dom.window.document,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true})) set(name,value);
 let hooks: ReturnType<typeof useSessions>, chat: ReturnType<typeof useChat>;
 set('fetch', async (input:RequestInfo|URL) => {
  const url=String(input);
  if(url.includes('/stream/chat')) return new Response('event: cancelled\ndata: {"type":"cancelled"}\n\n',{headers:{'Content-Type':'text/event-stream'}});
  if(url.endsWith('/sessions')) return Response.json([]);
  return Response.json({id:'cancel-session',status:'ACTIVE_IDLE',messages:[]});
 });
 function Harness(){hooks=useSessions();chat=useChat({currentSession:hooks.currentSession,currentSessionId:hooks.currentSessionId,selectedModel:'unknown',setSessions:hooks.setSessions,setCurrentSessionId:hooks.setCurrentSessionId});return null;}
 const root=createRoot(dom.window.document.getElementById('root')!);
 try {
  await act(async()=>{root.render(<Harness/>);});
  await act(async()=>{await chat!.sendMessage('AirPods Pro多少钱？');});
  const message=hooks!.sessions.flatMap(s=>s.messages).find(m=>m.role==='assistant');
  assert.ok(message);assert.equal(message.deliveryStatus,'stopped');assert.equal(message.recoverable,false);
  assert.equal(message.content,'已停止本次回答。');assert.equal(chat!.isLoading,false);
 } finally {await act(async()=>root.unmount());for(const [name,descriptor] of saved){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}dom.window.close();}
});

test('unknown URL session never starts a stream without a visible message container', async () => {
 const dom = new JSDOM('<div id="root"></div>', {url:'https://gate.test/chat/missing'});
 const saved = new Map<string, PropertyDescriptor | undefined>();
 const set = (name: string, value: unknown) => { saved.set(name,Object.getOwnPropertyDescriptor(globalThis,name)); Object.defineProperty(globalThis,name,{value,writable:true,configurable:true}); };
 for (const [name,value] of Object.entries({window:dom.window,document:dom.window.document,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true})) set(name,value);
 const requests: string[] = [];
 set('fetch', async (input:RequestInfo|URL) => { requests.push(String(input)); return Response.json([]); });
 let hooks: ReturnType<typeof useSessions>, chat: ReturnType<typeof useChat>;
 function Harness(){hooks=useSessions();chat=useChat({currentSession:hooks.currentSession,currentSessionId:hooks.currentSessionId,selectedModel:'test',setSessions:hooks.setSessions,setCurrentSessionId:hooks.setCurrentSessionId});return null;}
 const root=createRoot(dom.window.document.getElementById('root')!);
 try {
  await act(async()=>root.render(<Harness/>));
  await act(async()=>hooks!.setCurrentSessionId('missing'));
  await act(async()=>chat!.sendMessage('AirPods Pro多少钱？'));
  assert.equal(chat!.isLoading,false);
  assert.equal(hooks!.sessions.length,0);
  assert.ok(!requests.some(url=>url.includes('/stream/chat')));
 } finally {await act(async()=>root.unmount());for(const [name,descriptor] of saved){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}dom.window.close();}
});

test('new conversation is persisted before its first streaming request', async () => {
 const dom = new JSDOM('<div id="root"></div>', {url:'https://gate.test'});
 const saved = new Map<string, PropertyDescriptor | undefined>();
 const set = (name: string, value: unknown) => { saved.set(name,Object.getOwnPropertyDescriptor(globalThis,name)); Object.defineProperty(globalThis,name,{value,writable:true,configurable:true}); };
 for (const [name,value] of Object.entries({window:dom.window,document:dom.window.document,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true})) set(name,value);
 const requests: string[] = [];
 set('fetch', async (input:RequestInfo|URL) => {
  const url=String(input);requests.push(url);
  if(url.includes('/stream/chat')) return new Response('event: done\ndata: {"type":"done"}\n\n',{headers:{'Content-Type':'text/event-stream'}});
  return Response.json({sessionId:'created'});
 });
 let hooks: ReturnType<typeof useSessions>, chat: ReturnType<typeof useChat>;
 function Harness(){hooks=useSessions();chat=useChat({currentSession:hooks.currentSession,currentSessionId:hooks.currentSessionId,selectedModel:'test',setSessions:hooks.setSessions,setCurrentSessionId:hooks.setCurrentSessionId});return null;}
 const root=createRoot(dom.window.document.getElementById('root')!);
 try {
  await act(async()=>root.render(<Harness/>));
  await act(async()=>chat!.sendMessage('AirPods Pro多少钱？'));
  assert.ok(requests.findIndex(url=>url.endsWith('/sessions'))>=0);
  assert.ok(requests.findIndex(url=>url.endsWith('/sessions'))<requests.findIndex(url=>url.includes('/stream/chat')));
 } finally {await act(async()=>root.unmount());for(const [name,descriptor] of saved){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}dom.window.close();}
});

test('a completed first reply permits the next message in the same conversation', async () => {
 const dom = new JSDOM('<div id="root"></div>', {url:'https://gate.test'});
 const saved = new Map<string, PropertyDescriptor | undefined>();
 const set = (name: string, value: unknown) => { saved.set(name,Object.getOwnPropertyDescriptor(globalThis,name)); Object.defineProperty(globalThis,name,{value,writable:true,configurable:true}); };
 for (const [name,value] of Object.entries({window:dom.window,document:dom.window.document,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true})) set(name,value);
 let streamed = 0;
 set('fetch', async (input:RequestInfo|URL) => {
  const url = String(input);
  if (url.includes('/stream/chat')) {
   streamed++;
   return new Response('event: text\ndata: {"type":"text","content":"您好，有货。"}\n\nevent: done\ndata: {"type":"done"}\n\n', {headers:{'Content-Type':'text/event-stream'}});
  }
  if (url.endsWith('/sessions')) return Response.json([]);
  return Response.json({sessionId:'created'});
 });
 let hooks: ReturnType<typeof useSessions>, chat: ReturnType<typeof useChat>;
 function Harness(){hooks=useSessions();chat=useChat({currentSession:hooks.currentSession,currentSessionId:hooks.currentSessionId,selectedModel:'test',setSessions:hooks.setSessions,setCurrentSessionId:hooks.setCurrentSessionId});return null;}
 const root=createRoot(dom.window.document.getElementById('root')!);
 try {
  await act(async()=>root.render(<Harness/>));
  await act(async()=>chat!.sendMessage('AirPods Pro多少钱？'));
  assert.equal(chat!.isLoading,false);
  assert.ok(hooks!.currentSession);
  await act(async()=>chat!.sendMessage('还有哪些颜色？'));
  assert.equal(streamed,2);
  assert.equal(hooks!.currentSession?.messages.filter(message=>message.role==='user').length,2);
 } finally {await act(async()=>root.unmount());for(const [name,descriptor] of saved){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}dom.window.close();}
});

test('a fast account gate rejection is visible even when the stream ends immediately', async () => {
 const dom = new JSDOM('<div id="root"></div>', {url:'https://gate.test'});
 const saved = new Map<string, PropertyDescriptor | undefined>();
 const set = (name: string, value: unknown) => { saved.set(name,Object.getOwnPropertyDescriptor(globalThis,name)); Object.defineProperty(globalThis,name,{value,writable:true,configurable:true}); };
 for (const [name,value] of Object.entries({window:dom.window,document:dom.window.document,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true})) set(name,value);
 set('fetch', async (input:RequestInfo|URL) => {
  if(String(input).includes('/stream/chat')) return new Response(
   'event: request_blocked\ndata: {"type":"request_blocked"}\n\nevent: done\ndata: {"type":"done"}\n\n',
   {headers:{'Content-Type':'text/event-stream'}});
  return Response.json({sessionId:'created'});
 });
 let hooks: ReturnType<typeof useSessions>, chat: ReturnType<typeof useChat>, gateMessage = '';
 function Harness(){hooks=useSessions();chat=useChat({currentSession:hooks.currentSession,currentSessionId:hooks.currentSessionId,selectedModel:'test',setSessions:hooks.setSessions,setCurrentSessionId:hooks.setCurrentSessionId,onGateRejected:message=>{gateMessage=message;}});return null;}
 const root=createRoot(dom.window.document.getElementById('root')!);
 try {
  await act(async()=>root.render(<Harness/>));
  await act(async()=>chat!.sendMessage('AirPods Pro多少钱？'));
  assert.match(gateMessage,/上一条问题还在处理中/);
  assert.equal(chat!.inputValue,'AirPods Pro多少钱？');
  const reply=hooks!.currentSession?.messages.find(message=>message.role==='assistant');
  assert.equal(reply?.deliveryStatus,'stopped');
  assert.match(reply?.content || '',/上一条问题还在处理中/);
 } finally {await act(async()=>root.unmount());for(const [name,descriptor] of saved){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}dom.window.close();}
});

test('legacy suspended sessions remain explicit, while a busy deletion waits and succeeds', async () => {
 const dom = new JSDOM('<div id="root"></div>', {url:'https://gate.test'});
 const saved = new Map<string, PropertyDescriptor | undefined>();
 const set = (name: string, value: unknown) => { saved.set(name, Object.getOwnPropertyDescriptor(globalThis,name)); Object.defineProperty(globalThis,name,{value,writable:true,configurable:true}); };
 for (const [name,value] of Object.entries({window:dom.window,document:dom.window.document,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true})) set(name,value);
 let hooks: ReturnType<typeof useSessions>, chat: ReturnType<typeof useChat>;
 const requests: string[]=[];
 let deleteAttempts = 0;
 set('fetch', async (input: RequestInfo|URL, init?:RequestInit) => {
   const url=String(input);requests.push(url);
   if(url.includes('/stream/chat')) return new Response('event: conversation_suspended\ndata: {"type":"conversation_suspended","activeSessionId":"owner","sessionId":"new"}\n\nevent: done\ndata: {"type":"done"}\n\n',{headers:{'Content-Type':'text/event-stream'}});
   if(url.endsWith('/resume')) return new Response(JSON.stringify({message:'暂时无法恢复会话'}),{status:503});
   if(init?.method==='DELETE') return ++deleteAttempts === 1
     ? new Response(JSON.stringify({message:'当前对话仍在处理请求'}),{status:409})
     : Response.json({success:true});
   if(url.endsWith('/sessions')) return Response.json([{id:'owner',title:'占用对话',status:'ACTIVE_IDLE'}]);
   return Response.json({id:'new',status:'SUSPENDED',messages:[]});
 });
 function Harness(){hooks=useSessions();chat=useChat({currentSession:hooks.currentSession,currentSessionId:hooks.currentSessionId,selectedModel:'test',setSessions:hooks.setSessions,setCurrentSessionId:hooks.setCurrentSessionId});return null;}
 const root=createRoot(dom.window.document.getElementById('root')!);
 try {
   await act(async()=>{root.render(<Harness/>);});
   await act(async()=>{
     hooks!.setSessions([{id:'new',title:'旧会话',model:'test',intent:'unknown',status:'active',
       satisfaction:null,satisfaction_comment:null,user_name:'用户',agent_name:null,
       createdAt:new Date(),messages:[]}]);
     hooks!.setCurrentSessionId('new');
   });
   await act(async()=>{await chat!.sendMessage('查订单','new');});
   assert.ok(requests.some(url=>url.includes('/stream/chat')));
   assert.ok(hooks!.sessions.some(s=>s.id==='new' && s.status==='suspended'));
   await act(async()=>{await hooks!.resumeSession('new');});
   assert.match(hooks!.sessionActionError!,/无法恢复/);
   await act(async()=>{await hooks!.fetchSessions();});
   await act(async()=>{await hooks!.deleteSession('owner');});
   assert.equal(deleteAttempts,2);
   assert.ok(!hooks!.sessions.some(s=>s.id==='owner'));
   assert.equal(hooks!.sessionActionError,null);
 } finally {await act(async()=>root.unmount());for(const [name,descriptor] of saved){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}dom.window.close();}
});

test('busy deletion has a finite retry limit and never bypasses the server gate', async () => {
 const originalFetch = globalThis.fetch;
 const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
 const originalSessionStorage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
 const dom = new JSDOM('', {url:'https://gate.test'});
 Object.defineProperty(globalThis, 'localStorage', {value:dom.window.localStorage,configurable:true});
 Object.defineProperty(globalThis, 'sessionStorage', {value:dom.window.sessionStorage,configurable:true});
 let attempts = 0;
 globalThis.fetch = async () => {
   attempts++;
   return new Response(JSON.stringify({message:'正在处理'}), {status:409,headers:{'Content-Type':'application/json'}});
 };
 try {
   await assert.rejects(deleteSessionWhenIdle('busy-session', async()=>{}, 2), /仍在处理请求/);
   assert.equal(attempts,3);
 } finally {
   globalThis.fetch=originalFetch;
   if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage);
   else Reflect.deleteProperty(globalThis, 'localStorage');
   if (originalSessionStorage) Object.defineProperty(globalThis, 'sessionStorage', originalSessionStorage);
   else Reflect.deleteProperty(globalThis, 'sessionStorage');
   dom.window.close();
 }
});
