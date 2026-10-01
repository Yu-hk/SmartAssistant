"""Guarded Product-only canary/promotion. No model/resource/library replacement; recoverable old container."""
import argparse, copy, json, os, pathlib, sys, time, urllib.request
import release_multi_product_merge_20260930 as recoverable
import release_product_field_routing_20260929 as release
from verify_bounded_feedback_live import verify

ROOT=pathlib.Path('/opt/smart-assistant/releases/bounded-feedback-20261001')
FLAGS={'PRODUCT_RAG_AGENTIC_AUTOMATIC_RETRY_ENABLED':'true','PRODUCT_RAG_AGENTIC_AUTOMATICRETRYENABLED':'true',
       'PRODUCT_RAG_AGENTIC_RETRY_TIMEOUT_MS':'1500','PRODUCT_RAG_AGENTIC_RETRYTIMEOUTMS':'1500'}
CANARY='smart-product-feedback-canary-20261001'
original_clone=release.clone
original_equivalent=release.release.equivalent

def environment(info):
    source=copy.deepcopy(info)
    source['Config']['Env']=[v for v in source['Config']['Env'] if v.split('=',1)[0] not in FLAGS]+[k+'='+v for k,v in FLAGS.items()]
    source['Config']['Labels'].pop('smartassistant.release',None)
    return source

def clone(service,source,name): return original_clone(service,environment(source),name)
def equivalent(service,old,new): return original_equivalent(service,environment(old),new)

def wait(name):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for _ in range(60):
        info=release.release.inspect(name)
        if not info['State']['Running']: raise ValueError('Canary exited')
        ip=info['NetworkSettings']['Networks']['smart-network']['IPAddress']
        try:
            with opener.open('http://'+ip+':8084/actuator/health',timeout=3) as r:
                if json.load(r).get('status')=='UP': return
        except (OSError,ValueError): pass
        time.sleep(2)
    raise TimeoutError('Canary readiness')

def config_plan(compose, envs):
    # Exact existing deployment files, never print their credentials or rewrite unrelated entries.
    for path in [compose]+envs:
        if not path.is_file() or path.is_symlink() or path.resolve()!=path: raise ValueError('Deployment config path drift')
    text=compose.read_text(); marker='      PRODUCT_CHAT_MODEL:'
    if text.count(marker)!=1: raise ValueError('Deployment compose scope drift')
    result={path:(path.read_bytes(),None) for path in [compose]+envs}
    if 'PRODUCT_RAG_AGENTIC_AUTOMATICRETRYENABLED:' not in text:
        # This historical host file uses a flow-style environment mapping. Preserve that style.
        if 'environment: {' not in text: raise ValueError('Deployment YAML style changed; review required')
        block=''.join("      "+k+": '${PRODUCT_RAG_AGENTIC_AUTOMATIC_RETRY_ENABLED:-false}',\n" if 'AUTOMATIC' in k
                      else "      "+k+": '${PRODUCT_RAG_AGENTIC_RETRY_TIMEOUT_MS:-1500}',\n" for k in FLAGS)
        text=text.replace(marker,block+marker)
    result[compose]=(result[compose][0],text.encode())
    keys={'PRODUCT_RAG_AGENTIC_AUTOMATIC_RETRY_ENABLED':'true','PRODUCT_RAG_AGENTIC_RETRY_TIMEOUT_MS':'1500'}
    for env in envs:
        entries=env.read_text().splitlines()
        entries=[e for e in entries if e.split('=',1)[0] not in keys]+[k+'='+v for k,v in keys.items()]
        result[env]=(result[env][0], ('\n'.join(entries)+'\n').encode())
    return result

