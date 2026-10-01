"""One-off Product-only guarded rollout; preserve all environment/model settings and rollback container."""
import argparse
import copy
import json
import pathlib
import shutil
import sys
import release_multi_product_merge_20260930 as recoverable
import release_product_field_routing_20260929 as release
from release_bounded_feedback_20261001 import wait
from verify_knowledge_guard_live import verify
from build_knowledge_guard_overlay import BASELINE, GUARD, QUALITY_ENTRY

ROOT = pathlib.Path('/opt/smart-assistant/releases/knowledge-refusal-guard-20261001')
TAG = 'knowledge-refusal-guard-20261001'
CANARY = 'smart-product-knowledge-guard-canary-20261001'
original_clone = release.clone


def clone(service, source, name):
    safe = copy.deepcopy(source)
    safe['Config']['Labels'].pop('smartassistant.release', None)
    return original_clone(service, safe, name)


def rollback(old, expected):
    current = release.release.inspect('smart-product')
    prior = release.release.inspect(old['Id'])
    if (prior['Name'].lstrip('/') != 'smart-product-before-' + TAG
            or current['Config']['Labels'].get('smartassistant.release') != TAG
            or release.release.sha256(release.release.jar_mount(current)) != expected
            or release.release.sha256(release.release.jar_mount(prior)) != BASELINE):
        raise ValueError('Rollback ownership/artifact drift; manual review required')
    release.release.run('docker', 'stop', '--time', '30', 'smart-gateway', timeout=90)
    release.release.drain()
    release.release.run('docker', 'stop', '--time', '30', current['Id'], timeout=90)
    release.release.run('docker', 'rename', current['Id'], 'smart-product-failed-' + TAG)
    release.release.run('docker', 'network', 'disconnect', release.release.NETWORK, current['Id'])
    release.release.run('docker', 'rename', old['Id'], 'smart-product')
    ip = old['NetworkSettings']['Networks'][release.release.NETWORK]['IPAddress']
    release.release.run('docker', 'network', 'connect', '--ip', ip, '--alias', 'smart-product',
                        release.release.NETWORK, old['Id'])
    release.release.run('docker', 'start', 'smart-product', timeout=90)
    release.release.health('product')
    release.release.run('docker', 'start', 'smart-gateway', timeout=90)
    release.release.public_health()
    (ROOT / 'rollback.json').write_text(json.dumps({'restored': True, 'previousId': old['Id']}))
    print('POST_DEPLOY_ROLLBACK_DONE', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('preflight', 'canary', 'deploy'))
    args = parser.parse_args()
    if pathlib.Path(__file__).resolve().parent != ROOT:
        raise ValueError('Dedicated release root required')
    if shutil.disk_usage(ROOT).free < 1024 ** 3:
        raise ValueError('Insufficient rollout reserve')
    manifest = json.loads((ROOT / 'candidate.json').read_text())
    if (manifest.get('baseline_sha256') != BASELINE or manifest.get('classes_replaced') != 2
            or manifest.get('common_entries_replaced') != [GUARD]
            or manifest.get('product_entries_replaced') != [QUALITY_ENTRY]
            or manifest.get('unrelated_inner_and_outer_entries_preserved') is not True):
        raise ValueError('Reviewed scope drift')
    expected = manifest['sha256']
    snapshot = json.loads((ROOT / 'before-artifacts.json').read_text())
    services = snapshot.get('services', [])
    if len(services) != 1 or services[0].get('name') != 'smart-product' or services[0].get('sha256') != BASELINE:
        raise ValueError('Live snapshot does not match reviewed deployed baseline')
    proof = json.loads((ROOT / 'binary-verified.json').read_text())
    if (proof.get('status') != 'passed' or proof.get('network') != 'none' or proof.get('model_calls') != 0
            or [(r['mode'], r['sha256'], r['checks']) for r in proof['rows']]
               != [('baseline', BASELINE, 8), ('candidate', expected, 8)]):
        raise ValueError('Exact-binary regression proof missing')
    release.release.ROOT = ROOT
    release.release.SNAPSHOT = ROOT / 'before-artifacts.json'
    release.release.TAG = TAG
    release.release.TARGETS = {'product': ('smart-assistant-product-scoped.jar', expected, 8084)}
    release.clone = clone
    if args.mode == 'preflight':
        release.main('preflight')
        return
    if args.mode == 'canary':
        release.main('preflight')
        source = release.release.inspect('smart-product')
        isolated = copy.deepcopy(source)
        isolated['Config']['Cmd'] = [v for v in isolated['Config']['Cmd'] if not v.startswith(
            ('--profile.control.enabled=', '--spring.cloud.nacos.discovery.enabled='))]
        isolated['Config']['Cmd'] += ['--profile.control.enabled=false', '--spring.cloud.nacos.discovery.enabled=false']
        cid = clone('product', isolated, CANARY)
        try:
            release.release.run('docker', 'start', cid)
            wait(CANARY)
            verify(CANARY, expected, ROOT / 'canary-verified.json')
            print('CANARY_VERIFIED', flush=True)
        finally:
            if release.release.inspect(cid)['Config']['Labels'].get('smartassistant.release') != TAG:
                raise ValueError('Canary cleanup ownership drift')
            release.release.run('docker', 'rm', '-f', cid)
    else:
        canary = json.loads((ROOT / 'canary-verified.json').read_text())
        if canary.get('status') != 'passed' or canary.get('sha256') != expected:
            raise ValueError('No verified canary')
        old = release.release.inspect('smart-product')
        release.main('deploy')
        try:
            verify('smart-product', expected, ROOT / 'production-verified.json')
        except Exception:
            rollback(old, expected)
            raise
        print('PRODUCTION_GUARD_FIX_VERIFIED', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
