import pathlib
import tempfile
import unittest
import database_upgrade_contract as contract


class UpgradeContractTests(unittest.TestCase):
    def test_inventory_complete_and_explicit_dependencies(self):
        repo = pathlib.Path(__file__).resolve().parents[1]
        inputs = contract.load_inputs(repo)
        self.assertEqual(len(inputs), 29)
        for before, after in [('20260914_add_product_structured_features', '20260914_add_product_intake'),
                              ('20260918_add_profile_lifecycle', '20260919_add_profile_control_archive'),
                              ('20260827_add_workflow_recovery_jobs', '20260827_add_workflow_recovery_result')]:
            self.assertLess(contract.ORDER.index(before), contract.ORDER.index(after))
        for path, data in inputs.items():
            sql = contract.migration_sql(pathlib.Path(path).stem, data)
            self.assertIn('pg_advisory_xact_lock', sql)
            self.assertIn('Owned bootstrap fixture database required', sql)
            self.assertIn('Applied migration checksum changed', sql)

    def test_inventory_does_not_silently_skip_new_sql(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = pathlib.Path(folder) / 'docs/database/migrations'
            directory.mkdir(parents=True)
            with self.assertRaises(ValueError): contract.load_inputs(pathlib.Path(folder))

    def test_visit_migration_matches_runtime_schema(self):
        repo = pathlib.Path(__file__).resolve().parents[1]
        migration = (repo / 'docs/database/migrations/20260923_add_site_visits.sql').read_text(encoding='utf-8')
        runtime = (repo / 'smart-assistant-consumer/src/main/resources/db/visit-records.sql').read_text(encoding='utf-8')
        def statements(text):
            return '\n'.join(line.strip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith('--'))
        self.assertEqual(statements(migration), statements(runtime))

    def test_transaction_wrappers_are_explicit_not_arbitrary_stripping(self):
        name = '20260914_add_product_intake'
        self.assertEqual(contract.body(name, b'-- reviewed\nBEGIN; SELECT 1; COMMIT;'), '-- reviewed\nSELECT 1;')
        for data in [b'SELECT 1;', b'BEGIN; COMMIT; BEGIN; SELECT 1; COMMIT;', b'BEGIN; SELECT 1; COMMIT; SELECT 2;']:
            with self.assertRaises(ValueError): contract.body(name, data)

    def test_unknown_migration_rejected(self):
        with self.assertRaises(ValueError): contract.migration_sql("x';drop table users;--", b'SELECT 1;')

    def test_payload_quoted_and_raw_bytes_hashed(self):
        name = contract.ORDER[0]
        statement = contract.migration_sql(name, b"SELECT 'quoted'; -- fixture\r\n")
        self.assertIn("EXECUTE 'SELECT ''quoted''; -- fixture\n'", statement)
        self.assertNotEqual(statement, contract.migration_sql(name, b"SELECT 'quoted'; -- fixture\n"))

    def test_wrong_error_cannot_count_as_expected_rejection(self):
        def wrong(sql): raise RuntimeError('Fixture command failed SQLSTATE=42703')
        with self.assertRaises(RuntimeError): contract.reject(wrong, 'sql', '23505')
        with self.assertRaises(AssertionError): contract.reject(lambda sql: '', 'sql', '23505')

    def test_require_works_without_python_assertions(self):
        with self.assertRaises(AssertionError): contract.require('bad', 'expected', 'failed')

    def test_behavior_drives_expected_rejections_through_callback(self):
        calls = []
        def sql(statement):
            calls.append(statement)
            state = next((state for marker, state in [
                ("'fixture-running','ACTIVE_IDLE'", '23505'), ('weight_grams=-1', '23514'),
                ("'missing-order'", '23503'), ('workflow_versions', '23514'),
                ('SET ROLE fixture_reader; UPDATE', '42501'),
                ('SET ROLE fixture_reader; SELECT * FROM users', '42501')]
                if marker in statement), None)
            if state: raise RuntimeError('Fixture command failed SQLSTATE=' + state)
            return '2' if "'parallel-conversation'" in statement else 't'
        self.assertTrue(contract.behavior(sql)['writeAndUserReadDenied'])
        self.assertEqual(len(calls), 9)


if __name__ == '__main__': unittest.main()