def persist_flags(plan):
    for path,(before,after) in plan.items():
        if path.read_bytes()!=before: raise ValueError('Concurrent deployment config change; stopping')
        backup=ROOT/(path.name+'.before-feedback')
        fd=os.open(str(backup),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as stream: stream.write(before)
    for path,(before,after) in plan.items():
        if path.read_bytes()!=before: raise ValueError('Concurrent deployment config change; stopping')
        temporary=path.with_name(path.name+'.bounded-feedback.tmp')
        fd=os.open(str(temporary),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as stream: stream.write(after)
        os.replace(temporary,path)

def rollback_promotion(old, expected, plan):
    current=release.release.inspect('smart-product')
    previous=release.release.inspect(old['Id'])
    baseline=json.loads((ROOT/'before-artifacts.json').read_text())['services'][0]['sha256']
    if (previous['Id']!=old['Id'] or previous['Name'].lstrip('/')!='smart-product-before-bounded-feedback-20261001'
        or current['Config']['Labels'].get('smartassistant.release')!='bounded-feedback-20261001'
        or release.release.sha256(release.release.jar_mount(current))!=expected
        or release.release.sha256(release.release.jar_mount(previous))!=baseline):
        raise ValueError('Rollback ownership or artifact drift; manual review required')
    release.release.run('docker','stop','--time','30','smart-gateway',timeout=90)
    release.release.drain()
    release.release.run('docker','stop','--time','30',current['Id'],timeout=90)
    release.release.run('docker','rename',current['Id'],'smart-product-failed-bounded-feedback-20261001')
    release.release.run('docker','network','disconnect',release.release.NETWORK,current['Id'])
    release.release.run('docker','rename',old['Id'],'smart-product')
    ip=old['NetworkSettings']['Networks'][release.release.NETWORK]['IPAddress']
    release.release.run('docker','network','connect','--ip',ip,'--alias','smart-product',release.release.NETWORK,old['Id'])
    for path,(before,after) in plan.items():
        if path.read_bytes()==after:
            path.write_bytes(before)
        elif path.read_bytes()!=before:
            raise ValueError('Concurrent configuration change during rollback; review required')
    release.release.run('docker','start','smart-product',timeout=90)
    release.release.health('product')
    release.release.run('docker','start','smart-gateway',timeout=90)
    release.release.public_health()
    (ROOT/'rollback.json').write_text(json.dumps({'restored':True,'previousId':old['Id']}))
    print('POST_DEPLOY_ROLLBACK_DONE',flush=True)

def main():
    p=argparse.ArgumentParser(); p.add_argument('mode',choices=('preflight','canary','deploy')); a=p.parse_args()
    if pathlib.Path(__file__).resolve().parent!=ROOT: raise ValueError('Dedicated release root required')
    manifest=json.loads((ROOT/'candidate.json').read_text()); trial=json.loads((ROOT/'experiment.json').read_text())
    if trial.get('promotable') is not True: raise ValueError('Candidate experiment did not pass')
    if manifest['replacement_sha256']!=trial['replacement_sha256']: raise ValueError('Evaluated class package drift')
    expected=manifest['sha256']; filename='smart-assistant-product-scoped.jar'
    release.release.ROOT=ROOT; release.release.SNAPSHOT=ROOT/'before-artifacts.json'; release.release.TAG='bounded-feedback-20261001'
    release.release.TARGETS={'product':(filename,expected,8084)}
    release.clone=clone; release.release.equivalent=equivalent
    if a.mode=='preflight': release.main('preflight'); return
    if a.mode=='canary':
        release.main('preflight')
        source=release.release.inspect('smart-product'); isolated=environment(source)
        isolated['Config']['Cmd']=[v for v in isolated['Config']['Cmd'] if not v.startswith(('--profile.control.enabled=','--spring.cloud.nacos.discovery.enabled='))]
        isolated['Config']['Cmd']+=['--profile.control.enabled=false','--spring.cloud.nacos.discovery.enabled=false']
        cid=original_clone('product',isolated,CANARY)
        try:
            release.release.run('docker','start',cid); wait(CANARY)
            verify(CANARY,expected,ROOT/'canary-verified.json')
            print('CANARY_VERIFIED',flush=True)
        finally:
            owned=release.release.inspect(cid)
            if owned['Config']['Labels'].get('smartassistant.release')!='bounded-feedback-20261001': raise ValueError('Cleanup ownership drift')
            release.release.run('docker','rm','-f',cid)
    else:
        canary=json.loads((ROOT/'canary-verified.json').read_text())
        if canary.get('status')!='passed' or canary['sha256']!=expected: raise ValueError('No verified canary')
        plan=config_plan(pathlib.Path('/opt/smart-assistant/deploy/docker-compose.yml'),
            [pathlib.Path('/opt/smart-assistant/deploy/.env'),pathlib.Path('/opt/smart-assistant/deploy/.env.production')])
        old=release.release.inspect('smart-product')
        release.main('deploy')
        try:
            verify('smart-product',expected,ROOT/'production-verified.json')
            persist_flags(plan)
        except Exception:
            rollback_promotion(old,expected,plan)
            raise
        print('PRODUCTION_RETRY_VERIFIED_AND_CONFIG_PERSISTED',flush=True)

if __name__=='__main__':
    try: main()
    except Exception as error:
        print('RELEASE_FAILED '+type(error).__name__+': '+str(error),file=sys.stderr); sys.exit(1)
