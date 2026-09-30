import json
import pathlib
import tempfile
import unittest
import zipfile
from unittest.mock import patch as mock_patch
from types import SimpleNamespace
from build_result_merger_patch import CLASSES, PREFIX, digest, patch


class ResultMergerPatchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.live, self.built = self.root / 'live.jar', self.root / 'built.jar'
        self.guard, self.output = self.root / 'guard.json', self.root / 'scoped.jar'
        with zipfile.ZipFile(self.live, 'w') as jar:
            for name in CLASSES:
                jar.writestr(PREFIX + name, b'old')
            jar.writestr('BOOT-INF/classes/order.class', b'live-only-order')
            jar.writestr('BOOT-INF/lib/common.jar', b'live-common')
        with zipfile.ZipFile(self.built, 'w') as jar:
            for name in CLASSES:
                jar.writestr(PREFIX + name, b'new')
            jar.writestr('BOOT-INF/lib/common.jar', b'changed-do-not-copy')
        self.guard.write_text(json.dumps({PREFIX + n: digest(b'old') for n in CLASSES}))

    def test_only_selected_classes_change(self):
        patch(self.live, self.built, self.guard, self.output)
        with zipfile.ZipFile(self.output) as jar:
            self.assertEqual(jar.read('BOOT-INF/classes/order.class'), b'live-only-order')
            self.assertEqual(jar.read('BOOT-INF/lib/common.jar'), b'live-common')
            self.assertEqual(jar.read(PREFIX + CLASSES[0]), b'new')

    def test_drift_rejected_before_output(self):
        self.guard.write_text(json.dumps({PREFIX + n: digest(b'wrong') for n in CLASSES}))
        with self.assertRaisesRegex(RuntimeError, 'baseline class differs'):
            patch(self.live, self.built, self.guard, self.output)
        self.assertFalse(self.output.exists())

    def test_never_overwrites_artifact(self):
        with self.assertRaisesRegex(RuntimeError, 'new, separate'):
            patch(self.live, self.built, self.guard, self.live)

    def test_guard_cannot_expand_scope(self):
        self.guard.write_text(json.dumps({'BOOT-INF/classes/order.class': digest(b'old')}))
        with self.assertRaisesRegex(RuntimeError, 'guard set'):
            patch(self.live, self.built, self.guard, self.output)


class CheckedContainerStopTest(unittest.TestCase):
    def test_cli_error_requires_confirmed_exited_state(self):
        import release_multi_product_merge_20260930 as release
        result = SimpleNamespace(returncode=1, stdout=b'', stderr=b'libpod internal error')
        with mock_patch.object(release.subprocess, 'run', return_value=result), mock_patch.object(
                release, 'original_run', return_value=json.dumps([{'State': {'Running': False, 'Status': 'exited'}}])):
            self.assertEqual(release.checked_run('docker', 'stop', 'smart-router'), 'smart-router')

    def test_running_container_is_not_treated_as_stopped(self):
        import release_multi_product_merge_20260930 as release
        result = SimpleNamespace(returncode=1, stdout=b'', stderr=b'internal error')
        with mock_patch.object(release.subprocess, 'run', return_value=result), mock_patch.object(
                release, 'original_run', return_value=json.dumps([{'State': {'Running': True, 'Status': 'running'}}])):
            with self.assertRaisesRegex(RuntimeError, 'not confirmed'):
                release.checked_run('docker', 'stop', 'smart-router')

    def test_permission_denial_is_never_bypassed(self):
        import release_multi_product_merge_20260930 as release
        result = SimpleNamespace(returncode=1, stdout=b'', stderr=b'Permission denied')
        with mock_patch.object(release.subprocess, 'run', return_value=result), mock_patch.object(release, 'original_run') as run:
            with self.assertRaisesRegex(RuntimeError, 'permission failure'):
                release.checked_run('docker', 'stop', 'smart-router')
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
