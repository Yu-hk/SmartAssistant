"""Release one Product JAR on the current single-host Compose layout.

Requires a release_artifacts.py snapshot and a hash-verified candidate in ROOT.
Retains the previous container and its immutable JAR mount for rollback.
"""

import json
import pathlib
import sys

import deploy_assessment_20260925 as release


release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-rag-scope-weight-20260929')
release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.TAG = 'product-rag-scope-weight-20260929'
release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                '6221dd176317826882e81d0e74918712ec564c6175a546ad5a18f9e857a07f52',
                8084),
}


def validate_live(info, baseline):
    release.require(info['State']['Running'], 'Product service not running')
    release.require(set(info['NetworkSettings']['Networks']) == {release.NETWORK},
                    'Unexpected product network')
    release.require(info['Image'] == baseline['image'].replace('sha256:', '', 1),
                    'Image differs from snapshot')
    old_jar = release.jar_mount(info)
    release.require(old_jar == baseline['artifact']
                    and release.sha256(old_jar) == baseline['sha256'],
                    'Host JAR differs from snapshot')
    release.require(release.run('docker', 'exec', 'smart-product', 'sha256sum',
                                '/app/app.jar').split()[0] == baseline['sha256'],
                    'Running JAR differs from snapshot')
    host = info['HostConfig']
    release.require(not any(host.get(key) for key in (
        'PortBindings', 'Privileged', 'ExtraHosts', 'CapAdd', 'CapDrop',
        'SecurityOpt', 'Devices', 'VolumesFrom')),
        'Unsupported product container settings')
    release.require(not info['Config'].get('User') and not info['Config'].get('Healthcheck'),
                    'Unsupported container user or healthcheck')


def clone(source, name):
    config, host = source['Config'], source['HostConfig']
    entry = config['Entrypoint']
    entry = entry[0] if isinstance(entry, list) and len(entry) == 1 else entry
    release.require(isinstance(entry, str), 'Unsupported entrypoint')
    args = ['docker', 'create', '--name', name, '--network', release.NETWORK,
            '--network-alias', name, '--restart', host['RestartPolicy']['Name'],
            '--entrypoint', entry, '--workdir', config.get('WorkingDir') or '/',
            '--label', 'smartassistant.release=' + release.TAG]
    for dns in host.get('Dns') or []:
        args += ['--dns', dns]
    for mount in source['Mounts']:
        path = (str(release.ROOT / release.TARGETS['product'][0])
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
    release.require(set(services) == {'smart-product'}, 'Snapshot service set drift')
    service = 'product'
    name = 'smart-product'
    filename, expected_hash, _ = release.TARGETS[service]
    release.require(release.sha256(release.ROOT / filename) == expected_hash,
                    'Candidate hash mismatch')
    old = release.inspect(name)
    validate_live(old, services[name])
    release.require(release.inspect('smart-gateway')['State']['Running'],
                    'Gateway not running')

    preflight_name = name + '-rag-preflight'
    preflight_id = clone(old, preflight_name)
    try:
        release.equivalent(service, old, release.inspect(preflight_id))
    finally:
        release.run('docker', 'rm', preflight_id)
    print('PREFLIGHT_OK', flush=True)
    if mode == 'preflight':
        return

    gateway_stopped = False
    product_stopped = False
    renamed = False
    created_id = None
    try:
        release.run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
        gateway_stopped = True
        release.drain()
        release.run('docker', 'stop', '--time', '30', name, timeout=90)
        product_stopped = True
        release.run('docker', 'rename', old['Id'], name + '-before-' + release.TAG)
        renamed = True
        release.run('docker', 'network', 'disconnect', release.NETWORK, old['Id'])
        created_id = clone(old, name)
        release.equivalent(service, old, release.inspect(created_id))
        release.run('docker', 'start', name, timeout=90)
        release.health(service)
        release.require(release.run('docker', 'exec', name, 'sha256sum', '/app/app.jar').split()[0]
                        == expected_hash, 'Running artifact hash mismatch')
        release.run('docker', 'start', 'smart-gateway', timeout=90)
        gateway_stopped = False
        release.public_health()
        (release.ROOT / 'deployment.json').write_text(json.dumps({
            'deployed': True,
            'candidateSha256': expected_hash,
            'previousId': old['Id'],
        }, indent=2))
        print('DEPLOYED', flush=True)
    except Exception:
        print('ROLLING_BACK', flush=True)
        if not gateway_stopped:
            release.run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
            gateway_stopped = True
        if created_id:
            release.run('docker', 'rm', '-f', created_id)
        if renamed:
            release.run('docker', 'rename', old['Id'], name)
            if release.NETWORK not in release.inspect(old['Id'])['NetworkSettings']['Networks']:
                ip = old['NetworkSettings']['Networks'][release.NETWORK]['IPAddress']
                release.run('docker', 'network', 'connect', '--ip', ip, '--alias', name,
                            release.NETWORK, old['Id'])
            release.run('docker', 'start', name, timeout=90)
            release.health(service)
        elif product_stopped:
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
