import pathlib
import tempfile
import unittest
from unittest.mock import patch
import recovery_baseline as baseline


class BaselineTests(unittest.TestCase):
    def test_repository_baseline_and_crlf_checkout(self):
        repo = pathlib.Path(__file__).resolve().parents[1]
        self.assertEqual(baseline.verify_repository(repo), baseline.REPOSITORY_BASELINE)
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for name in baseline.SQL_SHA256:
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((repo / name).read_bytes().replace(b'\r\n', b'\n').replace(b'\n', b'\r\n'))
            baseline.verify_repository(root)
            path.write_bytes(path.read_bytes() + b'-- drift')
            with self.assertRaises(ValueError): baseline.verify_repository(root)

    def test_unknown_baseline_fails_before_reading_any_backup(self):
        with patch.object(pathlib.Path, 'open') as opened:
            with self.assertRaises(ValueError): baseline.verify_backup(pathlib.Path('missing'), 'unknown')
            opened.assert_not_called()

    def test_not_a_pg_dump_or_wrong_digest_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'backup.dump'
            for value in (b'not-pg', b'PGDMPsynthetic-unapproved'):
                path.write_bytes(value)
                with self.assertRaises(ValueError): baseline.verify_backup(path, baseline.PRODUCTION_BASELINE)
            with self.assertRaises(ValueError): baseline.verify_backup(path.parent, baseline.PRODUCTION_BASELINE)

    def test_approved_digest_compared_to_independent_constant(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'backup.dump'; path.write_bytes(b'PGDMPsynthetic')
            import hashlib
            with patch.object(baseline, 'BACKUP_SHA256', hashlib.sha256(path.read_bytes()).hexdigest()):
                self.assertEqual(baseline.verify_backup(path, baseline.PRODUCTION_BASELINE), baseline.PRODUCTION_BASELINE)


if __name__ == '__main__': unittest.main()
