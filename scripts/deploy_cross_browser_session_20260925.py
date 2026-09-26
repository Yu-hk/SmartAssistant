"""Single-consumer cutover for the cross-browser session fix.

Run only on the current production host after release_artifacts.py has saved a
fresh smart-consumer snapshot. The existing container is retained for rollback.
This script does not print container environment variables.
"""

import json
import pathlib
import sys

import deploy_assessment_20260925 as release


release.ROOT = pathlib.Path('/opt/smart-assistant/releases/cross-browser-session-20260925')
release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.TAG = 'cross-browser-session-20260925'
release.TARGETS = {
    'consumer': ('smart-assistant-consumer-1.0.0-SNAPSHOT.jar',
                 '1c7a7395f4d9ce9f3f2f6cef0010925ec20a340ae8778726913e926b57ec0e80', 8082),
}


def main(mode):
    release.require(mode in ('preflight', 'deploy'), 'Expected preflight or deploy')
    release.require(not (release.ROOT / 'deployment.json').exists(), 'Release already deployed')
    baseline = json.loads(release.SNAPSHOT.read_text())
    release.require({row['name'] for row in baseline['services']} == {'smart-consumer'},
                    'Snapshot service set drift')
    filename, expected, _ = release.TARGETS['consumer']
    release.require(release.sha256(release.ROOT / filename) == expected,
                    'Candidate hash mismatch')
    old = release.inspect('smart-consumer')
    release.validate_live('consumer', old, baseline['services'][0])
    release.require(release.inspect('smart-gateway')['State']['Running'],
                    'Gateway not running')
    probe = release.clone('consumer', old, 'smart-consumer-cross-browser-preflight')
    try:
        release.equivalent('consumer', old, release.inspect(probe))
    finally:
        release.run('docker', 'rm', probe)
    print('PREFLIGHT_OK', flush=True)
    if mode == 'preflight':
        return

    gateway_stopped = False
    renamed = False
    created = None
    old_id = old['Id']
    try:
        release.run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
        gateway_stopped = True
        release.drain()
        release.run('docker', 'stop', '--time', '30', 'smart-consumer', timeout=90)
        release.run('docker', 'rename', old_id,
                    'smart-consumer-before-' + release.TAG)
        renamed = True
        release.run('docker', 'network', 'disconnect', release.NETWORK, old_id)
        created = release.clone('consumer', old, 'smart-consumer')
        release.equivalent('consumer', old, release.inspect(created))
        release.run('docker', 'start', 'smart-consumer', timeout=90)
        release.health('consumer')
        release.require(release.run('docker', 'exec', 'smart-consumer',
                                    'sha256sum', '/app/app.jar').split()[0] == expected,
                        'Running artifact hash mismatch')
        release.run('docker', 'start', 'smart-gateway', timeout=90)
        gateway_stopped = False
        release.public_health()
        (release.ROOT / 'deployment.json').write_text(json.dumps({
            'deployed': True,
            'candidateSha256': expected,
            'previousId': old_id,
        }, indent=2))
        print('DEPLOYED', flush=True)
    except Exception:
        print('ROLLING_BACK', flush=True)
        if not gateway_stopped:
            release.run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
            gateway_stopped = True
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
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error),
              file=sys.stderr)
        sys.exit(1)
