import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
from unittest.mock import patch
from run_customer_acceptance import DATASET, inspect_answer, run


class CustomerAcceptanceTest(unittest.TestCase):
    def test_repeated_full_answer_requires_failure_review(self):
        answer = '热门商品列表\n1. 耳机1999元\n\n仅代表站内热度'
        events = [{'type': 'response', 'content': answer + '\n\n' + answer}, {'type': 'done'}]
        self.assertFalse(inspect_answer('nonempty', events)['checksPassed'])
    def test_dataset_has_six_turns_without_business_write_commands(self):
        cases = json.loads(DATASET.read_text(encoding='utf-8'))
        self.assertEqual(5, len({c['id'] for c in cases}))
        self.assertEqual(6, sum(len(c['turns']) for c in cases))

    def test_keyword_match_is_not_quality_certificate(self):
        r = inspect_answer('price-stock', [{'type': 'text', 'content': 'AirPods 999999元，库存充足'}, {'type': 'done'}])
        self.assertTrue(r['checksPassed'])
        self.assertFalse(r['semanticQualityCertified'])

    def test_color_in_spec_reply_fails(self):
        r = inspect_answer('spec-only', [{'type': 'text', 'content': 'USB-C，白色'}, {'type': 'done'}])
        self.assertFalse(r['checksPassed'])

    def test_done_does_not_hide_error(self):
        r = inspect_answer('nonempty', [{'type': 'text', 'content': '处理中'}, {'type': 'error'}, {'type': 'done'}])
        self.assertFalse(r['checksPassed'])

    def test_empty_results_never_pass(self):
        self.assertFalse(inspect_answer('nonempty', [])['checksPassed'])

    def test_node_rejection_and_friendly_failure_are_not_success(self):
        for events in ([{'type': 'node_criteria_rejected'}, {'type': 'text', 'content': '处理中'}, {'type': 'done'}],
                       [{'type': 'text', 'content': '抱歉，这次暂时没能完成查询，请稍后再试。'}, {'type': 'done'}]):
            self.assertFalse(inspect_answer('nonempty', events)['checksPassed'])

    def test_report_is_not_overwritten_and_no_account_is_created(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'report.json'
            report.write_text('original')
            with patch('run_customer_acceptance.request') as network:
                with self.assertRaises(FileExistsError):
                    run('https://example.test', [], report)
                network.assert_not_called()
            self.assertEqual('original', report.read_text())

    def test_registration_failure_is_recorded_not_certified(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch('run_customer_acceptance.request', side_effect=RuntimeError('SECRET')):
                result = run('https://example.test', [], Path(directory) / 'report.json')
            self.assertFalse(result['contractPassed'])
            self.assertNotIn('SECRET', json.dumps(result))

    def test_failed_chat_still_closes_session_and_revokes_token(self):
        visited = []
        def api(base, path, token=None, payload=None):
            visited.append(path)
            if path == '/api/auth/register':
                return {'code': 0, 'data': {'token': 'secret-token', 'refreshToken': 'secret-refresh'}}
            if path == '/api/privacy/profile':
                raise urllib.error.HTTPError(base + path, 401, 'revoked', {}, None)
            return {'code': 0}
        cases = [{'id': 'fixture', 'turns': [{'question': 'test', 'rule': 'nonempty', 'review': 'review'}]}]
        with tempfile.TemporaryDirectory() as directory:
            with patch('run_customer_acceptance.request', side_effect=api), patch('run_customer_acceptance.chat', side_effect=TimeoutError('secret')):
                report = run('https://example.test', cases, Path(directory) / 'report.json')
        self.assertFalse(report['contractPassed'])
        self.assertTrue(report['tokenRevoked'])
        self.assertEqual(1, report['closedSessions'])
        self.assertTrue(any(path.endswith('/close') for path in visited))
        self.assertNotIn('secret', json.dumps(report))


if __name__ == '__main__':
    unittest.main()
