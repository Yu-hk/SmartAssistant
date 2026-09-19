"""Restore the independent, payload-free production deletion control stream.

Does not restore a backup or start traffic. Default is verification only. Applying
requires an isolated labelled fixture, or all known production entry/business
containers stopped. Historical files/logs and backups are never removed here.
Python 3.6+. Same-host root/disk rollback and whole-host loss are not covered.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import uuid


def small(root, name):
    path=root/name
    info=path.lstat()
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode)!=0o600 or info.st_uid!=root.stat().st_uid:
        raise ValueError('Unsafe control file')
    with os.fdopen(os.open(str(path),os.O_RDONLY|os.O_NOFOLLOW),'rb') as stream:data=stream.read(513)
    if len(data)>512:raise ValueError('Control file too large')
    return data


def journal(root,source):
    source=str(uuid.UUID(source))
    if not root.is_absolute() or root.resolve()!=root or root.parent==root or not root.is_dir() or stat.S_IMODE(root.stat().st_mode)!=0o700:
        raise ValueError('Unsafe independent directory')
    if small(root,'source.pin')!=('1|'+source+'\n').encode('ascii'):raise ValueError('Source pin mismatch')
    entries=list(root.iterdir())
    if len(entries)>10103:raise ValueError('Control capacity exceeded')
    names=[]
    for entry in entries:
        if re.fullmatch(r'[0-9]{20}\.ctl',entry.name):names.append(entry.name)
        elif entry.name not in ('source.pin','HEAD','writer.lock') and not re.fullmatch(r'pending-[0-9a-f-]{36}\.tmp',entry.name):raise ValueError('Unexpected control entry')
    previous='0'*64;hashes=[previous];events=[];seen=set();generations={}
    for index,name in enumerate(sorted(names),1):
        if index>10000 or name!='%020d.ctl'%index:raise ValueError('Control history hole')
        data=small(root,name);parts=data.decode('ascii').rstrip('\n').split('|')
        if len(parts)!=8 or parts[0]!='1' or parts[1]!=source or parts[2]!=str(index) or parts[6] not in ('0','1') or parts[7]!=previous:
            raise ValueError('Control chain mismatch')
        identity=str(uuid.UUID(parts[3]))
        if identity!=parts[3] or identity in seen:raise ValueError('Invalid event identity')
        seen.add(identity)
        user,generation=int(parts[4]),int(parts[5])
        if not 0<user<2**63 or not 0<generation<2**63 or parts[4]!=str(user) or parts[5]!=str(generation):raise ValueError('Invalid owner or generation')
        old=generations.get(user,0)
        if generation!=old+1 or (old==0 and parts[6]!='0'):raise ValueError('Invalid lifecycle sequence')
        generations[user]=generation
        if data!=('|'.join(parts)+'\n').encode('ascii'):raise ValueError('Noncanonical control event')
        events.append(dict(sequence=index,event_id=identity,source_id=source,user_id=user,generation=generation,analysis_enabled=parts[6]=='1'))
        previous=hashlib.sha256(data).hexdigest();hashes.append(previous)
    head=small(root,'HEAD').decode('ascii').rstrip('\n').split('|')
    if len(head)!=3 or head[0]!='1' or not re.fullmatch(r'0|[1-9][0-9]*',head[1]):raise ValueError('Invalid acknowledgement')
    acknowledged=int(head[1])
    if acknowledged>len(events) or head[2]!=hashes[acknowledged]:raise ValueError('Acknowledgement mismatch')
    # Published suffixes are authoritative too, even when a crash preceded ACK.
    return source,events


def replay_sql(source,events):
    """Only validated journal records should enter here; validate again for callers."""
    source=str(uuid.UUID(source));seen=set();latest={};values=[]
    if not isinstance(events,list) or len(events)>10000:raise ValueError('Invalid stream')
    for index,row in enumerate(events,1):
        if set(row)!=set(('sequence','event_id','source_id','user_id','generation','analysis_enabled')) or row['source_id']!=source or row['sequence']!=index:
            raise ValueError('Invalid control stream')
        uid,gen=row['user_id'],row['generation'];eid=str(uuid.UUID(row['event_id']))
        if eid!=row['event_id'] or eid in seen or type(uid)!=int or type(gen)!=int or not 0<uid<2**63 or not 0<gen<2**63 or type(row['analysis_enabled'])!=bool:
            raise ValueError('Invalid control event')
        if gen!=latest.get(uid,{}).get('generation',0)+1 or (uid not in latest and row['analysis_enabled']):raise ValueError('Invalid control generation')
        seen.add(eid);latest[uid]=row
        values.append("(%d,'%s','%s',%d,%d,%s)"%(index,eid,source,uid,gen,str(row['analysis_enabled']).lower()))
    # The current shipped API only pauses/deletes; it has no reopen operation.
    # Refuse a future reopen stream instead of erasing newly consented profiles.
    if any(row['analysis_enabled'] for row in latest.values()):raise ValueError('Reopened profile requires a version-aware recovery plan')
    sql="""BEGIN;
SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='10s';
LOCK TABLE profile_lifecycle,profile_control_source,profile_control_outbox IN EXCLUSIVE MODE;
CREATE TEMP TABLE recovered_control(LIKE profile_control_outbox INCLUDING ALL) ON COMMIT DROP;
"""
    if values:sql+='INSERT INTO recovered_control VALUES '+','.join(values)+';\n'
    sql+="""DO $$ BEGIN
 IF (SELECT count(*) FROM profile_control_source)<>1
 OR EXISTS(SELECT 1 FROM profile_control_source s WHERE s.last_sequence<>(SELECT count(*) FROM profile_control_outbox))
 OR EXISTS(SELECT 1 FROM profile_control_outbox e,profile_control_source s WHERE e.source_id<>s.source_id)
 OR (SELECT coalesce(max(sequence),0) FROM profile_control_outbox)<>(SELECT count(*) FROM profile_control_outbox) THEN
  RAISE EXCEPTION 'Restored control header is inconsistent'; END IF;
 IF EXISTS(SELECT 1 FROM profile_control_outbox e WHERE NOT EXISTS
  (SELECT 1 FROM recovered_control r WHERE (r.sequence,r.event_id,r.source_id,r.user_id,r.generation,r.analysis_enabled)
   =(e.sequence,e.event_id,e.source_id,e.user_id,e.generation,e.analysis_enabled))) THEN
  RAISE EXCEPTION 'Restored database has conflicting controls'; END IF;
END $$;
CREATE TEMP TABLE recovered_latest ON COMMIT DROP AS
 SELECT DISTINCT ON(user_id) user_id,generation,analysis_enabled FROM recovered_control ORDER BY user_id,generation DESC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM profile_lifecycle l JOIN recovered_latest r USING(user_id) WHERE l.generation>r.generation)
 OR EXISTS(SELECT 1 FROM profile_lifecycle l WHERE (l.generation>0 OR NOT l.analysis_enabled)
  AND NOT EXISTS(SELECT 1 FROM recovered_latest r WHERE r.user_id=l.user_id)) THEN
  RAISE EXCEPTION 'Independent stream does not cover restored lifecycles'; END IF;
END $$;
INSERT INTO profile_control_outbox SELECT * FROM recovered_control ON CONFLICT DO NOTHING;
"""
    sql+="UPDATE profile_control_source SET source_id='%s',last_sequence=%d WHERE singleton;\n"%(source,len(events))
    sql+="""INSERT INTO profile_lifecycle(user_id,generation,analysis_enabled)
 SELECT r.user_id,r.generation,false FROM recovered_latest r JOIN users u ON u.id=r.user_id
 ON CONFLICT(user_id) DO UPDATE SET generation=EXCLUDED.generation,analysis_enabled=false,updated_at=CURRENT_TIMESTAMP;
"""
    for table in ('profile_commit_candidate','profile_agent_memory','user_profile_entity_fact','user_profile_change_log','user_profile_snapshot'):
        sql+='DELETE FROM '+table+' p USING recovered_latest r WHERE p.user_id=r.user_id;\n'
    sql+="""UPDATE routing_call_log p SET llm_received_question=NULL FROM recovered_latest r WHERE p.user_id=r.user_id;
