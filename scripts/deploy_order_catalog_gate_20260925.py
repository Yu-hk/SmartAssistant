"""Roll out the catalog/order clarification fix, retaining exact prior containers.

Run on the current production host only after a release_artifacts.py snapshot.
All Docker environment values are preserved and compared, never printed.
"""

import json
import pathlib
import sys

import deploy_assessment_20260925 as release


release.ROOT = pathlib.Path('/opt/smart-assistant/releases/order-catalog-gate-20260925')
release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.TAG = 'order-catalog-gate-20260925'
release.TARGETS = {
    'consumer': ('smart-assistant-consumer-1.0.0-SNAPSHOT.jar',
                 'eaad8a4a6ab31c759c0c87170c4f5c43a30e34c91007fd7d7716b7fa3a1646ff', 8082),
    'router': ('smart-assistant-router-1.0.0-SNAPSHOT.jar',
               'd7a5dd50641bfbc6d376ccb13a27a59e8db166ba7812f129291f30de62478e20', 8083),
    'order': ('smart-assistant-order-1.0.0-SNAPSHOT.jar',
              '13ddd96c1b601ea24c252098606080606af0f65f25c4999ffd277b9610cd6438', 8085),
}


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
        release.validate_live(service, old[service], services['smart-' + service])
    release.require(release.inspect('smart-gateway')['State']['Running'], 'Gateway not running')
    for service in release.TARGETS:
        name = 'smart-' + service + '-catalog-preflight'
        cid = release.clone(service, old[service], name)
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
        for service in ('consumer', 'router', 'order'):
            old_id = old[service]['Id']
            release.run('docker', 'stop', '--time', '30', 'smart-' + service, timeout=90)
            release.run('docker', 'rename', old_id, 'smart-' + service + '-before-' + release.TAG)
            renamed.append(service)
            release.run('docker', 'network', 'disconnect', release.NETWORK, old_id)
            cid = release.clone(service, old[service], 'smart-' + service)
            created.append((service, cid))
            release.equivalent(service, old[service], release.inspect(cid))
        for service in ('order', 'router', 'consumer'):
            release.run('docker', 'start', 'smart-' + service, timeout=90)
            release.health(service)
            release.require(release.run('docker', 'exec', 'smart-' + service,
                                        'sha256sum', '/app/app.jar').split()[0]
                            == release.TARGETS[service][1], 'Running hash mismatch: ' + service)
        release.run('docker', 'start', 'smart-gateway', timeout=90)
        gateway_stopped = False
        release.public_health()
        (release.ROOT / 'deployment.json').write_text(json.dumps({
            'deployed': True,
            'candidateSha256': {service: values[1] for service, values in release.TARGETS.items()},
            'previousIds': {service: old[service]['Id'] for service in release.TARGETS},
        }, indent=2))
        print('DEPLOYED', flush=True)
    except Exception:
        print('ROLLING_BACK', flush=True)
        if not gateway_stopped:
            release.run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
            gateway_stopped = True
        for service, cid in reversed(created):
            release.run('docker', 'rm', '-f', cid)
        for service in reversed(renamed):
            old_id = old[service]['Id']
            release.run('docker', 'rename', old_id, 'smart-' + service)
            if release.NETWORK not in release.inspect(old_id)['NetworkSettings']['Networks']:
                ip = old[service]['NetworkSettings']['Networks'][release.NETWORK]['IPAddress']
                release.run('docker', 'network', 'connect', '--ip', ip, '--alias', 'smart-' + service,
                            release.NETWORK, old_id)
            release.run('docker', 'start', 'smart-' + service, timeout=90)
            release.health(service)
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
