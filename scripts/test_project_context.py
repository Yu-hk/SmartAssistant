import json
from pathlib import Path
import shutil
import tempfile
import unittest
from check_project_context import ROOT, expected_context, render


class ProjectContextTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        paths = ['pom.xml', 'frontend/package.json', 'docker-compose-infra.yml', 'ai-project-context.md']
        paths += [str(p.relative_to(ROOT)) for p in ROOT.glob('smart-assistant-*/src/main/resources/application.yml')]
        for name in paths:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)

    def test_current_document_matches(self):
        self.assertEqual(expected_context(self.root), (self.root / 'ai-project-context.md').read_text(encoding='utf-8'))

    def test_version_drift_changes_expected_document(self):
        file = self.root / 'pom.xml'
        file.write_text(file.read_text(encoding='utf-8').replace('<version>4.1.0</version>', '<version>99.0.0</version>', 1), encoding='utf-8')
        self.assertIn('99.0.0', expected_context(self.root))
        self.assertNotEqual(expected_context(self.root), (self.root / 'ai-project-context.md').read_text(encoding='utf-8'))

    def test_rejects_legacy_default(self):
        file = self.root / 'frontend/package.json'
        package = json.loads(file.read_text(encoding='utf-8'))
        package['scripts']['dev'] = 'tsx server/index.ts'
        file.write_text(json.dumps(package), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'legacy'):
            render(self.root)

    def test_missing_markers_fail(self):
        (self.root / 'ai-project-context.md').write_text('missing markers', encoding='utf-8')
        with self.assertRaises(ValueError):
            expected_context(self.root)


if __name__ == '__main__':
    unittest.main()
