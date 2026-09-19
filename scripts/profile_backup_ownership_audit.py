"""Authorized REAL backup audit in disposable, unnetworked PostgreSQL containers.

Only accepts the known server backup root and its pre-existing catalog. Never
prints database rows, credentials, SQL error text or log bodies. Originals are
read-only and temporary restored data is removed with the exact owned container.
This is ownership evidence, NOT authorization to delete originals or open traffic.
"""
import hashlib
import json
import os
import pathlib
import subprocess
import time
import uuid
import re

ROOT = pathlib.Path('/opt/smart-assistant/backups')
CATALOG = pathlib.Path('/opt/smart-assistant/releases/profile-diagnostics-20260919/backup-catalog.json')
IMAGE = '56a5b20c7a157dbb94149ba3cce70da76f02e1645dec234379709ddaebe607ea'


def run(args, data=None, required=True):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    if required and result.returncode:
        raise RuntimeError('Isolated audit command failed')
    return result


def audit():
    run(['docker', 'image', 'inspect', IMAGE])
    report = []
    for entry in json.loads(CATALOG.read_text())['backups']:
        path = ROOT / entry['path']
        if ROOT not in path.resolve().parents or path.resolve() != path or path.is_symlink():
            raise RuntimeError('Unsafe backup path')
        with os.fdopen(os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW), 'rb') as stream:
            before = os.fstat(stream.fileno())
            if before.st_size > 64 * 1024 * 1024: raise RuntimeError('Backup too large')
            data = stream.read(64 * 1024 * 1024 + 1)
        digest = hashlib.sha256(data).hexdigest()
        if digest != entry['sha256']: raise RuntimeError('Backup changed since catalog')
        cid = None
        row = dict(path=entry['path'], sha256=digest, tables=[], fullRestoreSucceeded=False)
        try:
            name = 'profile-backup-audit-' + uuid.uuid4().hex[:12]
            cid = run(['docker', 'run', '-d', '--name', name, '--label', 'smartassistant.real-backup-audit=true',
                       '--network', 'none', '--memory', '512m', '--tmpfs', '/var/lib/postgresql/data:rw,size=268435456',
                       '-e', 'PGDATA=/var/lib/postgresql/data', '-e', 'POSTGRES_USER=backup_auditor',
                       '-e', 'POSTGRES_PASSWORD=isolated-audit-only', '-e', 'POSTGRES_DB=profile_backup_audit', IMAGE]).stdout.decode().strip()
            meta = json.loads(run(['docker', 'inspect', cid]).stdout)[0]
            if meta['HostConfig']['NetworkMode'] != 'none' or meta['HostConfig'].get('Binds'):
                raise RuntimeError('Audit isolation mismatch')
            psql = ['docker', 'exec', '-i', cid, 'psql', '--no-psqlrc', '-qAt', '-v', 'ON_ERROR_STOP=1',
                    '-U', 'backup_auditor', '-d', 'profile_backup_audit']
            for unused in range(40):
                ready = run(psql + ['-h', '127.0.0.1', '-c', 'SELECT 1'], required=False)
                if ready.returncode == 0 and ready.stdout.strip() == b'1': break
                time.sleep(1)
            else: raise RuntimeError('Audit database unavailable')
            row['isolatedPrerequisites']={'ownerRoles':0,'tableSchemaOnly':False}
            if path.suffix == '.sql':
                # A plain pg_dump script can refer to owner roles not present in
                # an empty cluster. Create NOLOGIN roles only inside the fixture.
                # Never parse COPY rows as DDL or emit role identifiers.
                owners=set();in_copy=False
                for line in data.decode('utf-8',errors='strict').splitlines():
                    if in_copy:
                        if line==r'\.':in_copy=False
                        continue
                    if re.match(r'^COPY .* FROM stdin;$',line):in_copy=True;continue
                    match=re.match(r'^ALTER .+ OWNER TO ("(?:[^"\n]|"")+"|[a-zA-Z_][a-zA-Z0-9_$]*);$',line)
                    if match:owners.add(match.group(1))
                if len(owners)>32:raise RuntimeError('Too many prerequisite roles')
                for owner in sorted(owners):
                    run(psql,('CREATE ROLE '+owner+' NOLOGIN;').encode(),False)
                row['isolatedPrerequisites']['ownerRoles']=len(owners)
                # This explicitly known artifact is data-only, not a full DB
                # backup. Bootstrap only its table DDL (no production row data).
                if entry['path']=='conversation-suspend-20260902-153921/conversation_session_state.sql':
                    schema=run(['docker','exec','smart-postgres','sh','-c',
                        'pg_dump --schema-only --no-owner --no-privileges -t public.conversation_session_state -U "$POSTGRES_USER" -d "$POSTGRES_DB"']).stdout
                    run(psql,schema)
                    row['isolatedPrerequisites']['tableSchemaOnly']=True
            if path.suffix == '.dump':
                restored = run(['docker', 'exec', '-i', cid, 'pg_restore', '--no-owner', '--no-privileges',
                                '-U', 'backup_auditor', '-d', 'profile_backup_audit'], data, False)
            else:
                restored = run(psql, data, False)
            row['fullRestoreSucceeded'] = restored.returncode == 0
            # Classify only fixed, non-sensitive error categories; never echo a
            # diagnostic line (it may include a row, role, path or credential).
            error=restored.stderr.decode('utf-8',errors='replace').lower()
            row['restoreErrorCategories']=[label for label,needle in (
                ('PSQL_META_COMMAND_UNSUPPORTED','invalid command'),
                ('SERVER_SETTING_UNSUPPORTED','unrecognized configuration parameter'),
                ('MISSING_ROLE','role '),('MISSING_RELATION','does not exist'),
                ('SYNTAX_INCOMPATIBLE','syntax error')) if needle in error]
            # Never print restore stdout/stderr: it can contain real row values.
            names = run(psql, b"SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;").stdout.decode().splitlines()
            for table in names:
                if not re.fullmatch('[a-z_][a-z0-9_]*', table): raise RuntimeError('Unexpected table identity')
                if not any(word in table for word in ('profile', 'memory', 'routing', 'audit', 'vector')): continue
                columns = run(psql, ("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='%s';" % table).encode()).stdout.decode().splitlines()
                fields = ["'" + table + "'", 'count(*)']
                if 'user_id' in columns: fields += ['count(*) FILTER (WHERE user_id IS NULL)', 'count(DISTINCT user_id)']
                if 'llm_received_question' in columns: fields += ['count(*) FILTER (WHERE llm_received_question IS NOT NULL)']
                values = run(psql, ('BEGIN READ ONLY; SET LOCAL statement_timeout=\'5s\'; SELECT ' + ','.join(fields) + ' FROM public.' + table + '; ROLLBACK;').encode()).stdout.decode().strip().split('|')
                row['tables'].append(dict(table=table, counts=[int(v) for v in values[1:]],
                                          hasUserId='user_id' in columns, hasPrompt='llm_received_question' in columns))
            row['status'] = 'OWNERSHIP_COUNTS_ONLY' if row['fullRestoreSucceeded'] else 'PARTIAL_RESTORE_NOT_CERTIFIED'
        finally:
            if cid:
                meta = json.loads(run(['docker', 'inspect', cid]).stdout)[0]
                if meta['Config']['Labels'].get('smartassistant.real-backup-audit') != 'true' or meta['Id'] != cid:
                    raise RuntimeError('Refusing to remove unowned audit container')
                run(['docker', 'rm', '-f', cid])
            row['temporaryContainerRemoved'] = cid is not None
        after = path.stat()
        if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
            raise RuntimeError('Original backup changed during audit')
        report.append(row)
    return dict(backups=report, originalsModified=False, rowContentsReported=False, productionRestoreCertified=False)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-real-backup-audit', action='store_true', required=True)
    parser.parse_args()
    print(json.dumps(audit(), indent=2))
