import unittest
from profile_restore_drill import barrier_sql, PAYLOAD_TABLES

class RestoreBarrierGuards(unittest.TestCase):
    def test_rejects_missing_malformed_or_duplicate_ledger(self):
        for ledger in (None, [], {}, [{'user_id': 1}], [{'user_id': 1, 'generation': 0}],
                       [{'user_id': True, 'generation': 1}], [{'user_id': '1; DROP TABLE users', 'generation': 1}],
                       [{'user_id': 1, 'generation': 1}] * 2, [{'user_id': 2**63, 'generation': 1}]):
            with self.subTest(ledger=ledger), self.assertRaises(ValueError): barrier_sql(ledger)

    def test_barrier_is_offline_atomic_allowlisted_and_retains_control_data(self):
        sql = barrier_sql([{'user_id': 42, 'generation': 2}])
        self.assertTrue(sql.startswith('BEGIN;'))
        self.assertTrue(sql.endswith('COMMIT;'))
        self.assertIn("current_database() <> 'profile_restore_drill_restored'", sql)
        self.assertIn('l.generation>t.generation', sql)
        for table in PAYLOAD_TABLES: self.assertIn('DELETE FROM public.' + table + ' p USING', sql)
        for table in ('users', 'profile_lifecycle', 'profile_request_admission', 'routing_call_log'):
            self.assertNotIn('DELETE FROM public.' + table + ' ', sql)

if __name__ == '__main__': unittest.main()
