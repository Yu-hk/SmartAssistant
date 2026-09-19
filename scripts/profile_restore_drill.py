"""Synthetic-only PostgreSQL dump/restore drill; never connects to production.

Creates an unnetworked, tmpfs-only container from an ALREADY LOCAL image. Uses actual
repository migrations, pg_dump and psql; destroys only the container it created.
The SQL builder is an experimental PG barrier, NOT a production restore procedure:
external tombstone authority, Redis/MQ/files and derived copies remain unverified.
Python 3.6+.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import time
import uuid

MIGRATIONS = ('20260902_add_ecommerce_user_profiles.sql', '20260918_add_profile_lifecycle.sql',
              '20260919_add_profile_request_admission.sql', '20260919_add_profile_commit_candidates.sql',
              '20260919_add_profile_entity_facts.sql', '20260919_add_governed_agent_memory.sql',
              '20260919_add_profile_cleanup_jobs.sql')
PAYLOAD_TABLES = ('profile_commit_candidate', 'profile_agent_memory', 'user_profile_entity_fact',
                  'user_profile_change_log', 'user_profile_snapshot')

def barrier_sql(ledger):
    """Fail closed on missing/ambiguous control metadata; all mutations are one transaction."""
    if not isinstance(ledger, list) or not ledger or len(ledger) > 10000:
        raise ValueError('Nonempty bounded external tombstone ledger required')
    seen = set(); rows = []
    for entry in ledger:
        if not isinstance(entry, dict) or set(entry) != {'user_id', 'generation'}:
            raise ValueError('Invalid tombstone fields')
        uid, gen = entry['user_id'], entry['generation']
        if type(uid) is not int or type(gen) is not int or not 0 < uid < 2**63 or not 0 < gen < 2**63 or uid in seen:
            raise ValueError('Invalid or duplicate tombstone identity/generation')
        seen.add(uid); rows.append('(%d,%d)' % (uid, gen))
    sql = """BEGIN;
SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='10s';
DO $$ BEGIN IF current_database() <> 'profile_restore_drill_restored' THEN
 RAISE EXCEPTION 'Synthetic restore database required'; END IF; END $$;
CREATE TEMP TABLE restore_tombstone(user_id bigint PRIMARY KEY,generation bigint NOT NULL) ON COMMIT DROP;
INSERT INTO restore_tombstone VALUES """ + ','.join(rows) + """;
-- Rehearsal is offline: no business readers/writers may start before this commits.
LOCK TABLE profile_lifecycle IN EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM restore_tombstone t LEFT JOIN users u ON u.id=t.user_id WHERE u.id IS NULL)
 OR EXISTS (SELECT 1 FROM restore_tombstone t JOIN profile_lifecycle l USING(user_id) WHERE l.generation>t.generation)
 THEN RAISE EXCEPTION 'Unknown user or stale tombstone ledger'; END IF;
END $$;
INSERT INTO profile_lifecycle(user_id,generation,analysis_enabled)
 SELECT user_id,generation,false FROM restore_tombstone
 ON CONFLICT(user_id) DO UPDATE SET generation=EXCLUDED.generation,analysis_enabled=false,updated_at=CURRENT_TIMESTAMP;
"""
    for table in PAYLOAD_TABLES:
        sql += 'DELETE FROM public.%s p USING restore_tombstone t WHERE p.user_id=t.user_id;\n' % table
    return sql + 'COMMIT;'

def command(args, data=None, expect_success=True):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
    if expect_success and result.returncode:
        # Do not echo SQL, payloads or environmental credentials.
        raise RuntimeError('Fixture command failed: ' + args[0])
    if not expect_success and result.returncode == 0:
        raise AssertionError('Expected fail-closed rejection')
    return result.stdout

def drill(image, repo):
    # No pull: an absent image is an explicit prerequisite failure.
    command(['docker', 'image', 'inspect', image])
    name = 'profile-restore-drill-' + uuid.uuid4().hex[:12]
    label = 'smartassistant.profile-restore-fixture'
    cid = None
    try:
        cid = command(['docker', 'run', '-d', '--name', name, '--label', label + '=true',
                       '--network', 'none', '--memory', '512m', '--tmpfs', '/var/lib/postgresql/data:rw',
                       '-e', 'PGDATA=/var/lib/postgresql/data', '-e', 'POSTGRES_USER=profile_fixture',
                       '-e', 'POSTGRES_PASSWORD=synthetic-only', '-e', 'POSTGRES_DB=profile_restore_drill_source',
                       image]).decode().strip()
        meta = json.loads(command(['docker', 'inspect', cid]))[0]
        if meta['HostConfig']['NetworkMode'] != 'none' or meta['HostConfig'].get('Binds'):
            raise RuntimeError('Fixture isolation mismatch')
        for unused in range(45):
            check = subprocess.run(['docker', 'exec', cid, 'pg_isready', '-U', 'profile_fixture'],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            if check.returncode == 0: break
            time.sleep(1)
        else: raise RuntimeError('Fixture database unavailable')
        def sql(text, restored=False, success=True):
            db = 'profile_restore_drill_restored' if restored else 'profile_restore_drill_source'
            return command(['docker', 'exec', '-i', cid, 'psql', '--no-psqlrc', '-qAt',
                            '-v', 'ON_ERROR_STOP=1', '-U', 'profile_fixture', '-d', db],
                           text.encode('utf-8') if isinstance(text, str) else text, success).decode().strip()
        schema = 'CREATE TABLE public.users(id bigint PRIMARY KEY);\n'
        for filename in MIGRATIONS:
            schema += (repo / 'docs/database/migrations' / filename).read_text(encoding='utf-8') + '\n'
        sql(schema)
        sql("""INSERT INTO users VALUES(91001),(91002);
