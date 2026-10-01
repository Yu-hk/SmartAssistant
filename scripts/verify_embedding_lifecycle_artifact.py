"""Verify exact baseline/candidate binaries without starting the service or loading a model."""
import argparse
import io
import json
import subprocess
import tempfile
import zipfile
from pathlib import Path
from build_embedding_lifecycle_overlay import BASELINE, ENTRY
from build_knowledge_guard_overlay import sha, unique


BEAN = 'Lorg/springframework/context/annotation/Bean;'
PRE_DESTROY = 'Ljakarta/annotation/PreDestroy;'
MODEL_METHOD = ('method', 'bgeEmbeddingModel',
                '()Lcom/example/smartassistant/common/embedding/BgeEmbeddingModel;')
CLEANUP_METHOD = ('method', 'cleanup', '()V')
ANNOTATION_ATTRIBUTES = ('RuntimeVisibleAnnotations', 'RuntimeInvisibleAnnotations')


class _Reader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def take(self, count):
        if count < 0 or count > len(self.data) - self.offset:
            raise ValueError('Truncated Java class file')
        result = self.data[self.offset:self.offset + count]
        self.offset += count
        return result

    def number(self, count):
        return int.from_bytes(self.take(count), 'big')

    def end(self):
        if self.offset != len(self.data):
            raise ValueError('Trailing class-file/attribute data')


