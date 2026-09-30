"""Run same-gold synthetic retrieval in owned containers; never restart business services.
Pinned Product artifact supplies actual Common/BM25/RRF/rerank classes. Only a
temporary extracted classpath is mounted read-only, no DB, socket or credential.
"""
import argparse
import hashlib
import ipaddress
import json
import re
import shutil
import subprocess
import tempfile
import uuid
import zipfile
from pathlib import Path

from ragas_feedback import write_new
from retrieval_calibration import evaluate, probe_request


def run(args,data=None,timeout=30):
    result=subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if result.returncode:
        # No HTTP responses, user text, env or dependency log body in error output.
        types=re.findall(r'(?:Caused by: |Exception in thread "[^"]+" )([\w.$]+)',result.stderr.decode('utf-8',errors='replace'))
        raise RuntimeError('Calibration command failed, exit='+str(result.returncode)+', types='+str(types[:4]))
    return result.stdout


def inspect(name): return json.loads(run(['docker','inspect',name]))[0]


def sha(path):
    value=hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda:source.read(1048576),b''): value.update(block)
    return value.hexdigest()


def container_command(folder,image,endpoint,label,main_class='RetrievalCalibrationProbe'):
    if not re.fullmatch(r'(?:sha256:)?[a-f0-9]{64}',image) or not re.fullmatch(r'[a-f0-9]{32}',label):
        raise ValueError('Pinned runtime and owned label required')
    if main_class not in ('RetrievalCalibrationProbe','NativeRetrievalCalibrationProbe'):
        raise ValueError('Fixed probe class required')
    return ['docker','create','-i','--name','retrieval-calibration-'+label,'--network','smart-network',
            '--memory','1g','--cpus','1','--pids-limit','128','--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges','--tmpfs','/tmp:rw,nosuid,size=134217728',
            '--label','smartassistant.retrieval-calibration='+label,
            '--mount','type=bind,source='+str(folder)+',target=/benchmark,readonly',
            '--env','RAG_EVAL_EMBEDDING_URL='+endpoint,'--entrypoint','java','-w','/benchmark',image,
            '-Xms64m','-Xmx640m','-XX:ActiveProcessorCount=1','-cp','.:BOOT-INF/classes:BOOT-INF/lib/*',
            main_class]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',required=True,type=Path)
    parser.add_argument('--expected-product-sha256',required=True)
    parser.add_argument('--profile',choices=('component','native'),default='component')
    args=parser.parse_args()
    root=args.root
    if (root.resolve()!=root or root.parent!=Path('/opt/smart-assistant/eval')
            or not re.fullmatch(r'retrieval-calibration-[0-9-]+',root.name) or not root.is_dir()):
        raise ValueError('Dedicated confined evaluation directory required')
    if (root/'report.json').exists(): raise FileExistsError('Fresh experiment required')
    dataset=json.loads((root/'questions.json').read_text(encoding='utf-8'))
    evaluator,request_builder,main_class=evaluate,probe_request,'RetrievalCalibrationProbe'
    if args.profile=='native':
        from native_retrieval_calibration import evaluate as native_evaluate, probe_request as native_request
        evaluator,request_builder,main_class=native_evaluate,native_request,'NativeRetrievalCalibrationProbe'
    request=request_builder(dataset)
    product,embedding=inspect('smart-product'),inspect('smart-embedding-service')
    if not product['State']['Running'] or not embedding['State']['Running']:
        raise ValueError('Services must already be healthy')
    jars=[m['Source'] for m in product['Mounts'] if m['Destination']=='/app/app.jar']
    if len(jars)!=1: raise ValueError('Explicit deployed Product artifact required')
    source=Path(jars[0])
    if source.resolve()!=source or Path('/opt/smart-assistant/releases') not in source.parents:
        raise ValueError('Unexpected deployed artifact path')
    if sha(source)!=args.expected_product_sha256: raise ValueError('Product artifact drift')
    ip=ipaddress.ip_address(embedding['NetworkSettings']['Networks']['smart-network']['IPAddress'])
    if ip.version!=4 or not ip.is_private: raise ValueError('Private embedding endpoint required')
    endpoint='http://'+str(ip)+':8091'
    folder=Path(tempfile.mkdtemp(prefix='retrieval-calibration-',dir='/dev/shm'))
    trials=[]; removed=0
    try:
        with zipfile.ZipFile(str(source)) as archive:
            for item in archive.infolist():
                if item.is_dir() or not item.filename.startswith(('BOOT-INF/lib/','BOOT-INF/classes/')): continue
                target=folder/item.filename
                if folder not in target.resolve().parents: raise ValueError('Unsafe artifact entry')
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(archive.read(item))
        with zipfile.ZipFile(str(root/'calibration-probe.zip')) as archive:
            for item in archive.infolist():
                if not re.fullmatch(r'(?:Native)?RetrievalCalibrationProbe(?:\$[A-Za-z0-9_]+)?\.class',item.filename):
                    raise ValueError('Unsafe probe entry')
                (folder/item.filename).write_bytes(archive.read(item))
        for repeat in range(3):
            label=uuid.uuid4().hex; cid=None
            try:
                cid=run(container_command(folder,product['Image'],endpoint,label,main_class)).decode().strip()
                state=inspect(cid); binds=[m for m in state['Mounts'] if m['Type']=='bind']
                if len(binds)!=1 or binds[0]['Source']!=str(folder) or binds[0]['RW']:
                    raise ValueError('Read-only classpath isolation failed')
                payload=run(['docker','start','-ai',cid],json.dumps(request,ensure_ascii=False).encode(),timeout=300)
                trial=json.loads(payload)
                if inspect(cid)['State']['ExitCode']!=0: raise ValueError('Probe failed')
                trials.append(trial); write_new(root/('trial-'+str(repeat+1)+'.json'),trial)
            finally:
                if cid:
                    owned=inspect(cid)
                    if owned['Id']!=cid or owned['Config']['Labels'].get('smartassistant.retrieval-calibration')!=label:
                        raise ValueError('Cleanup ownership mismatch')
                    run(['docker','rm','-f',cid]); removed+=1
        report=evaluator(dataset,trials)
        latest_product,latest_embedding=inspect('smart-product'),inspect('smart-embedding-service')
        if (latest_product['Id']!=product['Id'] or latest_embedding['Id']!=embedding['Id']
                or not latest_product['State']['Running'] or not latest_embedding['State']['Running']
                or sha(source)!=args.expected_product_sha256):
            raise ValueError('Runtime drift during experiment')
        report['runtime_evidence']={'product_sha256':args.expected_product_sha256,'image':product['Image'],
            'probe_sha256':sha(root/'calibration-probe.zip'),'removed_trial_containers':removed,
            'production_services_restarted':False,'production_database_mounted':False}
        write_new(root/'report.json',report)
        print(json.dumps({key:report[key] for key in ('decision','selected_on_development','stable_rankings','split_counts','holdout_ndcg_gain','reasons')}))
    finally:
        if folder.resolve().parent!=Path('/dev/shm') or not folder.name.startswith('retrieval-calibration-'):
            raise ValueError('Cleanup path mismatch')
        shutil.rmtree(str(folder))


if __name__=='__main__': main()
