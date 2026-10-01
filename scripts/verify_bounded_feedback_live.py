"""Synthetic read-only Product invocation plus exact request-bound Redis trace, no orders or user history."""
import argparse, hashlib, json, re, subprocess, urllib.request, uuid
from pathlib import Path
from ragas_feedback import write_new

def command(*args, data=None):
    p=subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
    if p.returncode: raise RuntimeError('Read-only check failed: '+args[0])
    return p.stdout.decode().strip()

def read_trace(info, rid):
    env=dict(v.split('=',1) for v in info['Config']['Env'] if '=' in v)
    password=env.get('REDIS_PASSWORD') or env.get('SPRING_DATA_REDIS_PASSWORD')
    if not password or '\n' in password: raise ValueError('Configured Redis authentication unavailable')
    proc=subprocess.run(['docker','exec','-i','smart-redis','sh','-c',
        'IFS= read -r REDISCLI_AUTH; export REDISCLI_AUTH; exec redis-cli --raw GET "$1"',
        'sh','a2a:stage:trace:'+rid],input=(password+'\n').encode(),
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
    if proc.returncode or proc.stderr or proc.stdout.startswith((b'NOAUTH',b'WRONGPASS',b'ERR')):
        raise ValueError('Configured Redis read failed; no alternate authentication attempted')
    raw=proc.stdout.decode().strip()
    if not raw: raise ValueError('Request-bound trace absent')
    trace=json.loads(raw)
    if trace.get('requestId')!=rid: raise ValueError('Trace correlation drift')
    return trace

def verify(name, expected, output):
    if not re.fullmatch('smart-product(?:-feedback-canary-20261001)?',name): raise ValueError('Confined service required')
    info=json.loads(command('docker','inspect',name))[0]
    mount=next(m['Source'] for m in info['Mounts'] if m['Destination']=='/app/app.jar')
    if hashlib.sha256(Path(mount).read_bytes()).hexdigest()!=expected: raise ValueError('Candidate drift')
    if command('docker','exec',name,'sha256sum','/app/app.jar').split()[0]!=expected: raise ValueError('Mounted candidate drift')
    ip=info['NetworkSettings']['Networks']['smart-network']['IPAddress']
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    rows=[]
    for q in ['请根据知识库回答：可售库存的计算规则是什么？锁定库存和质检库存分别如何处理？',
              '请根据知识库说明：库存锁定和库存释放流程如何区别？',
              'AirPods Pro 2件和MacBook Air M3 1台合计多少钱？']:
        rid='qa-feedback-'+uuid.uuid4().hex
        payload={'protocolVersion':'1.0','executionId':rid,'nodeId':'readonly','userId':'qa-feedback-readonly',
                 'operation':'ANSWER','question':q,'input':{}}
        req=urllib.request.Request('http://'+ip+':8084/internal/agents/product/execute',
                data=json.dumps(payload,ensure_ascii=False).encode(),headers={'Content-Type':'application/json'},method='POST')
        with opener.open(req,timeout=120) as response: answer=json.load(response)
        if answer.get('status')!='SUCCEEDED': raise ValueError('Product answer failed')
        if 'AirPods' in q:
            if '12997' not in answer.get('answer',''): raise ValueError('Catalog arithmetic regression')
            rows.append({'request_id':rid,'question':q,'answer':answer.get('answer'),'feedback':None,'route':'catalog-direct'})
            continue
        trace=read_trace(info,rid)
        retrieval=[s for s in trace.get('stages',[]) if s.get('stage')=='RETRIEVAL' or s.get('stageType')=='RETRIEVAL']
        if not retrieval: raise ValueError('Request-bound retrieval trace absent')
        attributes=retrieval[-1].get('metrics',{})
        evidence=attributes.get('evidenceTrace',{})
        feedback=evidence.get('feedback')
        if 'AirPods' not in q:
            if not isinstance(feedback,dict) or feedback.get('enabled') is not True: raise ValueError('Automatic retry flag not active')
            if feedback.get('maxRetries')!=1 or feedback.get('timeoutMs')!=1500: raise ValueError('Bound drift')
        rows.append({'request_id':rid,'question':q,'answer':answer.get('answer'),
                     'feedback':feedback,'executeRetry':evidence.get('executeRetry'),
                     'selectedAttempt':evidence.get('selectedAttempt'),'promptEvidenceInjected':attributes.get('promptEvidenceInjected')})
    if not any(r.get('feedback',{}).get('attempted') for r in rows if r['feedback']): raise ValueError('No actual bounded retry observed')
    report={'status':'passed','service':name,'sha256':expected,'rows':rows,'orders_written':0,'scope':'product-internal-answer-plus-pg-kb-and-trace-not-router-ui'}
    write_new(output,report)
    return report

def main():
    p=argparse.ArgumentParser(); p.add_argument('--service',default='smart-product'); p.add_argument('--sha',required=True); p.add_argument('--output',type=Path,required=True); p.add_argument('--inventory',action='store_true'); a=p.parse_args()
    if a.inventory:
        sql="BEGIN READ ONLY; SELECT id,title,category FROM knowledge_docs WHERE COALESCE(tenant_id,'')='' AND security_level=0 AND cardinality(authorized_roles)=0 AND cardinality(authorized_users)=0 ORDER BY id LIMIT 100; COMMIT;"
        print(command('docker','exec','-i','smart-postgres','psql','--no-psqlrc','-qAt','-U','postgres','-d','a2a_system',data=sql.encode())); return
    r=verify(a.service,a.sha,a.output); print(json.dumps({'status':r['status'],'requests':len(r['rows']),'actual_retry_observed':True}))
if __name__=='__main__': main()
