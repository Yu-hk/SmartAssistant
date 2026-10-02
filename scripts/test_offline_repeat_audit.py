import copy
import unittest

from audit_offline_repeat import LEGACY_EXACT_COMPARISON_ERROR, validate_measurement


class RepeatAuditTest(unittest.TestCase):
    def setUp(self):
        self.items = [{'module': 'example', 'suites': []}]
        self.modules = [{'module': 'example', 'testcases': {'tests': 1}}]
        self.record = {'status': 'passed', 'maven_exit_code': 0, 'inventory': self.items,
                       'source_fingerprint': 'source', 'policy_sha256': 'policy', 'modules': self.modules}

    def verify(self, record):
        validate_measurement(record, self.items, 'source', 'policy', self.modules)

    def test_valid_success(self):
        self.verify(self.record)

    def test_legacy_comparison_failure_not_maven_failure_can_be_reassessed(self):
        self.record.update(status='failed', error=LEGACY_EXACT_COMPARISON_ERROR)
        self.verify(self.record)
        self.record['maven_exit_code'] = 1
        with self.assertRaises(ValueError):
            self.verify(self.record)

    def test_any_other_failed_status_rejected(self):
        for error in ('source changed during baseline', 'Maven failed', 'missing reports'):
            bad = dict(self.record, status='failed', error=error)
            with self.subTest(error=error), self.assertRaises(ValueError):
                self.verify(bad)

    def test_source_policy_selection_and_raw_counter_mismatch_rejected(self):
        for field in ('source_fingerprint', 'policy_sha256', 'inventory', 'modules'):
            bad = copy.deepcopy(self.record)
            bad[field] = 'tampered'
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.verify(bad)


if __name__ == '__main__':
    unittest.main()
