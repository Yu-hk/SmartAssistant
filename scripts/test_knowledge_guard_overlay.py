import io
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
import build_knowledge_guard_overlay as overlay


class KnowledgeGuardOverlayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source, self.output = self.root / 'baseline.jar', self.root / 'candidate.jar'
        self.common, self.product = self.root / 'common', self.root / 'product'
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, 'w') as jar:
            jar.writestr(overlay.GUARD, b'old-guard')
            jar.writestr('other.class', b'live-only-common')
        with zipfile.ZipFile(self.source, 'w') as jar:
            jar.writestr(overlay.COMMON, inner.getvalue())
            jar.writestr(overlay.QUALITY_ENTRY, b'old-quality')
            jar.writestr('BOOT-INF/classes/application.yml', b'live-only-model-settings')
            jar.writestr('BOOT-INF/lib/other.jar', b'other-library')
        for root, name in ((self.common, overlay.GUARD), (self.product, overlay.QUALITY)):
            target = root / name
            target.parent.mkdir(parents=True)
            target.write_bytes(b'\xca\xfe\xba\xbe\x00\x00\x00\x41new-class')
        self.sha = overlay.sha(self.source.read_bytes())

    def build(self):
        with patch.object(overlay, 'BASELINE', self.sha):
            return overlay.build(self.source, self.common, self.product, self.output)

    def test_only_two_classes_change(self):
        receipt = self.build()
        self.assertEqual(2, receipt['classes_replaced'])
        self.assertTrue(receipt['unrelated_inner_and_outer_entries_preserved'])
        with zipfile.ZipFile(self.output) as jar:
            self.assertEqual(b'live-only-model-settings', jar.read('BOOT-INF/classes/application.yml'))
            self.assertEqual(b'other-library', jar.read('BOOT-INF/lib/other.jar'))
            with zipfile.ZipFile(io.BytesIO(jar.read(overlay.COMMON))) as inner:
                self.assertEqual(b'live-only-common', inner.read('other.class'))
                self.assertTrue(inner.read(overlay.GUARD).startswith(b'\xca\xfe\xba\xbe'))

    def test_no_existing_candidate_overwrite(self):
        self.build()
        before = self.output.read_bytes()
        with self.assertRaises(FileExistsError):
            self.build()
        self.assertEqual(before, self.output.read_bytes())

    def test_baseline_mismatch(self):
        with self.assertRaises(ValueError):
            overlay.build(self.source, self.common, self.product, self.output)
        self.assertFalse(self.output.exists())

    def test_bad_or_newer_class_rejected(self):
        for data in (b'not-a-class', b'\xca\xfe\xba\xbe\x00\x00\x00\x42'):
            (self.common / overlay.GUARD).write_bytes(data)
            with self.assertRaises(ValueError):
                self.build()
            self.assertFalse(self.output.exists())

    def test_missing_entry_and_duplicate_rejected(self):
        for duplicate in (False, True):
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, 'w') as jar:
                jar.writestr('original', b'value')
                if duplicate:
                    jar.writestr('original', b'changed')
            with zipfile.ZipFile(io.BytesIO(buf.getvalue())) as jar:
                with self.assertRaises(ValueError):
                    overlay.rewrite(jar, {overlay.GUARD: b'new'})


if __name__ == '__main__':
    unittest.main()
