import json
import os
import stat
from types import SimpleNamespace
import pathlib
import tempfile
import unittest
from unittest.mock import patch
from profile_storage_inventory import allowed, scan_tree, storage_plan, inventory


class StorageInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.temp.name).resolve()
        self.scope = patch('profile_storage_inventory.ALLOWED_ROOTS', (str(self.root),)); self.scope.start()
        self.layer = self.root/'diff'; self.layer.mkdir()

    def tearDown(self): self.scope.stop(); self.temp.cleanup()

    def test_counts_old_format_anywhere_without_body_or_member_name(self):
        folder=self.layer/'custom-path'/'private-user'; folder.mkdir(parents=True)
        (folder/'order-memory.md').write_text('private-preference')
        (folder/'preferences.json').write_text('private-profile')
        with patch.object(pathlib.Path, 'open', side_effect=AssertionError('Payload read')):
            row=scan_tree(self.layer)
        self.assertEqual('METADATA_ENUMERATED', row['status']); self.assertEqual(2,row['memoryFileCandidates'])
        self.assertNotIn('private', json.dumps(row))

    def test_broad_or_external_paths_rejected(self):
        self.assertFalse(allowed(self.root)); self.assertFalse(allowed(pathlib.Path('/')))
        self.assertEqual('BLOCKED_PATH', scan_tree(self.root)['status'])

    def test_limits_never_claim_completion(self):
        (self.layer/'x').write_text('fixture')
        with patch('profile_storage_inventory.MAX_ENTRIES',0):
            self.assertEqual('BLOCKED_LIMIT',scan_tree(self.layer)['status'])
        with patch('profile_storage_inventory.MAX_SECONDS',-1):
            self.assertEqual('BLOCKED_LIMIT',scan_tree(self.layer)['status'])

    def test_missing_path_and_unreadable_directory_are_explicit(self):
        self.assertEqual('BLOCKED_UNAVAILABLE',scan_tree(self.layer/'missing')['status'])
        with patch('os.scandir',side_effect=PermissionError('private-path')):
            row=scan_tree(self.layer)
        self.assertEqual('BLOCKED_IO',row['status']); self.assertNotIn('private',json.dumps(row))

    def test_regular_mount_only_stat(self):
        file=self.layer/'app.jar';file.write_bytes(b'not opened')
        self.assertEqual('REGULAR_FILE_METADATA_ONLY',scan_tree(file)['status'])

    def test_symlink_candidate_not_followed(self):
        file=self.layer/'order-memory.md';file.write_text('fixture')
        original=os.lstat
        def metadata(path,*args,**kwargs):
            if str(path)==str(file):return SimpleNamespace(st_mode=stat.S_IFLNK,st_dev=0)
            return original(path,*args,**kwargs)
        with patch('os.lstat',side_effect=metadata):row=scan_tree(self.layer)
        self.assertEqual(0,row['memoryFileCandidates']);self.assertEqual(1,row['memoryLinkCandidates'])
        self.assertEqual(1,row['symlinksNotFollowed'])

    def test_overlay_plan_includes_lower_upper_and_mount_not_work_or_merged(self):
        meta={'GraphDriver':{'Name':'overlay','Data':{'UpperDir':'/upper','LowerDir':'/a:/b',
              'MergedDir':'/merged','WorkDir':'/work'}},'Mounts':[{'Type':'bind','Source':'/mount'}]}
        self.assertEqual([('writable-layer','/upper'),('image-layer','/a'),('image-layer','/b'),('mount','/mount')],storage_plan(meta))

    def test_stopped_containers_only_use_ps_and_inspect_with_shared_layers_deduplicated(self):
        names=['smart-consumer-old','smart-consumer']
        def command(*args):
            if args[1]=='ps':return '\n'.join(names+['unrelated-database'])
            self.assertEqual(('docker','inspect'),args[:2])
            name=args[2].replace('id-','')
            return json.dumps([{'Id':'id-'+name,'Image':'image','State':{'Running':False},
               'Config':{'Env':['PASSWORD=private-secret']},
               'GraphDriver':{'Name':'overlay','Data':{'UpperDir':str(self.layer)}},'Mounts':[]}])
        with patch('profile_storage_inventory.run',side_effect=command): result=inventory()
        self.assertEqual(2,len(result['containers']));self.assertEqual(1,len(result['storageRoots']))
        self.assertTrue(result['containerSetStable']);self.assertNotIn('private',json.dumps(result))


if __name__=='__main__':unittest.main()
