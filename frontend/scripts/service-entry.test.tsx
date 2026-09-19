import {test} from 'node:test';
import assert from 'node:assert/strict';
import React,{act} from 'react';
import {createRoot} from 'react-dom/client';
import {JSDOM} from 'jsdom';
import {MemoryRouter} from 'react-router-dom';
import App from '../src/App';
import {CustomerChatPage} from '../src/pages/CustomerChatPage';
import {serviceEntryDraft,SERVICE_NAMES} from '../src/utils/serviceEntry';
import type {Session} from '../src/types';

test('shortcuts replace only generated prompts, preserving custom drafts',()=>{
 assert.equal(serviceEntryDraft('','技术支持'),'我需要技术支持：');
 assert.equal(serviceEntryDraft('我需要技术支持：','订单助手'),'我需要订单助手：');
 assert.equal(serviceEntryDraft('我的耳机有杂音','订单助手'),'我的耳机有杂音');
 assert.equal(serviceEntryDraft('','unknown'),'');
});

async function withDom(run:(container:HTMLElement,errors:string[])=>Promise<void>){
 const dom=new JSDOM('<div id="root"></div>',{url:'https://service-entry.test'});
 const saved=new Map<string,PropertyDescriptor|undefined>();
 const set=(name:string,value:unknown)=>{saved.set(name,Object.getOwnPropertyDescriptor(globalThis,name));Object.defineProperty(globalThis,name,{value,writable:true,configurable:true});};
 for(const [name,value] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true}))set(name,value);
 set('fetch',async(input:RequestInfo|URL)=>{
  const url=String(input);
  if(url.endsWith('/auth/me'))return Response.json({userId:42,username:'fixture',role:'ROLE_USER'});
  if(url.includes('capabilities'))return Response.json({available:false});
  if(url.endsWith('/stream'))return new Response('',{headers:{'Content-Type':'text/event-stream'}});
  if(url.endsWith('/sessions')||url.includes('unread'))return Response.json([]);
  throw new Error('Unexpected diagnostic request '+url);
 });
 dom.window.HTMLElement.prototype.scrollIntoView=()=>{};
 dom.window.matchMedia=(()=>({matches:false,addEventListener(){},removeEventListener(){}})) as typeof window.matchMedia;
 localStorage.setItem('smart-assistant-token','fixture-only');
 localStorage.setItem('smart-assistant-user',JSON.stringify({userId:42,username:'fixture',role:'ROLE_USER',token:'fixture-only'}));
 const original=console.error,errors:string[]=[];console.error=(...args)=>errors.push(args.map(String).join(' '));
 try{await run(document.getElementById('root')!,errors);assert.equal(errors.filter(e=>e.includes('same key')).length,0);}
 finally{console.error=original;for(const [name,descriptor] of saved){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}dom.window.close();}
}
test('repeated and alternating service clicks leave one composer and create no sessions',async()=>withDom(async(container)=>{
 const root=createRoot(container);
 try{
  await act(async()=>root.render(<MemoryRouter><App/></MemoryRouter>));
  for(const name of [...Array(5).fill('技术支持'),...SERVICE_NAMES]){
   const button=Array.from(container.querySelectorAll('.customer-services button')).find(b=>b.textContent===name) as HTMLButtonElement;
   assert.ok(button);await act(async()=>button.click());
   assert.equal(container.querySelectorAll('.customer-session').length,0);
   assert.equal(container.querySelectorAll('textarea[aria-label="输入你的问题"]').length,1);
   assert.equal((container.querySelector('textarea[aria-label="输入你的问题"]') as HTMLTextAreaElement).value,`我需要${name}：`);
  }
  for(let i=0;i<3;i++)await act(async()=>(container.querySelector('.customer-new-chat') as HTMLButtonElement).click());
  assert.equal(container.querySelectorAll('.customer-session').length,0);
 }finally{await act(async()=>root.unmount());}
}));
test('switching legacy empty sessions never accumulates composers or document panels',async()=>withDom(async(container)=>{
 const root=createRoot(container),noop=()=>{};
 const base={sessions:[],isLoading:false,inputValue:'draft',permissionRequest:null,faqSuggestions:[],queuePosition:null,queueEstimatedWait:null,progressMessage:'',onSendMessage:noop,onStop:noop,onInputChange:noop,onPermissionAllow:noop,onPermissionDeny:noop,onRecoverMessage:noop,recoveryAvailable:false,onRateSession:noop};
 try{
  for(const id of [undefined,'a','b','c',undefined]){
   const session=id?{id,title:'fixture',status:'active',messages:[],intent:'unknown',createdAt:new Date()} as Session:undefined;
   await act(async()=>root.render(<MemoryRouter><CustomerChatPage {...base} currentSession={session}/></MemoryRouter>));
   assert.equal(container.querySelectorAll('textarea[aria-label="输入你的问题"]').length,1);
   assert.equal(container.querySelectorAll('.home-documents').length,1);
  }
 }finally{await act(async()=>root.unmount());}
}));
