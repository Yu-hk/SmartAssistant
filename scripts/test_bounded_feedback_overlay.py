import hashlib, json, tempfile, unittest, zipfile
from pathlib import Path
from unittest.mock import patch
import apply_bounded_feedback_overlay as overlay

class ScopedOverlayTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name); self.source=self.root/'baseline.jar'; self.output=self.root/'candidate.jar'
        self.old={'BOOT-INF/classes/'+overlay.PREFIX+n for n in overlay.OLD}
        self.new={'BOOT-INF/classes/'+overlay.PREFIX+n for n in overlay.NEW}
        with zipfile.ZipFile(self.source,'w') as z:
            for name in self.old: z.writestr(name,b'old')
            z.writestr('BOOT-INF/classes/application.yml',b'production-only-config')
            z.writestr('BOOT-INF/lib/common.jar',b'production-only-library')
        self.sha=hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.guard={'baseline':self.sha,'replace':{n:hashlib.sha256(b'old').hexdigest() for n in self.old},'add':list(self.new)}
        self.write_package()
    def write_package(self, extra=None):
        (self.root/'guard.json').write_text(json.dumps(self.guard))
        with zipfile.ZipFile(self.root/'replacement.zip','w') as z:
            for n in self.old | self.new: z.writestr(n,b'new-different-length')
            if extra: z.writestr(extra,b'bad')
    def apply(self):
        with patch.object(overlay,'BASELINE',self.sha): return overlay.apply(self.source,self.root,self.output)
    def test_exact_scoped_overlay_preserves_live_only_resources(self):
        result=self.apply(); self.assertTrue(result['unrelated_entries_preserved'])
        with zipfile.ZipFile(self.output) as z:
            self.assertEqual(b'production-only-config',z.read('BOOT-INF/classes/application.yml'))
            self.assertEqual(b'production-only-library',z.read('BOOT-INF/lib/common.jar'))
        with self.assertRaises(FileExistsError): self.apply()
    def test_bad_baseline_rejected_before_output(self):
        self.source.write_bytes(b'changed')
        with self.assertRaises(ValueError): self.apply()
        self.assertFalse(self.output.exists())
    def test_unreviewed_class_and_path_escape_rejected(self):
        for name in ['BOOT-INF/classes/Unrelated.class','../escape']:
            self.write_package(name)
            with self.assertRaises(ValueError): self.apply()
            self.assertFalse(self.output.exists())
    def test_bad_class_hash_rejected(self):
        self.guard['replace'][next(iter(self.old))]='0'*64; self.write_package()
        with self.assertRaises(ValueError): self.apply()
    def test_guard_cannot_expand_reviewed_scope(self):
        self.guard['add'].append('BOOT-INF/classes/Extra.class'); self.write_package()
        with self.assertRaises(ValueError): self.apply()
if __name__=='__main__': unittest.main()