INSERT INTO profile_lifecycle(user_id) VALUES(91001),(91002);
CREATE TABLE fixture_conversations(user_id bigint,body text);
INSERT INTO fixture_conversations VALUES(91001,'synthetic chat retained'),(91002,'other chat retained');
INSERT INTO user_profile_snapshot(user_id,schema_version,report,reliable) VALUES
 (91001,'fixture','{"preference":"erased fixture"}',true),(91002,'fixture','{"preference":"survivor fixture"}',true);
INSERT INTO user_profile_change_log(event_id,user_id,base_version,new_version,action,prompt_version,after_hash)
 VALUES('fixture-erase',91001,0,1,'CREATE','fixture','hash'),('fixture-keep',91002,0,1,'CREATE','fixture','hash');
INSERT INTO user_profile_entity_fact(user_id,generation,category,fact_value) VALUES(91001,0,'preference','fixture'),(91002,0,'preference','keep');
INSERT INTO profile_agent_memory(user_id,agent,memory_key,memory_value,generation) VALUES(91001,'product','preference','fixture',0),(91002,'product','preference','keep',0);
INSERT INTO profile_commit_candidate(candidate_id,user_id,request_id,generation,payload,expires_at)
 VALUES('fixture-erase',91001,'fixture-erase',0,'{"fixture":true}',CURRENT_TIMESTAMP+INTERVAL '1 day'),
 ('fixture-keep',91002,'fixture-keep',0,'{"fixture":true}',CURRENT_TIMESTAMP+INTERVAL '1 day');
INSERT INTO profile_request_admission(user_id,request_hash,input_hash,generation) VALUES(91001,repeat('a',64),repeat('b',64),0);
""")
        dump = command(['docker', 'exec', cid, 'pg_dump', '-U', 'profile_fixture',
                        '-d', 'profile_restore_drill_source', '--no-owner', '--no-acl'])
        # External control-plane state survives independently of the older data backup.
        sql('UPDATE profile_lifecycle SET generation=1,analysis_enabled=false WHERE user_id=91001;')
        ledger = [{'user_id': 91001, 'generation': 1}]
        assert sql('SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=91001') == '1|f'
        sql('CREATE DATABASE profile_restore_drill_restored;')
        sql(dump, True)
        assert sql('SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=91001', True) == '0|t'
        barrier = barrier_sql(ledger)
        sql(barrier.replace('COMMIT;', 'SELECT 1/0;\nCOMMIT;'), True, False)
        assert sql('SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=91001', True) == '0|t'
        assert sql('SELECT count(*) FROM user_profile_snapshot WHERE user_id=91001', True) == '1'
        sql(barrier, True)
        sql(barrier, True)  # Replay is idempotent, admissions/tombstones survive.
        for table in PAYLOAD_TABLES:
            assert sql('SELECT count(*) FROM %s WHERE user_id=91001' % table, True) == '0'
            assert sql('SELECT count(*) FROM %s WHERE user_id=91002' % table, True) == '1'
        assert sql('SELECT count(*) FROM fixture_conversations', True) == '2'
        assert sql('SELECT count(*) FROM users', True) == '2'
        assert sql('SELECT count(*) FROM profile_request_admission', True) == '1'
        assert sql('SELECT count(*) FROM profile_request_admission a JOIN profile_lifecycle l USING(user_id) '
                   'WHERE a.generation=l.generation AND l.analysis_enabled', True) == '0'
        sql('UPDATE profile_lifecycle SET generation=2,analysis_enabled=true WHERE user_id=91001;', True)
        sql(barrier, True, False)
        assert sql('SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=91001', True) == '2|t'
        # Missing control ledger cannot even generate a barrier.
        try: barrier_sql([])
        except ValueError: pass
        else: raise AssertionError('Empty ledger accepted')
        return {'passed': True, 'syntheticOnly': True, 'network': 'none', 'payloadTables': list(PAYLOAD_TABLES),
                'dumpSha256': hashlib.sha256(dump).hexdigest(), 'checks': ['real-dump-restore', 'older-data-reappears',
                'atomic-rollback', 'tombstone-replay', 'idempotency', 'survivor-and-business-data-preserved',
                'old-admission-rejected', 'stale-ledger-rejected', 'missing-ledger-rejected'],
                'productionRestoreCertified': False}
    finally:
        if cid:
            meta = json.loads(command(['docker', 'inspect', cid]))[0]
            if meta['Config']['Labels'].get(label) != 'true' or not meta['Name'].lstrip('/').startswith('profile-restore-drill-'):
                raise RuntimeError('Refusing to remove unowned container')
            command(['docker', 'rm', '-f', cid])

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--repo', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    print(json.dumps(drill(args.image, args.repo), indent=2))
