"""One-off three-service release; preserves old containers for rollback.

Run only on the current single-host production layout after a read-only
release_artifacts.py snapshot. Never logs container environment values.
"""

import hashlib
import json
import pathlib
import subprocess
import sys
import time
import urllib.request


ROOT = pathlib.Path('/opt/smart-assistant/releases/assessment-20260925')
SNAPSHOT = ROOT / 'before-artifacts.json'
TAG = 'assessment-20260925'
TARGETS = {
    'consumer': ('smart-assistant-consumer-1.0.0-SNAPSHOT.jar',
                 '5017b6270da35c0fa84418a34e5e585fb346a74eafdb0f761eb3825750874d33', 8082),
    'router': ('smart-assistant-router-1.0.0-SNAPSHOT.jar',
               '83f02ed6fd05d49211f57769e94dd5a0af16c08edff1d9cd31d4690a01c459e3', 8083),
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                '1687109c601174963b0ef542eb64485f43d271584f7f66dcf1ec6605fd516faf', 8084),
}
NETWORK = 'smart-network'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(*args, input=None, timeout=120):
    result = subprocess.run(args, input=input, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout)
    require(result.returncode == 0, 'Command failed: ' + ' '.join(args[:2]))
    return result.stdout.decode('utf-8').strip()


def inspect(name):
    return json.loads(run('docker', 'inspect', name))[0]


def sha256(path):
    path = pathlib.Path(path)
    require(path.is_file() and not path.is_symlink(), 'Missing regular artifact')
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def jar_mount(info):
    mounts = [m for m in info['Mounts'] if m['Destination'] == '/app/app.jar']
    require(len(mounts) == 1 and mounts[0]['Type'] == 'bind' and not mounts[0]['RW'],
            'Expected exactly one read-only JAR bind mount')
    return mounts[0]['Source']


def validate_live(service, info, baseline):
    name = 'smart-' + service
    require(info['State']['Running'], 'Service not running: ' + name)
    require(set(info['NetworkSettings']['Networks']) == {NETWORK}, 'Unexpected network: ' + name)
    require(info['Image'] == baseline['image'].replace('sha256:', '', 1),
            'Image differs from release snapshot: ' + name)
    path = jar_mount(info)
    require(path == baseline['artifact'] and sha256(path) == baseline['sha256'],
            'Host artifact differs from release snapshot: ' + name)
    require(run('docker', 'exec', name, 'sha256sum', '/app/app.jar').split()[0]
            == baseline['sha256'], 'Container artifact differs from release snapshot: ' + name)
    host = info['HostConfig']
    require(not any(host.get(k) for k in ('PortBindings', 'Privileged', 'ExtraHosts',
            'CapAdd', 'CapDrop', 'SecurityOpt', 'Devices', 'VolumesFrom')),
            'Unsupported container settings: ' + name)
    require(host.get('Dns') == ['10.89.1.1'], 'Unexpected DNS: ' + name)
    require(not info['Config'].get('User') and not info['Config'].get('Healthcheck'),
            'Unsupported container user or healthcheck: ' + name)
    if service == 'consumer':
        require(any(m['Destination'] == '/profile-control'
                    and m['Source'] == '/opt/smart-assistant-control' and m['RW']
                    for m in info['Mounts']), 'Consumer control mount differs')


