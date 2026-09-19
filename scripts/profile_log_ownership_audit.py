"""Read-only log ownership sampling in a disposable networkless Python container.

Approved originals remain untouched. No content, user IDs or filenames are emitted.
Counts are conservative hints, not proof that a whole log belongs to one user.
No log can be deleted based on these counts. Python 3.6+.
"""
import argparse
import gzip
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
import uuid

LIMIT=16*1024*1024
IMAGE='d9a5b0cd892677dd43fa6f60adba9c06f68d7fd1da09b04f15a7b0f468e4c3f8'
OWNER=re.compile(rb'(?i)(?:user[_-]?id|principalUserId)[\s"\x27\\]*[=:][\s"\x27\\]*([1-9][0-9]{0,17})(?![0-9])')
PROFILE=re.compile(rb'(?i)profile|preference|agent.memory|memory.extract|personaliz|user:memory|user_profile')

def counts(data):
    result=dict(lines=0,profileHintLines=0,profileHintWithOwner=0,profileHintWithoutOwner=0,explicitOwnerLines=0)
    for line in data.splitlines():
        owner=bool(OWNER.search(line));profile=bool(PROFILE.search(line))
        result['lines']+=1;result['explicitOwnerLines']+=int(owner);result['profileHintLines']+=int(profile)
        result['profileHintWithOwner']+=int(profile and owner);result['profileHintWithoutOwner']+=int(profile and not owner)
    return result

def run(args,data=None):
    result=subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
    if result.returncode:raise RuntimeError('Isolated log audit unavailable')
    return result.stdout

def audit():
    from profile_storage_inventory import storage_plan,allowed
    root=pathlib.Path('/opt/smart-assistant');sources={root};explicit=set();cid=None
    names=run(['docker','ps','-a','--format','{{.Names}}']).decode().splitlines()
    for name in names:
        if not re.fullmatch(r'smart-(consumer|router|product|order)(?:-[a-zA-Z0-9_-]+)?',name):continue
        meta=json.loads(run(['docker','inspect',name]))[0]
        for unused,path in storage_plan(meta):
            path=pathlib.Path(path)
            if allowed(path) and path.is_dir():sources.add(path)
        path=meta.get('LogPath')
        if path:explicit.add(pathlib.Path(path))
    files=set(explicit);entries=0
    for source in sources:
        for directory,dirs,names in os.walk(str(source),followlinks=False):
            dirs[:]=[d for d in dirs if not pathlib.Path(directory,d).is_symlink()]
            for name in names:
                entries+=1
                if entries>100000:raise RuntimeError('Log inventory limit')
                if re.search(r'\.log(?:[.-][0-9-]+)?(?:\.gz)?$',name):files.add(pathlib.Path(directory,name))
    rows=[];total=0;token=uuid.uuid4().hex
    try:
        script=pathlib.Path(__file__).resolve()
        cid=run(['docker','create','--network','none','--read-only','--memory','256m','--pids-limit','32',
            '--cap-drop','ALL','--security-opt','no-new-privileges','--label','smartassistant.log-ownership-audit='+token,
            '--mount','type=bind,source='+str(script)+',target=/audit.py,readonly','--entrypoint','python3',IMAGE,
            '-c','import time;time.sleep(900)']).decode().strip()
        run(['docker','start',cid])
        for path in sorted(files):
            row={'object':hashlib.sha256(str(path).encode()).hexdigest()[:16],'status':'UNVERIFIED'};rows.append(row)
            try:
                if path.resolve()!=path:row['status']='SYMLINK_NOT_READ';continue
                info=path.lstat()
                if not stat.S_ISREG(info.st_mode):row['status']='SPECIAL_NOT_READ';continue
                if info.st_size>LIMIT:row['status']='SIZE_LIMIT';continue
                with os.fdopen(os.open(str(path),os.O_RDONLY|os.O_NOFOLLOW),'rb') as stream:
                    before=os.fstat(stream.fileno())
                    if path.name.endswith('.gz'):
                        with gzip.GzipFile(fileobj=stream) as expanded:data=expanded.read(LIMIT+1)
                    else:data=stream.read(LIMIT+1)
                total+=len(data)
                if total>256*1024*1024:raise RuntimeError('Total log inspection limit')
                if len(data)>LIMIT:row['status']='EXPANSION_LIMIT';continue
                result=json.loads(run(['docker','exec','-i',cid,'python3','/audit.py','--parse-stdin'],data))
                row.update(result);row['bytesInspected']=len(data)
                after=path.stat()
                row['status']='COUNTED' if (before.st_ino,before.st_size,before.st_mtime_ns)==(after.st_ino,after.st_size,after.st_mtime_ns) else 'CHANGED_SNAPSHOT'
            except (OSError,EOFError,ValueError):row['status']='UNREADABLE'
    finally:
        if cid:
            labels=json.loads(run(['docker','inspect','--format','{{json .Config.Labels}}',cid]))
            if labels.get('smartassistant.log-ownership-audit')!=token:raise RuntimeError('Unowned audit container')
            run(['docker','rm','-f',cid])
    return dict(objects=rows,originalsModified=False,contentsReported=False,identitiesReported=False,
                ownership='Explicit markers only; unmarked lines cannot be attributed',temporaryContainerRemoved=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parse-stdin',action='store_true');parser.add_argument('--confirm-real-log-audit',action='store_true')
    args=parser.parse_args()
    if args.parse_stdin:
        data=sys.stdin.buffer.read(LIMIT+1)
        if len(data)>LIMIT:raise ValueError('Input limit')
        print(json.dumps(counts(data)))
    elif args.confirm_real_log_audit:print(json.dumps(audit()))
    else:parser.error('Explicit isolated log audit approval required')
