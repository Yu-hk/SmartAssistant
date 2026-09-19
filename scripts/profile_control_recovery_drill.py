"""Actual dump/restore + current independent-control replay, synthetic data only."""
import argparse
import json
import pathlib
import subprocess
import time
import uuid
from profile_control_recovery import replay_sql


def run(args,data=None,ok=True):
    result=subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
    if ok and result.returncode:raise RuntimeError('Isolated recovery drill failed')
    if not ok and not result.returncode:raise AssertionError('Expected recovery rejection')
    return result.stdout


def drill(image,repo):
    run(['docker','image','inspect',image]);cid=None;token=uuid.uuid4().hex
    try:
        cid=run(['docker','run','-d','--network','none','--label','smartassistant.control-recovery-fixture='+token,
            '--memory','512m','--tmpfs','/var/lib/postgresql/data:rw,size=268435456',
            '-e','POSTGRES_DB=profile_control_source_fixture','-e','POSTGRES_USER=fixture','-e','POSTGRES_PASSWORD=fixture-only',image]).decode().strip()
        def sql(body,restored=False,ok=True):
            return run(['docker','exec','-i',cid,'psql','--no-psqlrc','-qAt','-v','ON_ERROR_STOP=1','-U','fixture','-d',
                'profile_control_restored_fixture' if restored else 'profile_control_source_fixture'],body.encode() if isinstance(body,str) else body,ok).decode().strip()
        for attempt in range(40):
            ready=subprocess.run(['docker','exec',cid,'pg_isready','-h','127.0.0.1','-U','fixture','-d','profile_control_source_fixture'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            if ready.returncode==0:break
            time.sleep(.5)
        else:raise RuntimeError('Fixture not ready')
        schema='CREATE TABLE users(id bigint PRIMARY KEY);\n'
        for name in ('20260902_add_ecommerce_user_profiles.sql','20260918_add_profile_lifecycle.sql','20260919_add_profile_request_admission.sql',
                '20260919_add_profile_commit_candidates.sql','20260919_add_profile_entity_facts.sql','20260919_add_governed_agent_memory.sql',
                '20260919_add_profile_cleanup_jobs.sql','20260919_add_profile_control_archive.sql'):
            schema+=(repo/'docs/database/migrations'/name).read_text(encoding='utf-8')+'\n'
        schema+='CREATE TABLE routing_call_log(user_id bigint,user_input text,llm_received_question text);'
        sql(schema)
        source=sql('SELECT source_id FROM profile_control_source;')
        sql("""INSERT INTO users VALUES(91001),(91002);
INSERT INTO profile_lifecycle(user_id) VALUES(91001),(91002);
INSERT INTO user_profile_snapshot(user_id,schema_version,report,reliable) VALUES
 (91001,'fixture','{"preference":"synthetic erased"}',true),(91002,'fixture','{"preference":"synthetic kept"}',true);
INSERT INTO routing_call_log VALUES(91001,'original chat','synthetic derived'),(91002,'other chat','synthetic kept');
""")
        dump=run(['docker','exec',cid,'pg_dump','--no-owner','--no-privileges','-U','fixture','-d','profile_control_source_fixture'])
        event=dict(sequence=1,event_id=str(uuid.uuid4()),source_id=source,user_id=91001,generation=1,analysis_enabled=False)
        sql('CREATE DATABASE profile_control_restored_fixture;');sql(dump,True)
        assert sql('SELECT count(*) FROM user_profile_snapshot WHERE user_id=91001',True)=='1'
        recovery=replay_sql(source,[event]);sql(recovery,True);sql(recovery,True)
        assert sql('SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=91001',True)=='1|f'
        assert sql('SELECT count(*) FROM user_profile_snapshot WHERE user_id=91001',True)=='0'
        assert sql('SELECT count(*) FROM user_profile_snapshot WHERE user_id=91002',True)=='1'
        assert sql('SELECT count(*) FROM routing_call_log WHERE user_id=91001 AND user_input IS NOT NULL AND llm_received_question IS NULL',True)=='1'
        assert sql('SELECT count(*) FROM profile_cleanup_receipt WHERE state=\'PENDING\'',True)=='5'
        assert sql('SELECT count(*) FROM profile_cleanup_job',True)=='1'
        changed=dict(event,event_id=str(uuid.uuid4()));sql(replay_sql(source,[changed]),True,False)
        assert sql('SELECT event_id FROM profile_control_outbox',True)==event['event_id']
        # Fail midway and prove PG content/controls roll back as one transaction.
        sql('DROP DATABASE profile_control_restored_fixture;CREATE DATABASE profile_control_restored_fixture;')
        sql(dump,True)
        broken=recovery.replace('UPDATE routing_call_log','SELECT 1/0; UPDATE routing_call_log')
        sql(broken,True,False)
        assert sql('SELECT count(*) FROM user_profile_snapshot WHERE user_id=91001',True)=='1'
        assert sql('SELECT count(*) FROM profile_control_outbox',True)=='0'
        assert sql('SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=91001',True)=='0|t'
        print(json.dumps({'checks':12,'syntheticOnly':True,'productionDataModified':False,'allStoresVerified':False}))
    finally:
        if cid:
            labels=json.loads(run(['docker','inspect','--format','{{json .Config.Labels}}',cid]))
            if labels.get('smartassistant.control-recovery-fixture')!=token:raise RuntimeError('Unowned fixture')
            run(['docker','rm','-f',cid])


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--image',required=True)
    args=parser.parse_args();drill(args.image,pathlib.Path(__file__).resolve().parents[1])
