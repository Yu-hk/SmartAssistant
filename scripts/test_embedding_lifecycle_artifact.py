import os
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
import verify_embedding_lifecycle_artifact as proof


def class_bytes(bean=None, field_value='safe-model-path', class_extra=False,
                cleanup=True, cleanup_visibility='RuntimeVisibleAnnotations',
                model_name='bgeEmbeddingModel', unsupported=False):
    """Small valid class-file fixture: bound annotations, not javap substring mocks."""
    pool, cache = [], {}

    def u2(value):
        return struct.pack('>H', value)

    def u4(value):
        return struct.pack('>I', value)

    def utf8(value):
        key = (1, value)
        if key not in cache:
            data = value.encode('utf-8')
            pool.append(b'\x01' + u2(len(data)) + data)
            cache[key] = len(pool)
        return cache[key]

    def class_index(value):
        key = (7, value)
        if key not in cache:
            index = utf8(value)
            pool.append(b'\x07' + u2(index))
            cache[key] = len(pool)
        return cache[key]

    def element(value):
        tag, content = value
        if tag == 's':
            return b's' + u2(utf8(content))
        if tag == '?':
            return b'?'
        raise AssertionError('Unsupported fixture element')

    def attribute(name, payload):
        return u2(utf8(name)) + u4(len(payload)) + payload

    def annotations(values, visibility='RuntimeVisibleAnnotations'):
        payload = u2(len(values))
        for descriptor, pairs in values:
            payload += u2(utf8(descriptor)) + u2(len(pairs))
            for name, value in pairs:
                payload += u2(utf8(name)) + element(value)
        return attribute(visibility, payload)

    def method(name, descriptor, opcodes, values=(), visibility='RuntimeVisibleAnnotations', extra=False):
        code = u2(1) + u2(1) + u4(len(opcodes)) + opcodes + u2(0) + u2(0)
        attrs = [attribute('Code', code)]
        if values:
            attrs.append(annotations(values, visibility))
        if extra:
            attrs.append(attribute('RuntimeVisibleParameterAnnotations', b'\x00'))
        return u2(1) + u2(utf8(name)) + u2(utf8(descriptor)) + u2(len(attrs)) + b''.join(attrs)

    this_class = class_index('com/example/smartassistant/embedding/EmbeddingApplication')
    super_class = class_index('java/lang/Object')
    # Keep the descriptor even if cleanup lacks its annotation: constant-pool
    # occurrences must never be mistaken for actual method bindings.
    utf8(proof.PRE_DESTROY)
    field = (u2(2) + u2(utf8('modelPath')) + u2(utf8('Ljava/lang/String;')) + u2(1)
             + annotations([('Lorg/springframework/beans/factory/annotation/Value;',
                             [('value', ('s', field_value))])]))
    if bean is None:
        bean = []
    methods = [
        method(model_name, proof.MODEL_METHOD[2], b'\x01\xb0', [(proof.BEAN, bean)]),
        method('cleanup', '()V', b'\xb1', [(proof.PRE_DESTROY, [])] if cleanup else (),
               cleanup_visibility, unsupported),
    ]
    class_attrs = [annotations([('Lorg/springframework/context/annotation/ComponentScan;',
                                [('value', ('s', 'unexpected.common'))])])] if class_extra else []
    body = (u2(0x21) + u2(this_class) + u2(super_class) + u2(0)
            + u2(1) + field + u2(len(methods)) + b''.join(methods)
            + u2(len(class_attrs)) + b''.join(class_attrs))
    return b'\xca\xfe\xba\xbe' + u2(0) + u2(65) + u2(len(pool) + 1) + b''.join(pool) + body


