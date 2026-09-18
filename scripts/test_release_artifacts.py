import copy
import json
import pathlib
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from release_artifacts import collect, digest, inspect, main, validate, verify


class ReleaseArtifactsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = pathlib.Path(temp.name)
        self.jar = self.root / 'app.jar'
        self.jar.write_bytes(b'known-good-jar')
        self.info = dict(image='sha256:' + 'a' * 64, running=True, containerSha256=digest(self.jar)[0], mounts=[
            dict(Type='bind', RW=False, Source=str(self.jar), Destination='/app/app.jar')])
        self.inspector = lambda name: self.info
        self.snapshot = collect(['smart-router'], self.inspector)

    def test_verified_snapshot_contains_no_environment(self):
        self.assertEqual(1, verify(self.snapshot, self.inspector))
        self.assertNotIn('Env', json.dumps(self.snapshot))

    def test_changed_artifact_fails_and_restored_bytes_pass(self):
        old = self.jar.read_bytes()
        self.jar.write_bytes(b'new-release')
        with self.assertRaisesRegex(ValueError, 'sha256'):
            verify(self.snapshot, self.inspector)
        self.jar.write_bytes(old)
        self.assertEqual(1, verify(self.snapshot, self.inspector))

    def test_same_size_corruption_is_detected(self):
        self.jar.write_bytes(b'x' * self.jar.stat().st_size)
        with self.assertRaises(ValueError):
            verify(self.snapshot, self.inspector)

    def test_stopped_service_fails(self):
        self.info['running'] = False
        with self.assertRaisesRegex(ValueError, 'not running'):
            verify(self.snapshot, self.inspector)

    def test_stale_bind_inode_fails_even_if_host_matches_snapshot(self):
        self.info['containerSha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'differs from host'):
            verify(self.snapshot, self.inspector)

    def test_image_drift_fails(self):
        self.info['image'] = 'sha256:' + 'b' * 64
        with self.assertRaisesRegex(ValueError, 'image'):
            verify(self.snapshot, self.inspector)

    def test_full_bare_image_digest_normalizes_but_tags_and_short_ids_fail(self):
        self.info['image'] = 'a' * 64
        self.assertEqual(1, verify(self.snapshot, self.inspector))
        for value in ('latest', 'sha256:abc123', 'abc123'):
            self.info['image'] = value
            with self.assertRaisesRegex(ValueError, 'image identity'):
                collect(['smart-router'], self.inspector)

    def test_changed_mount_path_fails_even_with_identical_bytes(self):
        other = self.root / 'other.jar'
        other.write_bytes(self.jar.read_bytes())
        self.info['mounts'][0]['Source'] = str(other)
        with self.assertRaisesRegex(ValueError, 'artifact'):
            verify(self.snapshot, self.inspector)

    def test_writable_missing_or_duplicate_mount_fails(self):
        for mounts in ([], self.info['mounts'] * 2, [dict(self.info['mounts'][0], RW=True)]):
            with self.subTest(mounts=mounts), self.assertRaises(ValueError):
                collect(['smart-router'], lambda name: dict(self.info, mounts=mounts))

    def test_unknown_schema_and_unexpected_fields_fail(self):
        for snapshot in (dict(self.snapshot, schema=2), dict(self.snapshot, Env='secret'), dict(self.snapshot, services=[])):
            with self.assertRaises(ValueError):
                validate(snapshot)

    def test_invalid_and_duplicate_services_fail_before_docker(self):
        for names in ([], ['smart-router', 'smart-router'], ['--help'], ['smart-router; echo bad']):
            with self.assertRaises(ValueError):
                collect(names, self.inspector)

    def test_manifest_cannot_redirect_file_reads(self):
        tampered = copy.deepcopy(self.snapshot)
        tampered['services'][0]['artifact'] = str(self.root / 'secret')
        with self.assertRaisesRegex(ValueError, 'artifact'):
            verify(tampered, self.inspector)

    def test_empty_file_fails(self):
        self.jar.write_bytes(b'')
        with self.assertRaises(ValueError):
            digest(self.jar)

    def test_inspect_uses_narrow_format_and_no_shell(self):
        with patch('release_artifacts.subprocess.run') as run:
            run.side_effect = [
                SimpleNamespace(returncode=0, stdout=('"' + self.info['image'] + '"|true|' + json.dumps(self.info['mounts'])).encode()),
                SimpleNamespace(returncode=0, stdout=(self.info['containerSha256'] + '  /app/app.jar\n').encode())]
            self.assertEqual(self.info, inspect('smart-router'))
            self.assertNotIn('Env', str(run.call_args_list))
            for call in run.call_args_list:
                self.assertNotIn('shell', call[1])

    def test_cli_never_overwrites_existing_snapshot(self):
        output = self.root / 'snapshot.json'
        output.write_text('original', encoding='utf-8')
        with patch('sys.argv', ['release_artifacts.py', 'snapshot', '--service', 'smart-router', '--output', str(output)]), \
                patch('release_artifacts.collect', return_value=self.snapshot):
            self.assertEqual(1, main())
        self.assertEqual('original', output.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
