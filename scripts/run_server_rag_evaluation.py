"""Run the public-seed probe against the existing embedding service, without DB mounts.
Requires eval_rag.py, dataset.json and probe.zip in an explicitly supplied release directory.
Only removes owned temporary containers and its /dev/shm temporary directory.
"""
import argparse
import hashlib
import ipaddress
import json
import pathlib
import re
import shutil
import subprocess
import tempfile
import uuid
import zipfile
import eval_rag
from container_dns_policy import internal_resolver, verify_resolvers

def run(args, data=None, timeout=180):
    p=subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if p.returncode:
        error=p.stderr.decode('utf-8',errors='replace')
        categories=[key for key,marker in (
            ('missing_class','NoClassDefFoundError'),('embedding_failed','Embedding unavailable'),
            ('dns_failed','UnknownHostException'),('connection_failed','ConnectException'),
            ('read_only','Read-only file system'),('permission','Permission denied'),
            ('invalid_json','JsonParseException'),('empty_input','No content to map')) if marker in error]
        types=re.findall(r'(?:Caused by: |Exception in thread "[^"]+" )([\w.$]+)',error)
        types+=re.findall(r'RAG_EMBEDDING_FAILURE_TYPE=([\w.$]+)',error)
        raise RuntimeError('Isolated command failed: categories='+str(categories)+', types='+str(types[:4]))
    return p.stdout
def inspect(name): return json.loads(run(['docker','inspect',name]))[0]
def main():
    p=argparse.ArgumentParser();p.add_argument('--root',required=True);p.add_argument('--expected-common',required=True)
    p.add_argument('--candidate-jar',help='Explicitly benchmark staged consumer.jar before production switch')
    p.add_argument('--endpoint-mode',choices=['inspected-ip','internal-dns'],default='inspected-ip')
    p.add_argument('--repeats',type=int,default=3);args=p.parse_args()
    root=pathlib.Path(args.root)
    assert root.resolve()==root and root.parent==pathlib.Path('/opt/smart-assistant/releases')
    assert root.name.startswith('real-rag-eval-') and root.is_dir()
    assert not (root/'report.json').exists() and not (root/'runtime-evidence.json').exists()
    current=inspect('smart-consumer')
    embedding=inspect('smart-embedding-service')
    assert embedding['State']['Running']
    embedding_ip=ipaddress.ip_address(embedding['NetworkSettings']['Networks']['smart-network']['IPAddress'])
    assert embedding_ip.version==4 and embedding_ip.is_private
    dns_gateway=None
    if args.endpoint_mode=='internal-dns':
        network=json.loads(run(['docker','network','inspect','smart-network']))[0]
        dns_gateway=internal_resolver(network)
    source=pathlib.Path(next(m['Source'] for m in current['Mounts'] if m['Destination']=='/app/app.jar'))
    if args.candidate_jar:
        source=pathlib.Path(args.candidate_jar)
        assert source == root/'consumer.jar'
    assert source.is_file() and source.resolve()==source
    data=json.loads((root/'dataset.json').read_text(encoding='utf-8'))
    folder=pathlib.Path(tempfile.mkdtemp(prefix='rag-eval-',dir='/dev/shm'))
    removed=[]
    try:
        with zipfile.ZipFile(str(source)) as z:
            common=z.read('BOOT-INF/lib/smart-assistant-common-1.0.0-SNAPSHOT.jar')
            assert hashlib.sha256(common).hexdigest()==args.expected_common
            for item in z.infolist():
                if item.filename.startswith('BOOT-INF/lib/') and not item.is_dir():
                    target=folder/item.filename
                    assert folder in target.resolve().parents
                    target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(item))
        with zipfile.ZipFile(str(root/'probe.zip')) as z:
            for item in z.infolist():
                assert '/' not in item.filename and item.filename.startswith('RagEvaluationProbe') and item.filename.endswith('.class')
                (folder/item.filename).write_bytes(z.read(item))
        def backend(request):
            label=uuid.uuid4().hex;cid=None
            try:
                dns_args=['--dns',dns_gateway] if dns_gateway else []
                endpoint='http://smart-embedding-service:8091' if dns_gateway else 'http://'+str(embedding_ip)+':8091'
                cid=run(['docker','create','-i','--network','smart-network']+dns_args+['--memory','1g','--cpus','1',
                    '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
                    '--tmpfs','/tmp:rw,size=134217728','--label','smartassistant.rag-eval='+label,
                    '--mount','type=bind,source='+str(folder)+',target=/benchmark,readonly',
                    '--env','RAG_EVAL_EMBEDDING_URL='+endpoint,
                    '--entrypoint','java','-w','/benchmark',current['Image'],
                    '-Xms64m','-Xmx640m','-XX:ActiveProcessorCount=1','-cp','.:BOOT-INF/lib/*','RagEvaluationProbe']).decode().strip()
                state=inspect(cid)
                binds=[m for m in state['Mounts'] if m['Type']=='bind']
                assert len(binds)==1 and binds[0]['Source']==str(folder) and not binds[0]['RW']
                assert all(m['Type']=='bind' or (m['Type']=='tmpfs' and m['Destination']=='/tmp') for m in state['Mounts'])
                result=run(['docker','start','-ai',cid],json.dumps(request,ensure_ascii=False).encode('utf-8'),timeout=240)
                assert inspect(cid)['State']['ExitCode']==0
                response=json.loads(result)
                if dns_gateway: verify_resolvers(response['resolver_nameservers'],dns_gateway)
                return response
            finally:
                if cid:
                    state=inspect(cid)
                    assert state['Id']==cid and state['Config']['Labels'].get('smartassistant.rag-eval')==label
                    run(['docker','rm','-f',cid]);removed.append(label)
        report=eval_rag.evaluate(data,backend,repeats=args.repeats)
        latest_embedding=inspect('smart-embedding-service')
        assert latest_embedding['Id']==embedding['Id'] and latest_embedding['State']['Running']
        assert latest_embedding['NetworkSettings']['Networks']['smart-network']['IPAddress']==str(embedding_ip)
        passed=report['overall']['hit']['5']>=0.8
        report['quality_gate']={'minimum_hit5':0.8,'passed':passed}
        with (root/'report.json').open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
        evidence={'common_sha256':args.expected_common,'runtime_image':current['Image'],
            'probe_sha256':hashlib.sha256((root/'probe.zip').read_bytes()).hexdigest(),
            'candidateOnly':bool(args.candidate_jar),'source_jar_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
            'embeddingContainerId':embedding['Id'],'embeddingEndpointMode':args.endpoint_mode,
            'backend':'real-embedding-public-seed-isolated-inmemory','productionDatabaseMounted':False,
            'removedTrialContainers':len(removed),'productionServiceRestarted':False}
        with (root/'runtime-evidence.json').open('x') as f:json.dump(evidence,f,indent=2)
        print(json.dumps({'status':'PASSED' if passed else 'QUALITY_FAILED','overall':report['overall'],
            'sample_count':len(data),'repeats':args.repeats,'all_trials_hit_at_5_rate':report['all_trials_hit_at_5_rate']}))
        return 0 if passed else 1
    finally:
        assert folder.resolve().parent==pathlib.Path('/dev/shm') and folder.name.startswith('rag-eval-')
        shutil.rmtree(str(folder))
if __name__=='__main__':
    raise SystemExit(main())
