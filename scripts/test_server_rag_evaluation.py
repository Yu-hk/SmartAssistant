"""Safe diagnostic contracts; actual container isolation is verified on the server."""
import subprocess
import unittest
from unittest.mock import patch
import run_server_rag_evaluation as runner

class RunnerDiagnosticTest(unittest.TestCase):
    def test_returns_only_success_stdout(self):
        response = subprocess.CompletedProcess([], 0, b'{"ok":true}', b'private diagnostic')
        with patch.object(runner.subprocess, 'run', return_value=response):
            self.assertEqual(runner.run(['fixture']), b'{"ok":true}')

    def test_failure_exposes_only_category_and_type(self):
        response = subprocess.CompletedProcess([], 1, b'private stdout',
            b'Embedding unavailable; secret=never-print\n'
            b'RAG_EMBEDDING_FAILURE_TYPE=java.net.UnknownHostException\n')
        with patch.object(runner.subprocess, 'run', return_value=response):
            with self.assertRaises(RuntimeError) as error:
                runner.run(['fixture'])
        self.assertIn('dns_failed', str(error.exception))
        self.assertIn('java.net.UnknownHostException', str(error.exception))
        self.assertNotIn('never-print', str(error.exception))
        self.assertNotIn('private stdout', str(error.exception))

    def test_timeout_is_not_success(self):
        with patch.object(runner.subprocess, 'run', side_effect=subprocess.TimeoutExpired(['fixture'], 1)):
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.run(['fixture'], timeout=1)

if __name__ == '__main__':
    unittest.main()
