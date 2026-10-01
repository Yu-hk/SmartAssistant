"""Compare exact release classes in an isolated, networkless production Java runtime."""
import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path
from build_knowledge_guard_overlay import BASELINE, COMMON, QUALITY_ENTRY
from ragas_feedback import write_new


def main():
    parser = argparse.ArgumentParser()
    for name in ('baseline', 'candidate', 'probe', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--sha', required=True)
    args = parser.parse_args()
    if hashlib.sha256(args.baseline.read_bytes()).hexdigest() != BASELINE:
        raise ValueError('Baseline drift')
    if hashlib.sha256(args.candidate.read_bytes()).hexdigest() != args.sha:
        raise ValueError('Candidate transfer/artifact drift')
    root = args.output.parent / (args.output.stem + '-files')
    root.mkdir(mode=0o700, exist_ok=False)
    rows = []
    for mode, source in (('baseline', args.baseline), ('candidate', args.candidate)):
        folder = root / mode
        folder.mkdir(mode=0o700)
        with zipfile.ZipFile(source) as jar:
            (folder / 'common.jar').write_bytes(jar.read(COMMON))
            target = folder / 'classes' / QUALITY_ENTRY[len('BOOT-INF/classes/'):]
            target.parent.mkdir(parents=True)
            target.write_bytes(jar.read(QUALITY_ENTRY))
        (folder / 'LoopGuardRegressionProbe.class').write_bytes(args.probe.read_bytes())
        result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--memory', '256m',
            '--mount', 'type=bind,source=' + str(folder.resolve()) + ',target=/guard-test,readonly',
            '--entrypoint', 'java', args.image, '-Xmx96m', '-cp',
            '/guard-test:/guard-test/common.jar:/guard-test/classes', 'LoopGuardRegressionProbe', mode],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
        if result.returncode:
            raise RuntimeError('Networkless binary probe failed: ' + mode)
        row = json.loads(result.stdout.decode().strip())
        if row != {'mode': mode, 'status': 'passed', 'checks': 8}:
            raise ValueError('Probe result drift')
        row['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        rows.append(row)
    write_new(args.output, {'status': 'passed', 'network': 'none', 'model_calls': 0, 'rows': rows})
    print('EXACT_BINARY_OLD_FAILURE_AND_NEW_FIX_VERIFIED')


if __name__ == '__main__':
    main()
