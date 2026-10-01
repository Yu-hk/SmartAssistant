"""Product-only guarded overlay; never replaces model settings or the full Common library."""
import argparse
import copy
import hashlib
import io
import json
import zipfile
from pathlib import Path

BASELINE = '1524087b617c1df38744e7c06337c2e0f5f178ee95307b4662ce9e666c930693'
COMMON = 'BOOT-INF/lib/smart-assistant-common-1.0.0-SNAPSHOT.jar'
GUARD = 'com/example/smartassistant/common/agent/LoopGuardService.class'
QUALITY = 'com/example/smartassistant/service/quality/ProductDomainQualityValidator.class'
QUALITY_ENTRY = 'BOOT-INF/classes/' + QUALITY


def sha(data):
    return hashlib.sha256(data).hexdigest()


def unique(archive):
    if len(archive.namelist()) != len(set(archive.namelist())):
        raise ValueError('Duplicate archive entries')


def rewrite(source, replacements):
    unique(source)
    if not set(replacements) <= set(source.namelist()):
        raise ValueError('Expected existing classes absent')
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as candidate:
        candidate.comment = source.comment
        for info in source.infolist():
            candidate.writestr(copy.copy(info), replacements.get(info.filename, source.read(info.filename)))
    data = buffer.getvalue()
    with zipfile.ZipFile(io.BytesIO(data)) as candidate:
        unique(candidate)
        if candidate.namelist() != source.namelist():
            raise ValueError('Archive topology drift')
        for name in source.namelist():
            if name not in replacements and candidate.read(name) != source.read(name):
                raise ValueError('Unrelated entry changed')
    return data


def build(source, common_classes, product_classes, output):
    if output.exists():
        raise FileExistsError('Fresh output required')
    data = source.read_bytes()
    if sha(data) != BASELINE:
        raise ValueError('Deployed baseline drift')
    replacements = {GUARD: (common_classes / GUARD).read_bytes(),
                    QUALITY: (product_classes / QUALITY).read_bytes()}
    for data_class in replacements.values():
        if data_class[:4] != b'\xca\xfe\xba\xbe' or not 52 <= int.from_bytes(data_class[6:8], 'big') <= 65:
            raise ValueError('Expected compatible compiled Java class')
    with zipfile.ZipFile(io.BytesIO(data)) as baseline:
        unique(baseline)
        inner_before = baseline.read(COMMON)
        with zipfile.ZipFile(io.BytesIO(inner_before)) as inner:
            inner_after = rewrite(inner, {GUARD: replacements[GUARD]})
            guard_before = inner.read(GUARD)
        candidate = rewrite(baseline, {COMMON: inner_after, QUALITY_ENTRY: replacements[QUALITY]})
        quality_before = baseline.read(QUALITY_ENTRY)
    with output.open('xb') as stream:
        stream.write(candidate)
    return {'baseline_sha256': BASELINE, 'sha256': sha(candidate), 'classes_replaced': 2,
            'common_entries_replaced': [GUARD], 'product_entries_replaced': [QUALITY_ENTRY],
            'unrelated_inner_and_outer_entries_preserved': True,
            'before_classes': {GUARD: sha(guard_before), QUALITY: sha(quality_before)},
            'after_classes': {name: sha(value) for name, value in replacements.items()}}


def main():
    parser = argparse.ArgumentParser()
    for name in ('baseline', 'common-classes', 'product-classes', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    receipt = build(args.baseline, args.common_classes, args.product_classes, args.output)
    with args.output.with_suffix('.json').open('x', encoding='utf-8') as stream:
        json.dump(receipt, stream, indent=2)
    print(json.dumps(receipt))


if __name__ == '__main__':
    main()
