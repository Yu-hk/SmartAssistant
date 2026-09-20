"""Synthetic bootstrap/dump/restore test. No production connections or persistent mounts.

Requires an already-local PG16 + pgvector image. Never pulls, migrates a running
database, imports real backups, or claims a full historical upgrade/DR test.
Python 3.6+; subprocess diagnostics deliberately exclude SQL and data.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import time
import uuid

LABEL = 'smartassistant.database-bootstrap-drill'
SOURCE = 'bootstrap_fixture_source'
RESTORED = 'bootstrap_fixture_restored'
FILES = ('docs/database/schema.sql', 'docs/database/seed_data.sql')
_LITERAL = r"'(?:[^']|'')*'::character varying"
_VARCHAR_ARRAY = re.compile(r"\(ARRAY\[(" + _LITERAL + r"(?:, " + _LITERAL + r")*)\]\)::text\[\]")


def canonical_varchar_literal_arrays(definition):
    """PG dump/reparse distributes an unbounded varchar[] -> text[] cast.

    Normalize ONLY arrays of non-null, unbounded varchar string literals; keep
    values, order and all other expressions/casts. Never strip casts globally.
    """
    def replace(match):
        literals = re.findall(_LITERAL, match[1])
        return 'ARRAY[' + ', '.join('(' + x + ')::text' for x in literals) + ']'
    return _VARCHAR_ARRAY.sub(replace, definition)


def command(args, data=None):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    if result.returncode:
        match = re.search(rb'ERROR:\s+([0-9A-Z]{5})(?::|\s|$)', result.stderr)
        raise RuntimeError('Fixture command failed' + (' SQLSTATE=' + match[1].decode() if match else ''))
    return result.stdout


def validate_container(meta, container_id, tag):
    if meta.get('Id') != container_id or meta.get('Config', {}).get('Labels', {}).get(LABEL) != tag:
        raise ValueError('Fixture ownership mismatch')
    host = meta['HostConfig']
    if host['NetworkMode'] != 'none' or host.get('Binds') or host.get('PortBindings') or host.get('Privileged'):
        raise ValueError('Fixture isolation mismatch')
    mounts = meta.get('Mounts', [])
    if any(m['Type'] != 'tmpfs' for m in mounts):
        raise ValueError('Persistent fixture mount rejected')
    # Podman reports --tmpfs in HostConfig.Tmpfs, not in Mounts (even when running).
    if '/var/lib/postgresql/data' not in (host.get('Tmpfs') or {}):
        raise ValueError('Ephemeral database mount required')


def qualified(name):
    if not re.fullmatch(r'[a-z_][a-z0-9_]*', name):
        raise ValueError('Unexpected catalog identifier')
    return 'public."' + name + '"'


def compare_snapshots(source, restored):
    if not source or source != restored:
        raise AssertionError('Restored database differs from source')


def drill(image, repo, report_path):
    if report_path.exists():
        raise ValueError('Refusing to overwrite an existing report')
    # Read exact files before creating any container; do not silently sanitize SQL.
    inputs = {name: (repo / name).read_bytes() for name in FILES}
    image_id = json.loads(command(['docker', 'image', 'inspect', image]))[0]['Id']
    if not re.fullmatch(r'(sha256:)?[0-9a-f]{64}', image_id):
        raise ValueError('Immutable local image ID required')
    tag = uuid.uuid4().hex
    cid = None
    phase = 'create'
    report = {'status': 'FAILED', 'phase': phase, 'productionConnected': False,
              'inputSha256': {k: hashlib.sha256(v).hexdigest() for k, v in inputs.items()},
              'runtimeImage': image_id, 'fixtureRemoved': False}
    try:
        cid = command(['docker', 'create', '--name', 'db-bootstrap-' + tag,
                       '--label', LABEL + '=' + tag, '--network', 'none', '--memory', '512m',
                       '--cpus', '1', '--tmpfs', '/var/lib/postgresql/data:rw,size=268435456',
                       '-e', 'PGDATA=/var/lib/postgresql/data', '-e', 'POSTGRES_USER=postgres',
                       '-e', 'POSTGRES_PASSWORD=synthetic-only', '-e', 'POSTGRES_DB=' + SOURCE,
                       image_id]).decode().strip()
        def inspect():
            return json.loads(command(['docker', 'inspect', cid]))[0]
        validate_container(inspect(), cid, tag)
        command(['docker', 'start', cid])
        def sql(text, db=SOURCE):
            return command(['docker', 'exec', '-i', '-e', 'PGPASSWORD=synthetic-only', cid,
                            'psql', '--no-psqlrc', '-qAt', '-h', '127.0.0.1', '-U', 'postgres', '-d', db,
                            '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate'],
                           text.encode('utf-8') if isinstance(text, str) else text).decode().strip()
        phase = 'readiness'
        for unused in range(60):
            try:
                if sql('SELECT 1;') == '1': break
            except RuntimeError: pass
            time.sleep(1)
        else: raise RuntimeError('Fixture readiness timeout')
        version = int(sql("SHOW server_version_num;"))
        if not 160000 <= version < 170000: raise ValueError('PG16 fixture required')
        report['serverVersionNum'] = version
        for name, data in inputs.items():
            phase = name
            sql(data)
        phase = 'bootstrap-contract'
        if [sql('SELECT count(*) FROM ' + qualified(t) + ';') for t in ['products', 'orders', 'user_coupons']] != ['6', '5', '9']:
            raise AssertionError('Expected public seed row counts')
        for table in ['profile_cleanup_job', 'profile_cleanup_receipt', 'profile_lifecycle', 'profile_request_admission']:
            if sql("SELECT to_regclass('" + qualified(table) + "') IS NOT NULL;") != 't':
                raise AssertionError('Required bootstrap table missing')
        def snapshot(db):
            tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;", db).splitlines()
            state = {}
            for table in tables:
                table_ref = qualified(table)
                data = sql("SELECT count(*)::text || ':' || md5(COALESCE(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) FROM " + table_ref + " t;", db)
                state[table] = data
            state['columns'] = sql("SELECT json_agg(x ORDER BY table_name,ordinal_position) FROM (SELECT table_name,column_name,ordinal_position,data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema='public') x;", db)
            state['constraints'] = sql("SELECT json_agg(x ORDER BY relname,conname) FROM (SELECT c.relname,k.conname,pg_get_constraintdef(k.oid) AS definition FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public') x;", db)
            state['indexes'] = sql("SELECT json_agg(x ORDER BY tablename,indexname) FROM (SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public') x;", db)
            for component in ['constraints', 'indexes']:
                state[component] = canonical_varchar_literal_arrays(state[component])
            sequences = sql("SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename;", db).splitlines()
            state['sequences'] = {s: sql('SELECT last_value::text || \':\' || is_called::text FROM ' + qualified(s) + ';', db) for s in sequences}
            return state, len(tables)
        phase = 'source-snapshot'
        source, count = snapshot(SOURCE)
        phase = 'dump-restore'
        dump = command(['docker', 'exec', cid, 'pg_dump', '-U', 'postgres', '-d', SOURCE, '--no-owner', '--no-acl'])
        sql('CREATE DATABASE ' + RESTORED + ';')
        sql(dump, RESTORED)
        phase = 'restore-contract'
        restored, restored_count = snapshot(RESTORED)
        if source != restored:
            report['mismatchedComponents'] = sorted(k for k in set(source) | set(restored) if source.get(k) != restored.get(k))
            for component in ['columns', 'constraints', 'indexes']:
                if source.get(component) != restored.get(component):
                    left = json.loads(source[component]); right = json.loads(restored[component])
                    report[component + 'Differences'] = [x for x in left + right if x not in left or x not in right]
        compare_snapshots(source, restored)
        if count != restored_count: raise AssertionError('Restored table count changed')
        report.update(status='PASSED', phase='verified', tables=count, seededRows=20,
                      dumpSha256=hashlib.sha256(dump).hexdigest(), structureDataAndSequencesMatch=True)
    except Exception as error:
        report.update(phase=phase, errorType=type(error).__name__)
        if isinstance(error, RuntimeError): report['diagnostic'] = str(error)
        raise
    finally:
        try:
            if cid:
                validate_container(json.loads(command(['docker', 'inspect', cid]))[0], cid, tag)
                command(['docker', 'rm', '-f', cid])
                report['fixtureRemoved'] = True
        except Exception:
            report.update(status='FAILED', cleanupFailed=True)
            raise
        finally:
            with report_path.open('x', encoding='utf-8') as f:
                json.dump(report, f, ensure_ascii=False, indent=2)
    return report


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--image', required=True); p.add_argument('--repo', type=pathlib.Path, required=True)
    p.add_argument('--report', type=pathlib.Path, required=True)
    args = p.parse_args()
    print(json.dumps(drill(args.image, args.repo.resolve(), args.report)))
