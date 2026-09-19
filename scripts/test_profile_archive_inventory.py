import io
import gzip
import json
import pathlib
import tarfile
import tempfile
import time
import unittest
import zipfile
import zlib
from unittest.mock import patch
from profile_archive_inventory import inspect_archive, classify, inventory, BoundedReader, InventoryLimit


class ArchiveInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.temp.name)

    def tearDown(self): self.temp.cleanup()

    def tar(self, name='fixture.tar.gz', unsafe=False):
        path = self.root / name
        with tarfile.open(str(path), 'w:gz' if name.endswith('.gz') else 'w') as archive:
            for member in ('users/private-user-memory.md', 'backup/data.sql', 'logs/private.log', 'application.yml'):
                item = tarfile.TarInfo(member); data = b'private-canary-secret'; item.size = len(data)
                archive.addfile(item, io.BytesIO(data))
            if unsafe:
                item = tarfile.TarInfo('../escape'); archive.addfile(item)
                item = tarfile.TarInfo('link'); item.type = tarfile.SYMTYPE; item.linkname = '/etc/passwd'
                archive.addfile(item)
        return path

    def test_tar_and_gzip_only_report_categories_without_extraction(self):
        for name in ('fixture.tar', 'fixture.tar.gz'):
            row = inspect_archive(self.tar(name))
            self.assertEqual('CATALOG_ONLY', row['status']); self.assertTrue(row['catalogComplete'])
            self.assertEqual(4, row['membersInspected'])
            self.assertEqual(1, row['categories']['profileOrMemoryName'])
            self.assertNotIn('private', json.dumps(row)); self.assertFalse((self.root/'users').exists())

    def test_unsafe_paths_and_links_are_blocked_not_followed(self):
        row = inspect_archive(self.tar(unsafe=True))
        self.assertEqual('BLOCKED_UNSAFE_OR_ENCRYPTED', row['status'])
        self.assertEqual(1, row['unsafeMembers']); self.assertEqual(1, row['linkOrSpecialMembers'])
        self.assertFalse((self.root/'escape').exists())

    def test_zip_lists_headers_without_opening_member_bodies(self):
        path = self.root/'fixture.zip'
        with zipfile.ZipFile(str(path), 'w') as archive:
            archive.writestr('dump.rdb', 'private-canary')
            archive.writestr('nested.tar.gz', 'not expanded')
        with patch.object(zipfile.ZipFile, 'open', side_effect=AssertionError('Payload read')):
            row = inspect_archive(path)
        self.assertEqual('CATALOG_ONLY', row['status'])
        self.assertEqual({'redisPersistence': 1, 'nestedArchive': 1}, row['categories'])

    def test_invalid_or_plain_gzip_is_not_claimed_as_clean_catalog(self):
        path = self.root/'broken.tar.gz'; path.write_bytes(b'not gzip')
        self.assertEqual('BLOCKED_UNREADABLE_OR_UNSUPPORTED', inspect_archive(path)['status'])
        path.write_bytes(gzip.compress(b'plain SQL or private body, not a TAR'))
        self.assertEqual('BLOCKED_UNREADABLE_OR_UNSUPPORTED', inspect_archive(path)['status'])

    def test_encrypted_zip_flag_is_explicitly_blocked(self):
        path = self.root/'encrypted.zip'
        with zipfile.ZipFile(str(path), 'w') as archive: archive.writestr('record.sql', 'private')
        content = bytearray(path.read_bytes()); index = content.index(b'PK\x01\x02')
        content[index+8] |= 1; path.write_bytes(content)
        row = inspect_archive(path)
        self.assertEqual('BLOCKED_UNSAFE_OR_ENCRYPTED', row['status'])
        self.assertEqual(1, row['encryptedMembers'])

    def test_symlink_input_is_not_followed(self):
        path = self.tar()
        with patch.object(pathlib.Path, 'is_symlink', return_value=True):
            self.assertEqual('BLOCKED_SYMLINK', inspect_archive(path)['status'])

    def test_deflate_corruption_returns_blocked_without_exception_or_payload(self):
        path = self.tar()
        with patch('gzip.GzipFile.read', side_effect=zlib.error('private-corrupt-input')):
            row = inspect_archive(path)
        self.assertEqual('BLOCKED_UNREADABLE_OR_UNSUPPORTED', row['status'])
        self.assertFalse(row['catalogComplete']); self.assertNotIn('private', json.dumps(row))

    def test_limits_preserve_partial_status(self):
        path = self.tar()
        for key, value, status in [('MAX_INPUT', 1, 'BLOCKED_INPUT_SIZE'),
                                   ('MAX_MEMBERS', 1, 'BLOCKED_INSPECTION_LIMIT'),
                                   ('MAX_EXPANDED', 1, 'BLOCKED_INSPECTION_LIMIT')]:
            with self.subTest(key=key), patch('profile_archive_inventory.'+key, value):
                row = inspect_archive(path); self.assertEqual(status, row['status'])
                self.assertFalse(row['catalogComplete'])

    def test_zip_directory_limit_before_directory_allocation(self):
        path = self.root/'large.zip'
        with zipfile.ZipFile(str(path), 'w') as archive: archive.writestr('a', '')
        with patch('profile_archive_inventory.MAX_ZIP_DIRECTORY', 1):
            self.assertEqual('BLOCKED_INSPECTION_LIMIT', inspect_archive(path)['status'])

    def test_byte_and_time_budget(self):
        reader = BoundedReader(io.BytesIO(b'payload'), 3, time.monotonic()+10)
        self.assertEqual(b'pay', reader.read(3))
        with self.assertRaises(InventoryLimit): reader.read(1)
        with self.assertRaises(InventoryLimit): BoundedReader(io.BytesIO(), 100, 0).read(1)

    def test_scope_rejects_other_roots(self):
        with self.assertRaises(ValueError): inventory(self.root)
        self.assertIn('profileOrMemoryName', classify('arbitrary/PREFERENCE.json'))


if __name__ == '__main__': unittest.main()
