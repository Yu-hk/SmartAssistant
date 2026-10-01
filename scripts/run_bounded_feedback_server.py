"""Read-only candidate-class experiment with existing frozen holdout and independent positive controls.
No business DB, real conversations, credentials or Docker socket enter a probe container.
"""
import argparse, hashlib, json, re, shutil, tempfile, uuid, zipfile
from pathlib import Path
from run_retrieval_calibration_server import run, inspect, sha, container_command
from native_retrieval_calibration import probe_request
from ragas_feedback import write_new

BASELINE='1d9a04ea419f44c92542f2003810408088c5ceb35d0bdd930ae6ec15b1ddf9e2'

def promotion_gate(rows):
    controls=[r for r in rows if r['group']=='controls']
    holdout=[r for r in rows if r['group']=='holdout']
    return (len(controls)==2 and len(holdout)==64
            and all(r['stable'] and not r['novel_non_gold_ids']
                    and r['candidate_recall']>=r['baseline_recall'] for r in rows)
            and all(r['status']=='ACCEPTED' and r['candidate_recall']==1 for r in controls))
def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--root',type=Path,required=True); args=parser.parse_args()
    root=args.root
    if root.resolve()!=root or root.parent!=Path('/opt/smart-assistant/eval') or not re.fullmatch('retrieval-feedback-[0-9-]+',root.name):
        raise ValueError('Confined fresh experiment root required')
    if (root/'report.json').exists(): raise FileExistsError('Fresh evidence required')
    product,embedding=inspect('smart-product'),inspect('smart-embedding-service')
    source=Path(next(m['Source'] for m in product['Mounts'] if m['Destination']=='/app/app.jar'))
    if sha(source)!=BASELINE: raise ValueError('Baseline artifact drift')
    guard=json.loads((root/'guard.json').read_text()); expected=set(guard['replace'])|set(guard['add'])
    patch=zipfile.ZipFile(root/'replacement.zip'); old=zipfile.ZipFile(source)
    if guard['baseline']!=BASELINE or set(patch.namelist())!=expected: raise ValueError('Overlay scope drift')
    if any(hashlib.sha256(old.read(n)).hexdigest()!=v for n,v in guard['replace'].items()): raise ValueError('Class baseline drift')
    gold=json.loads((root/'questions.json').read_text()); controls=json.loads((root/'positive-controls.json').read_text())
    requests=[('holdout', {**probe_request(gold),'profile':'bounded-feedback-v1'}),
              ('controls', {'profile':'bounded-feedback-v1','queries':controls['queries'],'documents':controls['documents']})]
    folder=Path(tempfile.mkdtemp(prefix='retrieval-feedback-',dir='/dev/shm')); results={}; removed=0
    try:
        for entry in old.infolist():
            if entry.is_dir() or not entry.filename.startswith(('BOOT-INF/lib/','BOOT-INF/classes/')): continue
            dest=folder/entry.filename
            if folder not in dest.resolve().parents: raise ValueError('Entry escapes temporary root')
            dest.parent.mkdir(parents=True,exist_ok=True); dest.write_bytes(patch.read(entry.filename) if entry.filename in expected else old.read(entry.filename))
        for name in guard['add']:
            dest=folder/name; dest.parent.mkdir(parents=True,exist_ok=True); dest.write_bytes(patch.read(name))
        with zipfile.ZipFile(root/'calibration-probe.zip') as probes:
            for entry in probes.infolist():
                if not re.fullmatch(r'(?:Native)?RetrievalCalibrationProbe(?:\$[A-Za-z0-9_]+)?\.class',entry.filename): raise ValueError('Probe scope drift')
                (folder/entry.filename).write_bytes(probes.read(entry.filename))
        ip=embedding['NetworkSettings']['Networks']['smart-network']['IPAddress']
        for group,request in requests:
            trials=[]
            for repeat in range(3):
                label=uuid.uuid4().hex; cid=None
                try:
                    cid=run(container_command(folder,product['Image'],'http://'+ip+':8091',label,'NativeRetrievalCalibrationProbe')).decode().strip()
                    payload=run(['docker','start','-ai',cid],json.dumps(request,ensure_ascii=False).encode(),timeout=300)
                    trial=json.loads(payload); trials.append(trial); write_new(root/(group+'-'+str(repeat+1)+'.json'),trial)
                    if inspect(cid)['State']['ExitCode']!=0: raise ValueError('Probe exit failure')
                finally:
                    if cid:
                        if inspect(cid)['Config']['Labels'].get('smartassistant.retrieval-calibration')!=label: raise ValueError('Cleanup owner drift')
                        run(['docker','rm','-f',cid]); removed+=1
            results[group]=trials
        rows=[]; forbidden={d['id'] for d in gold['documents'] if d['visibility']!='public' or d['knowledge_base']!='product_knowledge'}|{'CTRL-PRIVATE','CTRL-ORDER'}
        for group,trials in results.items():
            cases={row['id']:row for row in (gold['cases'] if group=='holdout' else controls['queries'])}
            for result in trials[0]['results']:
                base=result['rankings']['hybrid-semantic']; candidate=result['rankings']['hybrid-bounded-feedback']; case=cases[result['id']]
                expected_ids=case.get('expected_doc_ids',controls['reference_ids'])
                base_ids=base['context_doc_ids']; ids=candidate['context_doc_ids']
                if set(ids)&forbidden or len(ids)>8 or not set(base_ids).issubset(ids): raise ValueError('ACL/domain/baseline evidence lost')
                stable=all(next(r for r in t['results'] if r['id']==result['id'])['rankings']['hybrid-bounded-feedback']['context_doc_ids']==ids for t in trials)
                rows.append({'group':group,'id':result['id'],'split':case.get('split','positive-control'),
                             'baseline_ids':base_ids,'candidate_ids':ids,'status':candidate['feedback']['status'], 'stable':stable,
                             'novel_non_gold_ids':sorted(set(ids)-set(base_ids)-set(expected_ids)),
                             'baseline_recall':len(set(base_ids)&set(expected_ids))/len(expected_ids) if expected_ids else 1,
                             'candidate_recall':len(set(ids)&set(expected_ids))/len(expected_ids) if expected_ids else 1})
        if sha(source)!=BASELINE or inspect('smart-product')['Id']!=product['Id']: raise ValueError('Production changed during experiment')
        promotable=promotion_gate(rows)
        report={'rows':rows,'all_stable':all(r['stable'] for r in rows),'promotable':promotable,'baseline_sha256':BASELINE,
                'replacement_sha256':sha(root/'replacement.zip'),'probe_sha256':sha(root/'calibration-probe.zip'),
                'owned_containers_removed':removed,'business_writes':0,'business_services_restarted':False,
                'scope':'synthetic-memory-kb-with-real-production-bge-and-candidate-product-classes-not-full-ui'}
        write_new(root/'report.json',report)
        print(json.dumps({'status':'completed','cases':len(rows),'stable':report['all_stable'],'promotable':promotable,
                          'accepted':sum(r['status']=='ACCEPTED' for r in rows),'controls':rows[-2:]}))
    finally:
        old.close(); patch.close()
        if folder.resolve().parent!=Path('/dev/shm') or not folder.name.startswith('retrieval-feedback-'): raise ValueError('Cleanup confinement')
        shutil.rmtree(folder)
if __name__=='__main__': main()
