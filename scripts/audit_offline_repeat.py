"""Reassess saved XML from two executions, without rewriting their historical receipts."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import run_offline_baseline as baseline

LEGACY_EXACT_COMPARISON_ERROR = 'repeat differs in source, suite selection, tests or coverage'


def validate_measurement(record, items, source_fingerprint, policy_sha, actual_modules):
    allowed_status = record.get('status') == 'passed' or (
        record.get('status') == 'failed' and record.get('error') == LEGACY_EXACT_COMPARISON_ERROR)
    if not allowed_status or record.get('maven_exit_code') != 0:
        raise ValueError('not a completed successful Maven execution')
    if record.get('inventory') != items or record.get('source_fingerprint') != source_fingerprint:
        raise ValueError('saved measurement is not bound to current source/selection')
    if record.get('policy_sha256') != policy_sha or record.get('modules') != actual_modules:
        raise ValueError('saved counters do not match reviewed policy/raw XML')


def audit(repo, paths, output):
    repo, output = repo.resolve(), output.resolve()
    base = repo / '.codex-output/offline-baseline'
    if base not in output.parents or output.exists():
        raise ValueError('audit output must be a new file under the dedicated baseline root')
    items = baseline.inventory(repo)
    policy_path = repo / 'scripts/offline-baseline-policy.json'
    baseline.verify_policy(items, json.loads(policy_path.read_text(encoding='utf-8')))
    source = baseline.fingerprint(repo, items)
    policy_sha = hashlib.sha256(policy_path.read_bytes()).hexdigest()
    records, inputs = [], []
    for candidate in paths:
        path = candidate.resolve()
        if base not in path.parents or path.name != 'baseline.json':
            raise ValueError('input must be a saved receipt below the dedicated baseline root')
        record = json.loads(path.read_text(encoding='utf-8'))
        modules = [baseline.read_module(repo, item, path.parent / 'reports') for item in items]
        validate_measurement(record, items, source, policy_sha, modules)
        records.append(record)
        digest = hashlib.sha256()
        for xml in sorted((path.parent / 'reports').rglob('*.xml')):
            digest.update(xml.relative_to(path.parent).as_posix().encode('utf-8') + b'\0')
            digest.update(hashlib.sha256(xml.read_bytes()).digest())
        inputs.append({'receipt_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                       'raw_xml_fingerprint': digest.hexdigest(), 'historical_status': record['status']})
    result = {'schema_version': 1, 'status': 'passed', 'scope': 'post_run_repeat_audit',
              'tests_executed_by_audit': 0, 'inputs': inputs, 'source_fingerprint': source,
              'policy_sha256': policy_sha, 'executing_runner_sha256': records[0]['runner_sha256'],
              'comparison_implementation_sha256': hashlib.sha256(Path(baseline.__file__).read_bytes()).hexdigest(),
              'audit_implementation_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'testcases_per_execution': sum(module['testcases']['tests'] for module in records[0]['modules'])}
    result.update(baseline.compare_reports(*records))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('first', type=Path)
    parser.add_argument('second', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = audit(Path(__file__).resolve().parent.parent, [args.first, args.second], args.output)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print('Saved XML verified; cases/run=' + str(result['testcases_per_execution']) +
          '; identical coverage=' + str(result['coverage_repeat_exact']) + '; no tests re-executed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
