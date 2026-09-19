"""Read-only, metadata-only inventory. Does NOT certify erasure or inspect payloads.

Run on the deployment host; stdout contains counts, paths and container identities only.
No deletion, queue consumption, Redis GET/HGETALL or backup content reads are allowed here.
Python 3.6+; configured credentials are used only in memory/stdin, never reported.
"""
import collections
import json
import os
import pathlib
import subprocess

PREFIXES = ('user:profile:', 'user_profile:', 'user:memory:', 'answer:', 'personalization:hit_count:', 'vector_search:', 'routing:user-profile-context:', 'routing:user-profile-candidate:',
            'routing:user-profile-owner:', 'routing:user-profile-index:',
            'routing:user-profile-lifecycle:', 'routing:user-profile-done:',
            'router:product-node:v2:', 'consumer:semantic-answer:', 'routing:execution-graph:',
            'routing:sse:events:', 'routing:sse:stream:')
TABLES = ('user_profile', 'user_profile_snapshot', 'user_profile_change_log',
          'user_profile_entity_fact', 'profile_agent_memory', 'profile_commit_candidate',
          'profile_lifecycle', 'profile_request_admission', 'profile_cleanup_job',
          'profile_cleanup_receipt', 'routing_call_log', 'workflow_execution')

def run(args, data=None):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    if result.returncode:
        raise RuntimeError('Inventory command failed: ' + args[0])
    return result.stdout.decode('utf-8')

def inventory():
    report = {'scope': 'metadata-only', 'erasureComplete': False}
    names = run(['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines()
    containers = []
    for name in names:
        if not name.startswith(('smart-consumer', 'smart-router', 'smart-product', 'smart-order')):
            continue
        meta = json.loads(run(['docker', 'inspect', name]))[0]
        env = dict(item.split('=', 1) for item in meta['Config'].get('Env', []) if '=' in item)
        base = env.get('APP_DATA_DIR') or 'data/users'
        if not base.startswith('/'):
            base = (meta['Config'].get('WorkingDir') or '/') + '/' + base
        row = {'name': name, 'id': meta['Id'], 'running': meta['State']['Running'],
               'configuredOrDefaultMemoryPath': base, 'runtimeConfigOverrideVerified': False,
               'mounts': [{'source': m['Source'], 'destination': m['Destination'], 'rw': m['RW']}
                          for m in meta.get('Mounts', [])]}
        # docker diff lists paths/metadata only, including stopped writable layers (not volumes).
        paths = run(['docker', 'diff', name]).splitlines()
        row['writableLayerMemoryEntries'] = sum('-memory.md' in p for p in paths)
        if row['running']:
            output = run(['docker', 'exec', name, 'sh', '-c',
                          'if [ -d "$1" ]; then find "$1" -type f -name "*-memory.md" | wc -l; '
                          'else printf "absent"; fi', 'sh', base]).strip()
            row['defaultMemoryFiles'] = int(output) if output.isdigit() else output
        else:
            row['defaultMemoryFiles'] = 'NOT_INSPECTED_STOPPED_CONTAINER'
        containers.append(row)
    report['containers'] = containers
    sql = "BEGIN READ ONLY; SET LOCAL statement_timeout='5s';\n"
    for table in TABLES:
        # psql conditional avoids failing on tables absent in this schema version.
        sql += "SELECT to_regclass('public.%s') IS NOT NULL AS present \\gset\n\\if :present\n" % table
        sql += "SELECT '%s',count(*) FROM public.%s;\n\\endif\n" % (table, table)
    sql += 'ROLLBACK;'
    counts = run(['docker', 'exec', '-i', 'smart-postgres', 'sh', '-c',
                  'psql --no-psqlrc -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], sql.encode())
    report['postgresRows'] = {line.split('|')[0]: int(line.split('|')[1])
                              for line in counts.splitlines() if '|' in line}
    meta = json.loads(run(['docker', 'inspect', 'smart-consumer']))[0]
    env = dict(item.split('=', 1) for item in meta['Config']['Env'] if '=' in item)
    password = env.get('REDIS_PASSWORD') or env.get('SPRING_DATA_REDIS_PASSWORD')
    if not password:
        raise RuntimeError('Configured Redis authentication unavailable')
    def redis(*args):
        value = run(['docker', 'exec', '-i', 'smart-redis', 'sh', '-c',
                     'read -r REDISCLI_AUTH; export REDISCLI_AUTH; exec redis-cli --raw "$@"', 'sh']
                    + list(args), (password + '\n').encode()).strip()
        if value.startswith(('ERR', 'NOAUTH', 'WRONGPASS')):
            raise RuntimeError('Redis metadata query failed')
        return value
    redis_counts = {}
    for prefix in PREFIXES:
        cursor = '0'; keys = set(); complete = False
        for unused in range(1000):
            parts = redis('SCAN', cursor, 'MATCH', prefix + '*', 'COUNT', '200').splitlines()
            cursor = parts[0]; keys.update(parts[1:])
            if cursor == '0': complete = True; break
        redis_counts[prefix] = {'observedKeys': len(keys), 'scanComplete': complete}
        if prefix == 'routing:execution-graph:':
            # Metadata only: prove expiry bounds without fetching legacy diagnostic bodies.
            ttls = [int(redis('PTTL', key)) for key in keys]
            redis_counts[prefix]['retention'] = {
                'withoutExpiry': sum(ttl == -1 for ttl in ttls),
                'missingDuringScan': sum(ttl == -2 for ttl in ttls),
                'over24Hours': sum(ttl > 86400000 for ttl in ttls),
                'maxRemainingMs': max(ttls, default=0)}
    report['redis'] = redis_counts
    report['queueMetadata'] = run(['docker', 'exec', 'smart-rabbitmq', 'rabbitmqctl', '-q',
                                  'list_queues', 'name', 'messages_ready', 'messages_unacknowledged']).splitlines()
    root = pathlib.Path('/opt/smart-assistant/backups')
    counts = collections.Counter(); total = 0; files = 0
    if root.is_dir():
        for directory, dirs, names in os.walk(str(root), followlinks=False):
            dirs[:] = [d for d in dirs if not pathlib.Path(directory, d).is_symlink()]
            for name in names:
                path = pathlib.Path(directory, name)
                if path.is_symlink() or not path.is_file(): continue
                files += 1; total += path.stat().st_size
                counts[path.suffix.lower() or '<none>'] += 1
    report['backups'] = {'root': str(root), 'files': files, 'bytes': total,
                         'extensions': dict(counts), 'contentsInspected': False}
    report['unverified'] = ['runtime configuration overrides', 'external volumes and unregistered Redis prefixes',
                            'prompt/execution payload provenance', 'backup contents and external backup instances',
                            'stopped-container volumes', 'MQ payload ownership (not consumed)']
    return report

if __name__ == '__main__':
    print(json.dumps(inventory(), ensure_ascii=False, indent=2))