def _modified_utf8(raw):
    """Decode CONSTANT_Utf8 to Java UTF-16 code units, including encoded NUL/surrogates."""
    result, offset = [], 0
    while offset < len(raw):
        first = raw[offset]
        if 1 <= first <= 0x7f:
            result.append(chr(first))
            offset += 1
        elif 0xc0 <= first <= 0xdf and offset + 1 < len(raw):
            second = raw[offset + 1]
            if second & 0xc0 != 0x80:
                raise ValueError('Invalid modified UTF-8 constant')
            value = ((first & 0x1f) << 6) | (second & 0x3f)
            if value < 0x80 and (first, second) != (0xc0, 0x80):
                raise ValueError('Overlong modified UTF-8 constant')
            result.append(chr(value))
            offset += 2
        elif 0xe0 <= first <= 0xef and offset + 2 < len(raw):
            second, third = raw[offset + 1:offset + 3]
            if second & 0xc0 != 0x80 or third & 0xc0 != 0x80:
                raise ValueError('Invalid modified UTF-8 constant')
            value = ((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f)
            if value < 0x800:
                raise ValueError('Overlong modified UTF-8 constant')
            result.append(chr(value))
            offset += 3
        else:
            raise ValueError('Invalid modified UTF-8 constant')
    return ''.join(result)


def annotation_bindings(data):
    """Normalize bound runtime annotations; unsupported annotation attributes fail closed.

    Keys include the class/field/method owner, descriptor and visibility. Values
    resolve constant-pool indices and normalize element/annotation ordering while
    preserving array order and the exact primitive bits (including NaNs).
    """
    reader = _Reader(data)
    if reader.take(4) != b'\xca\xfe\xba\xbe':
        raise ValueError('Expected Java class file')
    reader.number(2)  # minor version
    if reader.number(2) != 65:
        raise ValueError('Expected compiled Java 21 class')
    pool = [None] * reader.number(2)
    index = 1
    while index < len(pool):
        tag = reader.number(1)
        if tag == 1:
            value = _modified_utf8(reader.take(reader.number(2)))
        elif tag in (3, 4):
            value = reader.take(4)
        elif tag in (5, 6):
            value = reader.take(8)
        elif tag in (7, 8, 16, 19, 20):
            value = reader.number(2)
        elif tag in (9, 10, 11, 12, 17, 18):
            value = (reader.number(2), reader.number(2))
        elif tag == 15:
            value = (reader.number(1), reader.number(2))
        else:
            raise ValueError('Unsupported constant-pool tag: ' + str(tag))
        pool[index] = (tag, value)
        index += 2 if tag in (5, 6) else 1
        if index > len(pool):
            raise ValueError('Invalid double-slot constant')

    def constant(position, expected):
        if not 0 < position < len(pool) or pool[position] is None or pool[position][0] != expected:
            raise ValueError('Invalid annotation constant-pool reference')
        return pool[position][1]

    def utf8(position):
        return constant(position, 1)

    def annotation(source, depth=0):
        if depth > 64:
            raise ValueError('Annotation nesting exceeds bounded proof depth')
        descriptor = utf8(source.number(2))
        elements = []
        for _ in range(source.number(2)):
            name = utf8(source.number(2))
            if any(existing == name for existing, _ in elements):
                raise ValueError('Duplicate annotation element')
            elements.append((name, element(source, depth + 1)))
        return descriptor, tuple(sorted(elements))

    def element(source, depth):
        if depth > 64:
            raise ValueError('Annotation nesting exceeds bounded proof depth')
        tag = chr(source.number(1))
        if tag in 'BCISZ':
            return tag, constant(source.number(2), 3)
        if tag in 'DFJ':
            return tag, constant(source.number(2), {'D': 6, 'F': 4, 'J': 5}[tag])
        if tag in ('s', 'c'):
            return tag, utf8(source.number(2))
        if tag == 'e':
            return tag, utf8(source.number(2)), utf8(source.number(2))
        if tag == '@':
            return tag, annotation(source, depth + 1)
        if tag == '[':
            return tag, tuple(element(source, depth + 1) for _ in range(source.number(2)))
        raise ValueError('Unsupported annotation element tag: ' + tag)

    bindings = {}

    def attributes(source, owner, code=False):
        annotations = {name: () for name in ANNOTATION_ATTRIBUTES}
        seen = set()
        for _ in range(source.number(2)):
            name = utf8(source.number(2))
            payload = _Reader(source.take(source.number(4)))
            if name in ANNOTATION_ATTRIBUTES:
                if code or name in seen:
                    raise ValueError('Invalid or duplicate bound annotation attribute')
                seen.add(name)
                values = tuple(annotation(payload) for _ in range(payload.number(2)))
                if len({value[0] for value in values}) != len(values):
                    raise ValueError('Duplicate bound annotation type')
                annotations[name] = tuple(sorted(values))
                payload.end()
            elif 'Annotation' in name:
                # Parameter/type/record annotations are not part of this reviewed
                # application; never silently ignore a newly introduced binding.
                raise ValueError('Unsupported annotation attribute: ' + name)
            elif name == 'Code':
                if code:
                    raise ValueError('Nested Code attribute')
                payload.number(2)  # max_stack
                payload.number(2)  # max_locals
                payload.take(payload.number(4))
                payload.take(payload.number(2) * 8)  # exception table
                attributes(payload, owner, code=True)
                payload.end()
            elif name == 'Record':
                raise ValueError('Record annotation bindings are outside the reviewed class')
        if not code:
            if owner in bindings:
                raise ValueError('Duplicate class/field/method binding')
            bindings[owner] = tuple((name, annotations[name]) for name in ANNOTATION_ATTRIBUTES)

    reader.number(2)  # access flags; javap -p -c independently compares declarations/code
    class_name = utf8(constant(reader.number(2), 7))
    reader.number(2)  # super class
    reader.take(reader.number(2) * 2)  # interfaces
    for kind in ('field', 'method'):
        for _ in range(reader.number(2)):
            reader.number(2)  # access flags
            owner = (kind, utf8(reader.number(2)), utf8(reader.number(2)))
            attributes(reader, owner)
    attributes(reader, ('class', class_name))
    reader.end()
    return bindings


def verify_annotations(before, after):
    old, new = annotation_bindings(before), annotation_bindings(after)

    def visible(bindings, owner):
        return dict(bindings.get(owner, ())).get('RuntimeVisibleAnnotations', ())

    for bindings in (old, new):
        if (PRE_DESTROY, ()) not in visible(bindings, CLEANUP_METHOD):
            raise ValueError('Existing cleanup owner must retain its bound @PreDestroy')
    old_model = visible(old, MODEL_METHOD)
    new_model = visible(new, MODEL_METHOD)
    if (BEAN, ()) not in old_model or (BEAN, (('destroyMethod', ('s', '')),)) not in new_model:
        raise ValueError('Explicit single-owner destruction annotation required on the exact model method')
    expected = dict(old)
    expected_visible = tuple(sorted(
        (BEAN, (('destroyMethod', ('s', '')),)) if annotation[0] == BEAN else annotation
        for annotation in old_model))
    expected[MODEL_METHOD] = tuple(
        (name, expected_visible if name == 'RuntimeVisibleAnnotations' else values)
        for name, values in old[MODEL_METHOD])
    if expected != new:
        raise ValueError('Annotations changed outside the reviewed model @Bean.destroyMethod binding')


def verify(baseline, candidate, javap):
    old_data, new_data = baseline.read_bytes(), candidate.read_bytes()
    if sha(old_data) != BASELINE:
        raise ValueError('Reviewed baseline hash required')
    with zipfile.ZipFile(io.BytesIO(old_data)) as old, zipfile.ZipFile(io.BytesIO(new_data)) as new:
        unique(old)
        unique(new)
        if old.namelist() != new.namelist():
            raise ValueError('Archive topology drift')
        changed = [name for name in old.namelist() if old.read(name) != new.read(name)]
        if changed != [ENTRY]:
            raise ValueError('Only the application lifecycle annotation may change')
        with tempfile.TemporaryDirectory() as tmp:
            before, after = Path(tmp) / 'before.class', Path(tmp) / 'after.class'
            before.write_bytes(old.read(ENTRY))
            after.write_bytes(new.read(ENTRY))
            verify_annotations(before.read_bytes(), after.read_bytes())
            def inspect(path, detail):
                return subprocess.check_output([str(javap), '-p', detail, str(path)],
                    stderr=subprocess.PIPE, timeout=30)
            if inspect(before, '-c') != inspect(after, '-c'):
                raise ValueError('Method bytecode changed outside the reviewed annotation')
    return {'status': 'passed', 'baseline_sha256': sha(old_data), 'sha256': sha(new_data),
            'changed_entries': changed, 'method_bytecode_identical': True,
            'explicit_destroy_method_empty': True, 'pre_destroy_retained': True,
            'bound_runtime_annotations_exact': True,
            'annotations_bound_checked': True,
            'model_calls': 0, 'business_writes': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('baseline', 'candidate', 'javap', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError('Fresh verification receipt required')
    result = verify(args.baseline, args.candidate, args.javap)
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, indent=2)
    args.output.chmod(0o600)
    print('EMBEDDING_EXACT_ARTIFACT_VERIFIED ' + result['sha256'])


if __name__ == '__main__':
    main()
