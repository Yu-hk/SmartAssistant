import json
import unittest
from profile_control_checkpoint import encode, verify, sha256, paused_tombstones


class ControlCheckpointTest(unittest.TestCase):
    source='c3908c83-9e7c-44d8-943f-1f6ebad5c85c'
    rows=[{'user_id': 10, 'generation': 2, 'analysis_enabled': False},
          {'user_id': 20, 'generation': 0, 'analysis_enabled': True}]

    def data(self, rows=None, at=100): return encode(self.source,'a'*64,at,self.rows if rows is None else rows)
    def checked(self,data,**overrides):
        args=dict(expected_sha256=sha256(data),expected_source_id=self.source,
                  expected_backup_sha256='a'*64,now=120,max_age_seconds=30)
        args.update(overrides); return verify(data,**args)

    def test_roundtrip_is_minimal_and_order_stable(self):
        data=self.data(); self.assertEqual(data,self.data(list(reversed(self.rows))))
        self.assertEqual([{'user_id':10,'generation':2}],paused_tombstones(self.checked(data)))
        self.assertEqual({'schema','source_id','data_backup_sha256','captured_at','controls'},set(self.checked(data)))

    def test_tampering_or_missing_trusted_digest_is_rejected(self):
        original=self.data(); modified=original.replace(b'"generation":2',b'"generation":1')
        for digest in (None,'','0'*64,sha256(original)):
            with self.subTest(digest=digest),self.assertRaises(ValueError):self.checked(modified,expected_sha256=digest)

    def test_older_valid_or_incomplete_control_artifact_fails_current_pin(self):
        newest=self.data()
        for old in (self.data(at=90),self.data([self.rows[1]])):
            with self.assertRaises(ValueError):self.checked(old,expected_sha256=sha256(newest))

    def test_wrong_source_backup_stale_and_future_rejected(self):
        for options in ({'expected_source_id':'different'}, {'expected_backup_sha256':'b'*64},
                        {'now':131},{'now':99},{'max_age_seconds':0},{'now':True}):
            with self.subTest(options=options),self.assertRaises(ValueError):self.checked(self.data(),**options)

    def test_malformed_controls_rejected(self):
        for rows in ([],self.rows*2,[dict(self.rows[0], user_id=True)],
                     [dict(self.rows[0],generation=-1)],[dict(self.rows[0],generation=0)],
                     [dict(self.rows[0],analysis_enabled=1)],[dict(self.rows[0],secret='not allowed')]):
            with self.subTest(rows=rows),self.assertRaises(ValueError):self.data(rows)

    def test_unknown_or_duplicate_fields_rejected_even_with_matching_digest(self):
        data=self.data()
        for altered in (data.replace(b'"schema":1',b'"schema":1,"schema":1'),
                        data.replace(b'"schema":1',b'"schema":1,"unexpected":"private"')):
            with self.assertRaises(ValueError):self.checked(altered)

    def test_reopened_generation_is_not_blindly_paused_or_ignored(self):
        reopened=[dict(self.rows[0],analysis_enabled=True),self.rows[1]]
        value=self.checked(self.data(reopened))
        with self.assertRaises(ValueError):paused_tombstones(value)


if __name__=='__main__':unittest.main()