def clone(service, source, name):
    config, host = source['Config'], source['HostConfig']
    entry = config['Entrypoint']
    entry = entry[0] if isinstance(entry, list) and len(entry) == 1 else entry
    require(isinstance(entry, str), 'Unsupported entrypoint')
    args = ['docker', 'create', '--name', name, '--network', NETWORK,
            '--network-alias', name, '--dns', '10.89.1.1', '--restart',
            host['RestartPolicy']['Name'], '--entrypoint', entry,
            '--workdir', config.get('WorkingDir') or '/',
            '--label', 'smartassistant.assessment-release=20260925']
    for mount in source['Mounts']:
        path = str(ROOT / TARGETS[service][0]) if mount['Destination'] == '/app/app.jar' else mount['Source']
        args += ['--mount', 'type=bind,source=' + path + ',target=' + mount['Destination']
                 + ('' if mount['RW'] else ',readonly')]
    for flag, key in (('--memory', 'Memory'), ('--memory-swap', 'MemorySwap')):
        if host.get(key):
            args += [flag, str(host[key])]
    if host.get('LogConfig', {}).get('Type'):
        args += ['--log-driver', host['LogConfig']['Type']]
    for key, value in (host['LogConfig'].get('Config') or {}).items():
        args += ['--log-opt', key + '=' + value]
    for key, value in (config.get('Labels') or {}).items():
        args += ['--label', key + '=' + value]
    for value in config.get('Env') or []:
        args += ['--env', value]
    return run(*args, source['Image'], *(config.get('Cmd') or []))


def equivalent(service, old, new):
    require(old['Image'] == new['Image'], 'Image drift: ' + service)
    for key in ('Cmd', 'Entrypoint', 'WorkingDir', 'User', 'Healthcheck'):
        require(old['Config'].get(key) == new['Config'].get(key), 'Config drift: ' + service + '/' + key)
    require(sorted(old['Config']['Env']) == sorted(new['Config']['Env']), 'Environment drift: ' + service)
    for key in ('Memory', 'MemorySwap', 'RestartPolicy', 'Dns', 'PortBindings', 'Privileged'):
        require(old['HostConfig'].get(key) == new['HostConfig'].get(key),
                'Host config drift: ' + service + '/' + key)
    for key in ('Type', 'Config'):
        require(old['HostConfig']['LogConfig'].get(key) == new['HostConfig']['LogConfig'].get(key),
                'Log config drift: ' + service + '/' + key)
    old_mounts = sorted((str(ROOT / TARGETS[service][0]) if m['Destination'] == '/app/app.jar'
                         else m['Source'], m['Destination'], m['RW']) for m in old['Mounts'])
    new_mounts = sorted((m['Source'], m['Destination'], m['RW']) for m in new['Mounts'])
    require(old_mounts == new_mounts, 'Mount drift: ' + service)


def health(service):
    name = 'smart-' + service
    for _ in range(90):
        try:
            info = inspect(name)
            ip = info['NetworkSettings']['Networks'][NETWORK]['IPAddress']
            with urllib.request.urlopen('http://' + ip + ':' + str(TARGETS[service][2])
                                        + '/actuator/health', timeout=3) as response:
                if json.load(response)['status'] == 'UP':
                    print('HEALTHY ' + name, flush=True)
                    return
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(2)
    raise RuntimeError('Health timeout: ' + name)


def public_health():
    # The gateway process may be started before its HTTP listener is ready;
    # Nginx can briefly return 502 during that bounded startup window.
    for _ in range(30):
        try:
            with urllib.request.urlopen('https://xiaoyuai.cloud/healthz', timeout=5) as response:
                if json.load(response)['status'] == 'UP':
                    print('PUBLIC_HEALTHY', flush=True)
                    return
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(2)
    raise RuntimeError('Public health timeout')


def drain():
    for _ in range(45):
        rows = run('docker', 'exec', 'smart-rabbitmq', 'rabbitmqctl', '-q',
                   'list_queues', 'name', 'messages_ready', 'messages_unacknowledged').splitlines()
        queues = {}
        for row in rows:
            parts = row.split()
            if len(parts) == 3:
                queues[parts[0]] = parts[1:]
        wanted = [name for name in queues if name.startswith('smart.chat.dispatch.v1.quorum')
                  or name == 'smartassistant.user-profile.commit']
        running = run('docker', 'exec', '-i', 'smart-postgres', 'sh', '-c',
                      'exec psql --no-psqlrc -qAt -v ON_ERROR_STOP=1 '
                      '-U "$POSTGRES_USER" -d "$POSTGRES_DB"',
                      input=b"SELECT count(*) FROM conversation_session_state WHERE status='ACTIVE_RUNNING';")
        if len(wanted) >= 2 and all(queues[name] == ['0', '0'] for name in wanted) and running == '0':
            print('DRAINED', flush=True)
            return
        time.sleep(2)
    raise RuntimeError('Live requests did not drain')


