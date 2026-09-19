import {test} from 'node:test';
import assert from 'node:assert/strict';
import React,{act} from 'react';
import {createRoot} from 'react-dom/client';
import {JSDOM} from 'jsdom';
import {ProfilePrivacy} from '../src/components/ProfilePrivacy';

async function fixture(run:(container:HTMLElement,requests:{url:string,body:any}[],setResult:(x:any)=>void)=>Promise<void>){
 const dom=new JSDOM('<div id="root"></div>',{url:'https://privacy.test'}),saved=new Map();
 function set(k:string,v:any){saved.set(k,Object.getOwnPropertyDescriptor(globalThis,k));Object.defineProperty(globalThis,k,{value:v,writable:true,configurable:true});}
 for(const [k,v] of Object.entries({window:dom.window,document:dom.window.document,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,IS_REACT_ACT_ENVIRONMENT:true}))set(k,v);
 dom.window.HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
 localStorage.setItem('smart-assistant-token','fixture-only');
 localStorage.setItem('smart-assistant-user',JSON.stringify({userId:41}));
 let result:any={available:true,analysisEnabled:true};const requests:{url:string,body:any}[]=[];
 set('fetch',async(url:any,options:any)=>{
  requests.push({url:String(url),body:options?.body?JSON.parse(options.body):null});
  if(options?.method==='POST'){
   result={available:true,analysisEnabled:false,job:{job_id:'fixture-job',state:'ONLINE_CLEANED',targets:[{target:'POSTGRES_PROFILE',state:'SUCCEEDED'}]}};
   return Response.json({jobId:'fixture-job'});
  }return Response.json(result);
 });
 const root=createRoot(document.getElementById('root')!);
 try{await act(async()=>root.render(<ProfilePrivacy/>));await run(document.getElementById('root')!,requests,x=>result=x);}
 finally{await act(async()=>root.unmount());dom.window.close();for(const [k,v] of saved){if(v)Object.defineProperty(globalThis,k,v);else Reflect.deleteProperty(globalThis,k);}}
}
test('privacy is opt-in, confirmed, single-flight and reports retained originals',async()=>fixture(async(c,requests)=>{
 assert.equal(requests.length,0);
 await act(async()=>(c.querySelector('button') as HTMLButtonElement).click());
 assert.equal(requests.length,1);assert.match(c.textContent!,/历史备份和旧日志仍会保留/);
 const deletion=c.querySelector('.profile-privacy-delete') as HTMLButtonElement;
 assert.equal(deletion.disabled,true);
 await act(async()=>(c.querySelector('input') as HTMLInputElement).click());
 await act(async()=>{deletion.click();deletion.click();});
 const posts=requests.filter(r=>r.body);assert.equal(posts.length,1);
 assert.deepEqual(Object.keys(posts[0].body).sort(),['confirmation','idempotencyKey']);
 assert.equal(posts[0].body.confirmation,'DELETE_PROFILE_PAUSE_ANALYSIS');
 assert.match(c.textContent!,/在线画像清理完成/);assert.match(c.textContent!,/已暂停/);
 assert.equal(c.querySelector('.profile-privacy-delete'),null);
}));
test('unavailable and partial do not claim deletion success or offer duplicate deletion',async()=>fixture(async(c,requests,setResult)=>{
 setResult({available:false,analysisEnabled:true});
 await act(async()=>(c.querySelector('button') as HTMLButtonElement).click());
 assert.equal((c.querySelector('input') as HTMLInputElement).disabled,true);
 setResult({available:true,analysisEnabled:false,job:{job_id:'fixture',state:'PARTIAL',targets:[{target:'DERIVED_COPIES',state:'RETRY'}]}});
 await act(async()=>Array.from(c.querySelectorAll('button')).find(b=>b.textContent==='刷新状态')!.click());
 assert.match(c.textContent!,/清理仍在进行/);assert.doesNotMatch(c.textContent!,/在线画像清理完成/);
 assert.equal(c.querySelector('.profile-privacy-delete'),null);assert.equal(requests.filter(r=>r.body).length,0);
}));
test('an account switch cannot submit a stale confirmation',async()=>fixture(async(c,requests)=>{
 await act(async()=>(c.querySelector('button') as HTMLButtonElement).click());
 await act(async()=>(c.querySelector('input') as HTMLInputElement).click());
 localStorage.setItem('smart-assistant-user',JSON.stringify({userId:42}));
 await act(async()=>(c.querySelector('.profile-privacy-delete') as HTMLButtonElement).click());
 assert.equal(requests.filter(r=>r.body).length,0);
}));
