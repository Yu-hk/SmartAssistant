"""Hash-checked two-service release; preserves the former containers for rollback.

Run on the current single-host deployment only after a release_artifacts snapshot,
an additive database migration, and separate frontend backup.
"""

import json
import pathlib
import sys

import deploy_assessment_20260925 as release


release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-field-routing-20260929')
release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.TAG = 'product-field-routing-20260929'
release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                '3b8cc23c1109e603a2e5b549df1803805ede47a0546d2b6c35f882d3a3378447', 8084),
    'data-intake': ('smart-assistant-data-intake-1.0.0-SNAPSHOT.jar',
                    'a8411312e3e21d1cf5f04335cf5ec3c83a5a7e09b44eb03ba6c0c67da696ea77', 8092),
}


def validate_live(service, info, baseline):
    name = 'smart-' + service
    release.require(info['State']['Running'], 'Service not running: ' + name)
    release.require(set(info['NetworkSettings']['Networks']) == {release.NETWORK},
                    'Unexpected network: ' + name)
    release.require(info['Image'] == baseline['image'].replace('sha256:', '', 1),
                    'Image differs from snapshot: ' + name)
    mount = release.jar_mount(info)
    release.require(mount == baseline['artifact'] and release.sha256(mount) == baseline['sha256'],
                    'Host artifact differs from snapshot: ' + name)
    release.require(release.run('docker', 'exec', name, 'sha256sum', '/app/app.jar').split()[0]
                    == baseline['sha256'], 'Running artifact differs from snapshot: ' + name)
    host = info['HostConfig']
    release.require(not any(host.get(key) for key in (
        'PortBindings', 'Privileged', 'ExtraHosts', 'CapAdd', 'CapDrop',
        'SecurityOpt', 'Devices', 'VolumesFrom')),
        'Unsupported container settings: ' + name)
    release.require(not info['Config'].get('User') and not info['Config'].get('Healthcheck'),
                    'Unsupported container identity or healthcheck: ' + name)


def clone(service, source, name):
    config, host = source['Config'], source['HostConfig']
    entry = config['Entrypoint']
    entry = entry[0] if isinstance(entry, list) and len(entry) == 1 else entry
    release.require(isinstance(entry, str), 'Unsupported entrypoint: ' + service)
    args = ['docker', 'create', '--name', name, '--network', release.NETWORK,
            '--network-alias', name, '--restart', host['RestartPolicy']['Name'],
            '--entrypoint', entry, '--workdir', config.get('WorkingDir') or '/',
            '--label', 'smartassistant.release=' + release.TAG]
    for dns in host.get('Dns') or []:
        args += ['--dns', dns]
    for mount in source['Mounts']:
        path = (str(release.ROOT / release.TARGETS[service][0])
                if mount['Destination'] == '/app/app.jar' else mount['Source'])
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
    return release.run(*args, source['Image'], *(config.get('Cmd') or []))


def main(mode):
    release.require(mode in ('preflight', 'deploy'), 'Expected preflight or deploy')
    release.require(not (release.ROOT / 'deployment.json').exists(), 'Release already deployed')
    baseline = json.loads(release.SNAPSHOT.read_text())
    services = {row['name']: row for row in baseline['services']}
    release.require(set(services) == {'smart-' + service for service in release.TARGETS},
                    'Snapshot service set drift')
    old = {}
    for service, (filename, expected, _) in release.TARGETS.items():
        release.require(release.sha256(release.ROOT / filename) == expected,
                        'Candidate hash mismatch: ' + service)
        old[service] = release.inspect('smart-' + service)
        validate_live(service, old[service], services['smart-' + service])
    release.require(release.inspect('smart-gateway')['State']['Running'], 'Gateway not running')
    for service in release.TARGETS:
        name = 'smart-' + service + '-field-preflight'
        cid = clone(service, old[service], name)
        try:
            release.equivalent(service, old[service], release.inspect(cid))
        finally:
            release.run('docker', 'rm', cid)
    print('PREFLIGHT_OK', flush=True)
    if mode == 'preflight':
        return

    created, renamed = [], []
    gateway_stopped = False
    try:
        release.run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
        gateway_stopped = True
        release.drain()
        for service in release.TARGETS:
            name = 'smart-' + service
            old_id = old[service]['Id']
            release.run('docker', 'stop', '--time', '30', name, timeout=90)
            release.run('docker', 'rename', old_id, name + '-before-' + release.TAG)
            renamed.append(service)
            release.run('docker', 'network', 'disconnect', release.NETWORK, old_id)
            cid = clone(service, old[service], name)
            created.append((service, cid))
            release.equivalent(service, old[service], release.inspect(cid))
        for service in release.TARGETS:
            name = 'smart-' + service
            release.run('docker', 'start', name, timeout=90)
            release.health(service)
            release.require(release.run('docker', 'exec', name, 'sha256sum', '/app/app.jar').split()[0]
                            == release.TARGETS[service][1], 'Running artifact hash mismatch: ' + service)
        release.run('docker', 'start', 'smart-gateway', timeout=90)
        gateway_stopped = False
        release.public_health()
        (release.ROOT / 'deployment.json').write_text(json.dumps({
            'deployed': True,
            'candidateSha256': {service: value[1] for service, value in release.TARGETS.items()},
            'previousIds': {service: old[service]['Id'] for service in release.TARGETS},
        }, indent=2))
        print('DEPLOYED', flush=True)
    except Exception:
        print('ROLLING_BACK', flush=True)
        if not gateway_stopped:
            release.run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
            gateway_stopped = True
        for _, cid in reversed(created):
            release.run('docker', 'rm', '-f', cid)
        for service in reversed(renamed):
            old_id = old[service]['Id']
            name = 'smart-' + service
            release.run('docker', 'rename', old_id, name)
            if release.NETWORK not in release.inspect(old_id)['NetworkSettings']['Networks']:
                ip = old[service]['NetworkSettings']['Networks'][release.NETWORK]['IPAddress']
                release.run('docker', 'network', 'connect', '--ip', ip, '--alias', name,
                            release.NETWORK, old_id)
            release.run('docker', 'start', name, timeout=90)
            release.health(service)
        if gateway_stopped:
            release.run('docker', 'start', 'smart-gateway', timeout=90)
        release.public_health()
        print('ROLLBACK_DONE', flush=True)
        raise


if __name__ == '__main__':
    try:
        main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
