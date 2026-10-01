"""Fresh read-only collection + network-disabled answer audit, no model calls.

Run on production host from a fresh /opt/smart-assistant/eval/ragas-answer-audit-*
directory. No deps installation, service restart, DB mutation or key access.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

from ragas_feedback import write_new


def snapshot():
    result = {}
    for name in ('smart-product', 'smart-router', 'smart-consumer'):
        output = subprocess.check_output(['docker', 'inspect', '--format', '{{json .Mounts}}', name], timeout=30)
        mounts = json.loads(output.decode())
        jars = [m['Source'] for m in mounts if m['Destination'].endswith('.jar')]
        if len(jars) != 1: raise ValueError('Single explicit business JAR required')
        path = Path(jars[0]).resolve(strict=True)
        path.relative_to('/opt/smart-assistant/releases')
        hasher = hashlib.sha256()
        with path.open('rb') as source:
            for block in iter(lambda: source.read(1024 * 1024), b''): hasher.update(block)
        image = subprocess.check_output(['docker', 'inspect', '--format', '{{.Image}}', name], timeout=30).decode().strip()
        result[name] = {'jar_sha256': hasher.hexdigest(), 'image': image}
    return result


def audit_command(root, image, historical=False):
    if (root.parent != Path('/opt/smart-assistant/eval') or not re.fullmatch(r'ragas-answer-audit-[a-z0-9-]+', root.name)
            or not re.fullmatch(r'sha256:[a-f0-9]{64}', image) or type(historical) is not bool):
        raise ValueError('Dedicated root and pinned image required')
    suffix = 'historical' if historical else 'fresh'
    command = ['docker', 'run', '--rm', '--name', root.name + '-' + suffix,
               '--network', 'none', '--cpus', '1', '--memory', '512m', '--pids-limit', '64',
               '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
               '--tmpfs', '/tmp:rw,nosuid,noexec,size=33554432',
               '-v', str(root) + ':/eval:ro', '-v', str(root / 'outputs') + ':/outputs:rw',
               '-e', 'PYTHONDONTWRITEBYTECODE=1', image, 'python', '/eval/ragas_answer_audit.py',
               '--dataset', '/eval/' + ('historical-dataset.json' if historical else 'dataset.json'),
               '--assertions', '/eval/assertions.json', '--include-controls',
               '--output', '/outputs/' + suffix + '-audit.json']
    if historical: command += ['--score-report', '/eval/historical-scores.json']
    return command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--expected-product-sha256', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    audit_command(root, args.image)  # Validate scope before any file/collection operation.
    if not re.fullmatch(r'[a-f0-9]{64}', args.expected_product_sha256): raise ValueError('Expected Product SHA required')
    if not (root / 'outputs').is_dir() or any((root / name).exists() for name in
            ('before-artifacts.json', 'after-artifacts.json', 'dataset.json', 'verification.json')):
        raise ValueError('Fresh owned run directory required')
    if any((root / 'outputs' / name).exists() for name in ('fresh-audit.json', 'historical-audit.json')):
        raise ValueError('Never overwrite existing reports')
    before = snapshot()
    if before['smart-product']['jar_sha256'] != args.expected_product_sha256: raise ValueError('Product artifact drift')
    write_new(root / 'before-artifacts.json', before)
    subprocess.run([sys.executable, str(root / 'collect_ragas_product.py'),
                    '--questions', str(root / 'questions.json'), '--output', str(root / 'dataset.json'),
                    '--expected-product-sha256', args.expected_product_sha256], check=True, timeout=600)
    for historical in (False, True):
        command = audit_command(root, args.image, historical)
        try: subprocess.run(command, check=True, timeout=120)
        except subprocess.TimeoutExpired:
            subprocess.run(['docker', 'stop', '--time', '10', root.name + ('-historical' if historical else '-fresh')],
                           check=True, timeout=30)
            raise RuntimeError('Audit timeout; inspect owned run before retry')
    after = snapshot(); write_new(root / 'after-artifacts.json', after)
    if before != after: raise ValueError('Business artifacts changed during audit')
    summaries = {}
    for kind in ('fresh', 'historical'):
        report = json.loads((root / 'outputs' / (kind + '-audit.json')).read_text(encoding='utf-8'))
        summaries[kind] = report['summary']
        if (report['new_model_calls'] != 0 or report['retries_executed'] != 0 or report['ragas_scores_adjusted'] is not False
                or report['summary'].get('live_catalog_readonly') != {'PASS': 8, 'FAIL': 0, 'NOT_ASSESSED': 0}
                or report['summary'].get('negative_control') != {'PASS': 0, 'FAIL': 8, 'NOT_ASSESSED': 0}):
            raise ValueError('Audit acceptance not met; reports preserved')
    result = {'summaries': summaries, 'business_artifacts_unchanged': True, 'new_model_calls': 0,
              'orders_written': 0, 'retries_executed': 0, 'audit_network': 'none', 'image': args.image}
    write_new(root / 'verification.json', result)
    print(json.dumps(result), flush=True)


if __name__ == '__main__': main()