class EmbeddingArtifactProofTest(unittest.TestCase):
    def fixtures(self, root, other=b'same', old_options=None, new_options=None):
        old, new = root / 'old.jar', root / 'new.jar'
        new_settings = {'bean': [('destroyMethod', ('s', ''))]}
        new_settings.update(new_options or {})
        for target, application, config in ((old, class_bytes(**(old_options or {})), b'same'),
                                            (new, class_bytes(**new_settings), other)):
            with zipfile.ZipFile(target, 'w') as archive:
                archive.writestr(proof.ENTRY, application)
                archive.writestr('BOOT-INF/classes/application.yml', config)
        return old, new

    def inspector(self, args, **kwargs):
        if args[-2] == '-c':
            # javap can emit local-code-page literals: byte equality must not assume UTF-8.
            return b'public method bytecode \xd5\xc5'
        raise AssertionError('Proof must parse class-file annotations, not inspect javap strings')

    def test_exact_annotation_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = self.fixtures(Path(tmp))
            with patch.object(proof, 'BASELINE', proof.sha(old.read_bytes())), patch.object(
                    proof.subprocess, 'check_output', side_effect=self.inspector):
                result = proof.verify(old, new, Path('javap'))
            self.assertTrue(result['method_bytecode_identical'])
            self.assertTrue(result['annotations_bound_checked'])
            self.assertEqual([proof.ENTRY], result['changed_entries'])
            self.assertEqual(0, result['model_calls'])

    def test_wrong_baseline(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = self.fixtures(Path(tmp))
            with self.assertRaisesRegex(ValueError, 'baseline hash'):
                proof.verify(old, new, Path('javap'))

    def test_configuration_drift_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = self.fixtures(Path(tmp), other=b'changed config')
            with patch.object(proof, 'BASELINE', proof.sha(old.read_bytes())), self.assertRaisesRegex(
                    ValueError, 'Only the application'):
                proof.verify(old, new, Path('javap'))

    def test_changed_method_code_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = self.fixtures(Path(tmp))
            with patch.object(proof, 'BASELINE', proof.sha(old.read_bytes())), patch.object(
                    proof.subprocess, 'check_output', side_effect=[b'code one', b'code two']), self.assertRaisesRegex(
                    ValueError, 'Method bytecode changed'):
                proof.verify(old, new, Path('javap'))

    def test_missing_or_already_applied_annotation_rejected(self):
        explicit = [('destroyMethod', ('s', ''))]
        for old_options, new_options in (({}, {'bean': [], 'class_extra': True}),
                                         ({'bean': explicit}, {'bean': explicit, 'class_extra': True})):
            with self.subTest(old=old_options, new=new_options), tempfile.TemporaryDirectory() as tmp:
                old, new = self.fixtures(Path(tmp), old_options=old_options, new_options=new_options)
                with patch.object(proof, 'BASELINE', proof.sha(old.read_bytes())), patch.object(
                        proof.subprocess, 'check_output', side_effect=self.inspector), self.assertRaisesRegex(
                        ValueError, 'single-owner'):
                    proof.verify(old, new, Path('javap'))

    def test_missing_cleanup_owner_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = self.fixtures(Path(tmp), new_options={'cleanup': False})
            with zipfile.ZipFile(new) as archive:
                self.assertIn(proof.PRE_DESTROY.encode(), archive.read(proof.ENTRY))
            with patch.object(proof, 'BASELINE', proof.sha(old.read_bytes())), patch.object(
                    proof.subprocess, 'check_output', side_effect=self.inspector), self.assertRaisesRegex(
                    ValueError, 'cleanup owner'):
                proof.verify(old, new, Path('javap'))

    def assert_annotation_drift(self, options):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = self.fixtures(Path(tmp), new_options=options)
            with patch.object(proof, 'BASELINE', proof.sha(old.read_bytes())), patch.object(
                    proof.subprocess, 'check_output', side_effect=self.inspector), self.assertRaisesRegex(
                    ValueError, 'Annotations changed outside'):
                proof.verify(old, new, Path('javap'))

    def test_class_annotation_change_is_rejected(self):
        self.assert_annotation_drift({'class_extra': True})

    def test_field_value_annotation_change_is_rejected(self):
        self.assert_annotation_drift({'field_value': 'production-model-path'})

    def test_cleanup_annotation_visibility_change_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = self.fixtures(Path(tmp), new_options={'cleanup_visibility': 'RuntimeInvisibleAnnotations'})
            with patch.object(proof, 'BASELINE', proof.sha(old.read_bytes())), self.assertRaisesRegex(
                    ValueError, 'cleanup owner'):
                proof.verify(old, new, Path('javap'))

    def test_destroy_method_on_a_different_method_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = self.fixtures(Path(tmp), new_options={'model_name': 'unrelatedModel'})
            with patch.object(proof, 'BASELINE', proof.sha(old.read_bytes())), self.assertRaisesRegex(
                    ValueError, 'exact model method'):
                proof.verify(old, new, Path('javap'))

    def test_additional_bean_attribute_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = self.fixtures(Path(tmp), new_options={
                'bean': [('destroyMethod', ('s', '')), ('autowireCandidate', ('s', 'false'))]})
            with patch.object(proof, 'BASELINE', proof.sha(old.read_bytes())), self.assertRaisesRegex(
                    ValueError, 'single-owner'):
                proof.verify(old, new, Path('javap'))

    def test_unsupported_annotation_binding_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported annotation attribute'):
            proof.annotation_bindings(class_bytes(unsupported=True))

    def test_unknown_annotation_element_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported annotation element'):
            proof.annotation_bindings(class_bytes(bean=[('destroyMethod', ('?', None))]))

    def test_truncated_or_invalid_class_fails_closed(self):
        for data in (b'not a class', class_bytes()[:-1]):
            with self.subTest(data=data[:8]), self.assertRaises(ValueError):
                proof.annotation_bindings(data)

    def test_duplicate_annotation_element_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate annotation element'):
            proof.annotation_bindings(class_bytes(bean=[('destroyMethod', ('s', '')), ('destroyMethod', ('s', ''))]))

    def test_modified_utf8_nul_and_surrogates_and_invalid_sequences(self):
        self.assertEqual('\x00\ud83d\ude00', proof._modified_utf8(b'\xc0\x80\xed\xa0\xbd\xed\xb8\x80'))
        for raw in (b'\x00', b'\xc1\x81', b'\xe0\x80\x80', b'\xf0\x9f\x98\x80', b'\xc0'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                proof._modified_utf8(raw)

    def test_real_javac_nested_arrays_enum_class_and_primitive_annotations_normalize_indices(self):
        executable = 'javac.exe' if os.name == 'nt' else 'javac'
        java_home = os.environ.get('JAVA_HOME')
        javac = str(Path(java_home) / 'bin' / executable) if java_home else shutil.which(executable)
        self.assertTrue(javac and Path(javac).is_file(), 'JDK 21 javac is required for the real class-file fixture')
        declarations = '''package fixture;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @interface Inner { String value(); }
@Retention(RetentionPolicy.CLASS) @Target({ElementType.TYPE,ElementType.FIELD,ElementType.METHOD})
@interface Hidden { String value(); }
enum Choice { OK }
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE,ElementType.FIELD,ElementType.METHOD})
@interface Sample {
 String text(); int[] numbers(); Inner inner(); Choice kind(); Class<?> type();
 boolean flag(); byte tiny(); short small(); char symbol(); long large(); float score(); double price();
}
'''
        pairs = ['text="嵌入\\u0000\\uD83D\\uDE00"', 'numbers={1,2}', 'inner=@Inner("嵌套")',
                 'kind=Choice.OK', 'type=String.class', 'flag=true', 'tiny=3', 'small=4',
                 "symbol='中'", 'large=9L', 'score=0.25f', 'price=-0.5d']
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'SmallFixture.java'
            parsed = []
            for index, values in enumerate((pairs, list(reversed(pairs)))):
                annotation = '@Hidden("opaque") @Sample(' + ','.join(values) + ')'
                source.write_text(declarations + annotation + '\npublic class SmallFixture {\n'
                                  + annotation + ' private String text;\n'
                                  + annotation + ' public void cleanup() {}\n}\n', encoding='utf-8')
                output = root / str(index)
                subprocess.run([javac, '--release', '21', '-encoding', 'UTF-8', '-d', str(output), str(source)],
                               check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
                parsed.append(proof.annotation_bindings((output / 'fixture/SmallFixture.class').read_bytes()))
            self.assertEqual(parsed[0], parsed[1])
            self.assertEqual(4, len(parsed[0]))
            sample = dict(parsed[0][('class', 'fixture/SmallFixture')])['RuntimeVisibleAnnotations'][0]
            self.assertEqual('Lfixture/Sample;', sample[0])
            self.assertEqual(12, len(sample[1]))
            hidden = dict(parsed[0][('class', 'fixture/SmallFixture')])['RuntimeInvisibleAnnotations'][0]
            self.assertEqual(('Lfixture/Hidden;', (('value', ('s', 'opaque')),)), hidden)


if __name__ == '__main__':
    unittest.main()
