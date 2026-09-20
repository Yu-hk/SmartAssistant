"""Run a disposable JVM resolver probe against the deployed CNI network, no DB mounts."""
import argparse
import json
import pathlib
import shutil
import subprocess
import tempfile
import uuid
from container_dns_policy import internal_resolver, verify_resolvers

def run(*args):
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
    if p.returncode: raise RuntimeError('DNS probe container command failed')
    return p.stdout.decode().strip()

def inspect(name): return json.loads(run('docker','inspect',name))[0]

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',required=True)
    p.add_argument('--mode',choices=['inherited','internal'],required=True)
    p.add_argument('--repeats',type=int,default=100);args=p.parse_args()
    if not 1<=args.repeats<=200: raise ValueError('Invalid repeats')
    root=pathlib.Path(args.root)
    assert root.resolve()==root and root.parent==pathlib.Path('/opt/smart-assistant/releases')
    assert root.name.startswith('container-dns-') and root.is_dir()
    output=root/(args.mode+'.json');assert not output.exists()
    network=json.loads(run('docker','network','inspect','smart-network'))[0]
    gateway=internal_resolver(network)
    current=inspect('smart-consumer');targets={}
    for name in ['smart-embedding-service','smart-rabbitmq','smart-redis','smart-postgres']:
        d=inspect(name);assert d['State']['Running']
        targets[name]=(d['Id'],d['NetworkSettings']['Networks']['smart-network']['IPAddress'])
    folder=pathlib.Path(tempfile.mkdtemp(prefix='dns-probe-',dir='/dev/shm'))
    tag=uuid.uuid4().hex;cid=None
    try:
        shutil.copyfile(str(root/'ContainerDnsProbe.class'),str(folder/'ContainerDnsProbe.class'))
        command=['docker','create','--network','smart-network','--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges','--memory','128m','--cpus','1',
            '--label','smartassistant.dns-probe='+tag,'--tmpfs','/tmp:rw,size=16777216',
            '--mount','type=bind,source='+str(folder)+',target=/probe,readonly',
            '--entrypoint','java','-w','/probe']
        if args.mode=='internal': command+=['--dns',gateway]
        command += [current['Image'],'-Xmx64m','-cp','/probe','ContainerDnsProbe',str(args.repeats)]
        for name,(_,address) in targets.items(): command += [name,address]
        command += ['github.com','ANY','missing-'+tag+'.dns.podman','MISSING']
        cid=run(*command)
        meta=inspect(cid)
        assert all(m['Type']=='tmpfs' or (m['Type']=='bind' and m['Source']==str(folder) and not m['RW']) for m in meta['Mounts'])
        result=subprocess.run(['docker','start','-a',cid],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
        assert result.returncode in (0,1) and inspect(cid)['State']['ExitCode'] in (0,1)
        report=json.loads(result.stdout)
        assert report['schema']==1 and report['attempts']==args.repeats*6
        if args.mode=='internal': verify_resolvers(report['resolvers'],gateway)
        for name,(container_id,address) in targets.items():
            latest=inspect(name)
            assert latest['Id']==container_id and latest['State']['Running']
            assert latest['NetworkSettings']['Networks']['smart-network']['IPAddress']==address
        report.update(mode=args.mode,runtime_image=current['Image'],productionRestarted=False)
        with output.open('x') as f: json.dump(report,f,indent=2)
        print(json.dumps({'mode':args.mode,'attempts':report['attempts'],'failures':report['failures'],'resolvers':report['resolvers']}))
        return 0 if report['failures']==0 else 1
    finally:
        if cid:
            meta=inspect(cid)
            assert meta['Id']==cid and meta['Config']['Labels'].get('smartassistant.dns-probe')==tag
            run('docker','rm','-f',cid)
        assert folder.resolve().parent==pathlib.Path('/dev/shm') and folder.name.startswith('dns-probe-')
        shutil.rmtree(str(folder))

if __name__=='__main__': raise SystemExit(main())
