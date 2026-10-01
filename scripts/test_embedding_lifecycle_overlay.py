import io
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
import build_embedding_lifecycle_overlay as overlay


class EmbeddingLifecycleOverlayTest(unittest.TestCase):
    def fixture(self, root):
        source = root / 'baseline.jar'
        with zipfile.ZipFile(source, 'w') as archive:
            archive.writestr(overlay.ENTRY, b'old-class')
            archive.writestr('BOOT-INF/lib/model.jar', b'unchanged dependency')
            archive.writestr('BOOT-INF/classes/application.yml', b'unchanged settings')
        classes = root / 'classes'
        target = classes / overlay.CLASS
        target.parent.mkdir(parents=True)
        target.write_bytes(b'\xca\xfe\xba\xbe\x00\x00\x00\x41new-class')
        return source, classes, root / 'candidate.jar'

    def test_only_exact_class_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, classes, output = self.fixture(Path(tmp))
            with patch.object(overlay, 'BASELINE', overlay.sha(source.read_bytes())):
                result = overlay.build(source, classes, output)
            self.assertEqual(1, result['classes_replaced'])
            self.assertTrue(result['unrelated_entries_preserved'])
            with zipfile.ZipFile(source) as old, zipfile.ZipFile(output) as new:
                self.assertEqual(old.namelist(), new.namelist())
                for name in old.namelist():
                    self.assertEqual(name == overlay.ENTRY, old.read(name) != new.read(name))

    def test_wrong_baseline_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, classes, output = self.fixture(Path(tmp))
            with self.assertRaisesRegex(ValueError, 'baseline drift'):
                overlay.build(source, classes, output)
            self.assertFalse(output.exists())

    def test_incompatible_bytecode_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, classes, output = self.fixture(Path(tmp))
            (classes / overlay.CLASS).write_bytes(b'not a Java 21 class')
            with patch.object(overlay, 'BASELINE', overlay.sha(source.read_bytes())):
                with self.assertRaisesRegex(ValueError, 'Java 21'):
                    overlay.build(source, classes, output)
            self.assertFalse(output.exists())

    def test_existing_output_or_receipt_preserved(self):
        for suffix in ('.jar', '.json'):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as tmp:
                source, classes, output = self.fixture(Path(tmp))
                protected = output.with_suffix(suffix)
                protected.write_bytes(b'prior artifact')
                with self.assertRaises(FileExistsError):
                    overlay.build(source, classes, output)
                self.assertEqual(b'prior artifact', protected.read_bytes())


if __name__ == '__main__':
    unittest.main()
