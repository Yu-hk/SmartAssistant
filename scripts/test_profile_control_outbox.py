import pathlib
import unittest
from unittest.mock import patch
from profile_control_outbox_drill import await_activity


class OutboxFixtureGuards(unittest.TestCase):
    def test_schema_is_only_an_explicit_empty_synthetic_fixture(self):
        path = pathlib.Path(__file__).parent / 'fixtures/profile_control_outbox.sql'
        text = path.read_text(encoding='utf-8')
        self.assertIn("current_database() <> 'profile_control_outbox_fixture'", text)
        self.assertIn('IF EXISTS (SELECT 1 FROM public.profile_lifecycle)', text)
        self.assertLess(text.index('Synthetic control database required'), text.index('CREATE TABLE'))
        self.assertTrue(text.startswith('-- SYNTHETIC ONLY'))
        self.assertIn('BEGIN;', text)
        self.assertTrue(text.rstrip().endswith('COMMIT;'))

    def test_event_payload_is_minimal_and_counter_is_transactional(self):
        text = (pathlib.Path(__file__).parent / 'fixtures/profile_control_outbox.sql').read_text(encoding='utf-8')
        columns = text.split('CREATE TABLE public.profile_control_event (')[1].split('\n);')[0]
        for name in ('sequence', 'event_id', 'source_id', 'user_id', 'generation', 'analysis_enabled'):
            self.assertIn(name, columns)
        for prohibited in ('REFERENCES', 'payload', 'message', 'report', 'password', 'delivered'):
            self.assertNotIn(prohibited, columns)
        self.assertNotIn('nextval(', text.split('CREATE FUNCTION')[1].replace('-- A transactional counter, not nextval(): rollback leaves no sequence hole.', ''))
        self.assertIn('IF NOT FOUND THEN RAISE EXCEPTION', text)

    def test_activity_wait_is_bounded_and_scoped_to_fixture_database(self):
        calls = []
        def sql(query):
            calls.append(query)
            return '0' if len(calls) < 3 else '1'
        with patch('profile_control_outbox_drill.time.sleep'):
            await_activity(sql, "application_name='profile_outbox_a'")
        self.assertEqual(3, len(calls))
        self.assertTrue(all('datname=current_database()' in query for query in calls))
        with patch('profile_control_outbox_drill.time.sleep'), self.assertRaises(AssertionError):
            await_activity(lambda unused: '0', 'false')


if __name__ == '__main__': unittest.main()
