"""Drain and deploy per-session concurrency on the known single-host layout.

Run on production only after uploading the exact candidate and creating a
read-only release_artifacts.py snapshot. Retain the old container for rollback.
The database migration is forward-only: never restore the account-wide index
after new independent sessions may have been created.
"""

import json
import pathlib
import sys

import deploy_assessment_20260925 as release


release.ROOT = pathlib.Path('/opt/smart-assistant/releases/independent-conversations-20260926')
release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.TAG = 'independent-conversations-20260926'
release.TARGETS = {
    'consumer': ('smart-assistant-consumer-1.0.0-SNAPSHOT.jar',
                 '720f5da1b00687b40f3a7474fece42f518cf05b38146964cbe974bcc4201b623', 8082),
}
FRONTEND = pathlib.Path('/opt/smart-assistant/frontend/dist')
MIGRATION = release.ROOT / '20260926_allow_independent_conversations.sql'


def database(sql):
    return release.run('docker', 'exec', '-i', 'smart-postgres', 'sh', '-c',
                       'exec psql --no-psqlrc -qAt -v ON_ERROR_STOP=1 '
                       '-U "$POSTGRES_USER" -d "$POSTGRES_DB"', input=sql.encode('utf-8'))


def migration():
    expected = 'a64d4ce99881247149a7c29cbea8995f7bbeb85178c99ac79d04b9e3de51fb8d'
    release.require(release.sha256(MIGRATION) == expected, 'Migration hash mismatch')
    release.require(database("SELECT to_regclass('public.conversation_session_state') IS NOT NULL;") == 't',
                    'Session table missing')
    return MIGRATION.read_text(encoding='utf-8')


def main(mode):
    release.require(mode in ('preflight', 'deploy'), 'Expected preflight or deploy')
    release.require(not (release.ROOT / 'deployment.json').exists(), 'Release already deployed')
    baseline = json.loads(release.SNAPSHOT.read_text(encoding='utf-8'))
    release.require({row['name'] for row in baseline['services']} == {'smart-consumer'},
                    'Snapshot service set drift')
    name, expected, _ = release.TARGETS['consumer']
    release.require(release.sha256(release.ROOT / name) == expected, 'Candidate hash mismatch')
    release.require((release.ROOT / 'dist/index.html').is_file(), 'Frontend candidate missing')
    sql = migration()
    old = release.inspect('smart-consumer')
    release.validate_live('consumer', old, baseline['services'][0])
    release.require(release.inspect('smart-gateway')['State']['Running'], 'Gateway not running')
    probe = release.clone('consumer', old, 'smart-consumer-independent-preflight')
    try:
        release.equivalent('consumer', old, release.inspect(probe))
    finally:
        release.run('docker', 'rm', probe)
    print('PREFLIGHT_OK', flush=True)
    if mode == 'preflight':
        return

    old_id = old['Id']
    gateway_stopped = False
    renamed = False
    created = None
    old_index = FRONTEND / 'index.html'
    index_backup = release.ROOT / 'index-before.html'
    release.require(not index_backup.exists(), 'Frontend rollback copy already exists')
    index_backup.write_bytes(old_index.read_bytes())
    try:
        release.run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
        gateway_stopped = True
        release.drain()
        # One transaction: an unsuccessful migration cannot leave half its DDL applied.
        database('BEGIN; SET LOCAL lock_timeout = \'3s\'; SET LOCAL statement_timeout = \'30s\';\n'
                 + sql + '\nCOMMIT;')
        release.require(database("SELECT to_regclass('public.uk_conversation_one_active_per_user') IS NULL;") == 't',
                        'Account-wide unique index remains')

        release.run('docker', 'stop', '--time', '30', 'smart-consumer', timeout=90)
        release.run('docker', 'rename', old_id, 'smart-consumer-before-' + release.TAG)
        renamed = True
        release.run('docker', 'network', 'disconnect', release.NETWORK, old_id)
        created = release.clone('consumer', old, 'smart-consumer')
        release.equivalent('consumer', old, release.inspect(created))
        release.run('docker', 'start', 'smart-consumer', timeout=90)
        release.health('consumer')
        release.require(release.run('docker', 'exec', 'smart-consumer', 'sha256sum', '/app/app.jar').split()[0]
                        == expected, 'Running artifact hash mismatch')
        release.run('docker', 'start', 'smart-gateway', timeout=90)
        gateway_stopped = False
        release.public_health()

        # Hashed assets can coexist; change the small HTML entrypoint last.
        candidate = release.ROOT / 'dist'
        for asset in (candidate / 'assets').iterdir():
            target = FRONTEND / 'assets' / asset.name
            if asset.is_file() and not target.exists():
                target.write_bytes(asset.read_bytes())
        index_tmp = FRONTEND / 'index.independent-conversations.tmp'
        index_tmp.write_bytes((candidate / 'index.html').read_bytes())
        index_tmp.replace(old_index)
        release.public_health()
        (release.ROOT / 'deployment.json').write_text(json.dumps({
            'deployed': True, 'candidateSha256': expected, 'previousId': old_id,
        }, indent=2), encoding='utf-8')
        print('DEPLOYED', flush=True)
    except Exception:
        print('ROLLING_BACK', flush=True)
        if not gateway_stopped:
            release.run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
            gateway_stopped = True
        old_index.write_bytes(index_backup.read_bytes())
        if created:
            release.run('docker', 'rm', '-f', created)
        if renamed:
            release.run('docker', 'rename', old_id, 'smart-consumer')
            if release.NETWORK not in release.inspect(old_id)['NetworkSettings']['Networks']:
                ip = old['NetworkSettings']['Networks'][release.NETWORK]['IPAddress']
                release.run('docker', 'network', 'connect', '--ip', ip,
                            '--alias', 'smart-consumer', release.NETWORK, old_id)
            release.run('docker', 'start', 'smart-consumer', timeout=90)
            release.health('consumer')
        if gateway_stopped:
            release.run('docker', 'start', 'smart-gateway', timeout=90)
        print('ROLLBACK_DONE', flush=True)
        raise


if __name__ == '__main__':
    try:
        main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