-- Recreate retryable online cleanup work. Redis/legacy files and diagnostic TTLs
-- must be checked by the current adapters before any user sees ONLINE_CLEANED.
INSERT INTO profile_cleanup_job(job_id,user_id,idempotency_key,generation,state)
 SELECT gen_random_uuid(),r.user_id,gen_random_uuid(),r.generation,'PAUSED'
 FROM recovered_latest r JOIN users u ON u.id=r.user_id
 WHERE NOT EXISTS(SELECT 1 FROM profile_cleanup_job j WHERE j.user_id=r.user_id AND j.generation=r.generation);
UPDATE profile_cleanup_job j SET state='PAUSED' FROM recovered_latest r WHERE j.user_id=r.user_id AND j.generation=r.generation;
INSERT INTO profile_cleanup_receipt(job_id,target,state)
 SELECT j.job_id,t.target,'PENDING' FROM profile_cleanup_job j JOIN recovered_latest r
 ON r.user_id=j.user_id AND r.generation=j.generation
 CROSS JOIN (VALUES('POSTGRES_PROFILE'),('REDIS_INDEXED'),('LEGACY_STORAGE'),('DERIVED_COPIES'),('BACKUP_RESTORE')) t(target)
 ON CONFLICT(job_id,target) DO UPDATE SET state='PENDING',error_code=NULL,next_attempt_at=CURRENT_TIMESTAMP;
DO $$ DECLARE seq text; maximum bigint; BEGIN
 IF EXISTS(SELECT 1 FROM recovered_latest r LEFT JOIN users u ON u.id=r.user_id WHERE u.id IS NULL) THEN
  seq:=pg_get_serial_sequence('users','id');
  IF seq IS NULL THEN RAISE EXCEPTION 'Missing historical owners require allocator review'; END IF;
  SELECT greatest(coalesce(max(user_id),0),(SELECT coalesce(max(id),0) FROM users)) INTO maximum FROM recovered_latest;
  -- Never regress the existing allocator. Sequence increments are not rolled back.
  PERFORM setval(seq,greatest(maximum,nextval(seq)),true);
 END IF;
END $$;
COMMIT;
"""
    return sql


def command(args,data=None):
    result=subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
    if result.returncode:raise RuntimeError('Offline recovery command failed')
    return result.stdout


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--control-dir',type=pathlib.Path,required=True)
    parser.add_argument('--expected-source-id',required=True)
    parser.add_argument('--apply-container')
    parser.add_argument('--offline-production',action='store_true')
    args=parser.parse_args()
    # POSIX record lock interoperates with Java FileChannel.lock (not flock).
    import fcntl
    lock=args.control_dir/'writer.lock'
    small(args.control_dir,'writer.lock')
    fd=os.open(str(lock),os.O_RDWR|os.O_NOFOLLOW)
    try:
        fcntl.lockf(fd,fcntl.LOCK_SH|fcntl.LOCK_NB)
        source,events=journal(args.control_dir,args.expected_source_id)
        if args.apply_container:
            meta=json.loads(command(['docker','inspect',args.apply_container]))[0]
            if args.offline_production:
                if args.apply_container!='smart-postgres' or args.control_dir!=pathlib.Path('/opt/smart-assistant-control'):raise ValueError('Explicit production scope required')
                names=command(['docker','ps','-a','--format','{{.Names}}']).decode().splitlines()
                business=[name for name in names if re.fullmatch(r'smart-(gateway|user|consumer|router|product|order)(?:-[a-zA-Z0-9_-]+)?',name)]
                if not set(('smart-gateway','smart-user','smart-consumer','smart-router','smart-product','smart-order')).issubset(business):raise ValueError('Incomplete production inventory')
                for name in business:
                    if json.loads(command(['docker','inspect',name]))[0]['State']['Running']:raise ValueError('Business services must remain stopped')
            elif meta['HostConfig']['NetworkMode']!='none' or meta['Config'].get('Labels',{}).get('smartassistant.profile-restore-isolated')!='true':
                raise ValueError('Dedicated isolated restore fixture required')
            command(['docker','exec','-i',meta['Id'],'sh','-c','exec psql --no-psqlrc -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],replay_sql(source,events).encode('utf-8'))
            assert journal(args.control_dir,args.expected_source_id)==(source,events)
        print(json.dumps({'verifiedEvents':len(events),'controlledOwners':len(set(r['user_id'] for r in events)),
            'postgresReplayApplied':bool(args.apply_container),'trafficStarted':False,'allStoresVerified':False}))
    finally:os.close(fd)


if __name__=='__main__':main()