def main(mode):
    require(mode in ('preflight', 'deploy'), 'Expected preflight or deploy')
    require(not (ROOT / 'deployment.json').exists(), 'Release already deployed')
    baseline = json.loads(SNAPSHOT.read_text())
    services = {row['name']: row for row in baseline['services']}
    require(set(services) == {'smart-' + service for service in TARGETS}, 'Snapshot service set drift')
    old = {}
    for service, (filename, expected, _) in TARGETS.items():
        require(sha256(ROOT / filename) == expected, 'Candidate hash mismatch: ' + service)
        old[service] = inspect('smart-' + service)
        validate_live(service, old[service], services['smart-' + service])
    require(inspect('smart-gateway')['State']['Running'], 'Gateway not running')
    for service in TARGETS:
        name = 'smart-' + service + '-assessment-preflight'
        cid = clone(service, old[service], name)
        try:
            equivalent(service, old[service], inspect(cid))
        finally:
            run('docker', 'rm', cid)
    print('PREFLIGHT_OK', flush=True)
    if mode == 'preflight':
        return

    created, renamed = [], []
    gateway_stopped = False
    try:
        run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
        gateway_stopped = True
        drain()
        for service in ('consumer', 'router', 'product'):
            old_id = old[service]['Id']
            run('docker', 'stop', '--time', '30', 'smart-' + service, timeout=90)
            run('docker', 'rename', old_id, 'smart-' + service + '-before-' + TAG)
            renamed.append(service)
            run('docker', 'network', 'disconnect', NETWORK, old_id)
            cid = clone(service, old[service], 'smart-' + service)
            created.append((service, cid))
            equivalent(service, old[service], inspect(cid))
        for service in ('product', 'router', 'consumer'):
            run('docker', 'start', 'smart-' + service, timeout=90)
            health(service)
            require(run('docker', 'exec', 'smart-' + service, 'sha256sum', '/app/app.jar').split()[0]
                    == TARGETS[service][1], 'Running artifact hash mismatch: ' + service)
        run('docker', 'start', 'smart-gateway', timeout=90)
        gateway_stopped = False
        public_health()
        (ROOT / 'deployment.json').write_text(json.dumps({'deployed': True,
            'candidateSha256': {service: values[1] for service, values in TARGETS.items()},
            'previousIds': {service: old[service]['Id'] for service in TARGETS}}, indent=2))
        print('DEPLOYED', flush=True)
    except Exception:
        print('ROLLING_BACK', flush=True)
        if not gateway_stopped:
            run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
            gateway_stopped = True
        for service, cid in reversed(created):
            run('docker', 'rm', '-f', cid)
        for service in reversed(renamed):
            old_id = old[service]['Id']
            run('docker', 'rename', old_id, 'smart-' + service)
            if NETWORK not in inspect(old_id)['NetworkSettings']['Networks']:
                ip = old[service]['NetworkSettings']['Networks'][NETWORK]['IPAddress']
                run('docker', 'network', 'connect', '--ip', ip, '--alias', 'smart-' + service,
                    NETWORK, old_id)
            run('docker', 'start', 'smart-' + service, timeout=90)
            health(service)
        if gateway_stopped:
            run('docker', 'start', 'smart-gateway', timeout=90)
        print('ROLLBACK_DONE', flush=True)
        raise


if __name__ == '__main__':
    try:
        main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        # Do not expose Docker stderr or container environment on failure.
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
