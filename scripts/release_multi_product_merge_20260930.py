"""Second-phase release: preserve a complete multi-product answer through Router merge."""
import pathlib
import sys
import json
import subprocess
import release_product_field_routing_20260929 as release

release.ROOT = pathlib.Path('/opt/smart-assistant/releases/multi-product-merge-20260930')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'multi-product-merge-20260930'
release.release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                'ad515745d97ae20a0a52710a377f280835a713299300f3b0a11081a4f4da1619', 8084),
    # Preserve the live-only order logic. Guarded overlay changes ResultMerger classes only.
    'router': ('smart-assistant-router-scoped.jar',
               '679896254593e592ed928492d0bc3c516c26b90de4194e0992b3dd5de6c15a83', 8083),
}

original_run = release.release.run

def checked_run(*args, input=None, timeout=120):
    if args[:2] != ('docker', 'stop'):
        return original_run(*args, input=input, timeout=timeout)
    result = subprocess.run(args, input=input, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode == 0:
        return result.stdout.decode('utf-8').strip()
    detail = result.stderr.decode('utf-8', errors='replace').lower()
    if any(term in detail for term in ('permission', 'denied', 'unauthorized', 'not authorized')):
        raise RuntimeError('Container stop permission failure')
    # Podman's Docker compatibility CLI can fail after the container has actually exited.
    state = json.loads(original_run('docker', 'inspect', args[-1]))[0]['State']
    if not state['Running'] and state.get('Status') == 'exited':
        print('STOP_CONFIRMED_AFTER_CLI_ERROR ' + args[-1], flush=True)
        return args[-1]
    raise RuntimeError('Container stop failed and stopped state was not confirmed')

release.release.run = checked_run

def main(mode):
    try:
        release.main(mode)
    except Exception as error:
        # Restore an original container stopped before the shared rollback's rename ledger.
        for service in release.release.TARGETS:
            try:
                info = release.release.inspect('smart-' + service)
                if not info['State']['Running']:
                    original_run('docker', 'start', info['Id'])
                    release.release.health(service)
            except Exception:
                print('RECOVERY_REQUIRES_CHECK smart-' + service, file=sys.stderr)
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) == 2 else '')
