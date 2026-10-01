"""Single-class overlay on the reviewed deployed embedding artifact; no dependency/config changes."""
import argparse
import io
import json
import zipfile
from pathlib import Path
from build_knowledge_guard_overlay import rewrite, sha, unique

BASELINE = '27b51d0a143fdf7b49d20e0105a4f3f54c89238f2998f8554dc240abcb9631c4'
CLASS = 'com/example/smartassistant/embedding/EmbeddingApplication.class'
ENTRY = 'BOOT-INF/classes/' + CLASS


def build(source, classes, output):
    if output.exists() or output.with_suffix('.json').exists():
        raise FileExistsError('Fresh artifact and receipt required')
    baseline_data = source.read_bytes()
    if sha(baseline_data) != BASELINE:
        raise ValueError('Deployed baseline drift')
    replacement = (classes / CLASS).read_bytes()
    if replacement[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(replacement[6:8], 'big') != 65:
        raise ValueError('Expected compiled Java 21 class')
    with zipfile.ZipFile(io.BytesIO(baseline_data)) as baseline:
        unique(baseline)
        before = baseline.read(ENTRY)
        if before == replacement:
            raise ValueError('No class change')
        data = rewrite(baseline, {ENTRY: replacement})
    with output.open('xb') as stream:
        stream.write(data)
    return {'baseline_sha256': BASELINE, 'sha256': sha(data), 'classes_replaced': 1,
            'embedding_entries_replaced': [ENTRY], 'unrelated_entries_preserved': True,
            'before_class_sha256': sha(before), 'after_class_sha256': sha(replacement)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('baseline', 'classes', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    result = build(args.baseline, args.classes, args.output)
    with args.output.with_suffix('.json').open('x', encoding='utf-8') as receipt:
        json.dump(result, receipt, indent=2)
    print(json.dumps(result))


if __name__ == '__main__':
    main()
