import hashlib
import os
import pathlib
import tempfile
import unittest
import uuid
from unittest.mock import patch
from profile_control_recovery import journal,replay_sql
import profile_control_recovery as recovery


class RecoverySqlTest(unittest.TestCase):
    def setUp(self):
        self.source=str(uuid.uuid4())
        self.row=dict(sequence=1,event_id=str(uuid.uuid4()),source_id=self.source,user_id=91001,generation=1,analysis_enabled=False)
    def test_production_cli_rejects_missing_or_unknown_baseline_before_control_or_database_access(self):
        base=['recovery','--control-dir','/unread-control','--expected-source-id',self.source,
              '--offline-production','--apply-container','smart-postgres']
        for suffix,error in [([],SystemExit),(['--baseline','unknown','--backup','/unread-backup'],ValueError)]:
            with patch('sys.argv',base+suffix),patch.object(recovery,'command') as command,patch.object(recovery,'small') as read:
                with self.assertRaises(error):recovery.main()
                command.assert_not_called();read.assert_not_called()
    def test_recovery_retains_accounts_and_admissions_but_reopens_all_cleanup_receipts(self):
        sql=replay_sql(self.source,[self.row])
        self.assertIn('llm_received_question=NULL',sql)
        self.assertIn("SET state='PENDING'",sql)
        self.assertIn("Missing historical owners require allocator review",sql)
        self.assertNotIn('DELETE FROM users',sql)
        self.assertNotIn('DELETE FROM profile_request_admission',sql)
        self.assertNotIn('DELETE FROM routing_call_log',sql)
        self.assertNotIn('TRUNCATE',sql)
    def test_invalid_or_reopened_stream_cannot_silently_delete_newer_profiles(self):
        for row in [dict(self.row,user_id=True),dict(self.row,event_id="'); DROP TABLE users; --"),dict(self.row,sequence=2),dict(self.row,generation=2)]:
            with self.assertRaises(ValueError):replay_sql(self.source,[row])
        reopened=dict(self.row,sequence=2,generation=2,event_id=str(uuid.uuid4()),analysis_enabled=True)
        with self.assertRaises(ValueError):replay_sql(self.source,[self.row,reopened])
    @unittest.skipUnless(os.name=='posix','Requires actual POSIX permissions')
    def test_current_runtime_format_integrity_permissions_and_published_suffix(self):
        with tempfile.TemporaryDirectory(prefix='profile-recovery-fixture-') as directory:
            root=pathlib.Path(directory).resolve();root.chmod(0o700)
            def put(name,data):
                file=root/name;file.write_bytes(data);file.chmod(0o600)
            zero='0'*64
            data=('1|%s|1|%s|91001|1|0|%s\n'%(self.source,self.row['event_id'],zero)).encode()
            put('source.pin',('1|'+self.source+'\n').encode());put('writer.lock',b'');put('00000000000000000001.ctl',data)
            put('HEAD',('1|0|'+zero+'\n').encode())
            self.assertEqual((self.source,[self.row]),journal(root,self.source))
            put('HEAD',('1|1|'+hashlib.sha256(data).hexdigest()+'\n').encode())
            self.assertEqual((self.source,[self.row]),journal(root,self.source))
            put('00000000000000000001.ctl',data.replace(b'91001',b'91002'))
            with self.assertRaises(ValueError):journal(root,self.source)
            put('00000000000000000001.ctl',data);(root/'source.pin').chmod(0o644)
            with self.assertRaises(ValueError):journal(root,self.source)


if __name__=='__main__':unittest.main()
