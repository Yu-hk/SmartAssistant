"""Pure receipt/safety contracts; these tests do not launch Docker or certify shutdown."""
import hashlib
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('probe', Path(__file__).with_name('run_dispatch_shutdown_probe.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ReceiptContractTest(unittest.TestCase):
    def test_complete_matrix_cannot_be_replaced_with_duplicates_or_partial_results(self):
        cases = [{'case': case, 'repeat': repeat, 'status': 'passed'}
                 for case in ('empty', 'queued', 'short', 'before-ack', 'running') for repeat in range(1, 4)]
        probe.verify_matrix(cases)
        for invalid in (cases[:-1], [cases[0]] * 15, cases + [cases[0]],
                        [{**cases[0], 'status': 'failed'}] + cases[1:]):
            with self.subTest(invalid=invalid), self.assertRaises(AssertionError):
                probe.verify_matrix(invalid)

    def test_same_count_with_unknown_case_is_not_a_complete_matrix(self):
        cases = [{'case': case, 'repeat': repeat, 'status': 'passed'}
                 for case in ('empty', 'queued', 'short', 'before-ack', 'other') for repeat in range(1, 4)]
        with self.assertRaises(AssertionError):
            probe.verify_matrix(cases)

    def test_remote_docker_engine_is_refused(self):
        for endpoint in ('tcp://server:2376', 'ssh://root@server', '', None):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                probe.require_local_docker(endpoint)
        probe.require_local_docker('unix:///var/run/docker.sock')
        probe.require_local_docker('npipe:////./pipe/dockerDesktopLinuxEngine')

    def test_completed_requires_expected_result_and_single_execution(self):
        valid = {'status': 'COMPLETED', 'calls': 1, 'result': {'result': 'synthetic verified result'}}
        probe.assert_snapshot(valid, 'COMPLETED', 1)
        for changed in ({**valid, 'calls': 2}, {**valid, 'status': 'RUNNING'}, {**valid, 'result': {}}):
            with self.subTest(changed=changed), self.assertRaises(AssertionError):
                probe.assert_snapshot(changed, 'COMPLETED', 1)

    def test_ambiguous_execution_cannot_claim_success(self):
        probe.assert_snapshot({'status': 'RUNNING', 'calls': 1, 'result': {}}, 'RUNNING', 1)
        with self.assertRaises(AssertionError):
            probe.assert_snapshot({'status': 'RUNNING', 'calls': 1, 'result': {'result': 'success'}}, 'RUNNING', 1)

    def test_queued_must_not_have_executed(self):
        probe.assert_snapshot({'status': 'QUEUED', 'calls': 0, 'result': {}}, 'QUEUED', 0)
        with self.assertRaises(AssertionError):
            probe.assert_snapshot({'status': 'QUEUED', 'calls': 1, 'result': {}}, 'QUEUED', 0)

    def test_normal_stop_requires_complete_unique_term_hooks(self):
        state = {'Running': False, 'OOMKilled': False, 'ExitCode': 143}
        valid = 'PROBE_TERM_BEGIN generation\nPROBE_TERM_END generation'
        probe.verify_stop(state, valid, 'generation')
        for changed, logs in [({**state, 'ExitCode': 137}, valid), ({**state, 'OOMKilled': True}, valid),
                              ({**state, 'Running': True}, valid), (state, ''), (state, valid + valid)]:
            with self.subTest(changed=changed, logs=logs), self.assertRaises(AssertionError):
                probe.verify_stop(changed, logs, 'generation')

    def test_intentional_kill_is_separate_from_normal_term(self):
        state = {'Running': False, 'OOMKilled': False, 'ExitCode': 137}
        probe.verify_stop(state, '', 'generation', deliberate_kill=True)
        with self.assertRaises(AssertionError):
            probe.verify_stop({**state, 'ExitCode': 143}, '', 'generation', deliberate_kill=True)

    def test_request_key_matches_actual_store_hash(self):
        self.assertEqual(probe.request_key('synthetic-id'), 'chat:dispatch:v1:{dispatch}:request:' +
                         hashlib.sha256(b'synthetic-id').hexdigest())

    def test_cleanup_refuses_unowned_container(self):
        instance = probe.Probe(Path('.'), Path('.'))
        instance.ids = ['unrelated-id']
        with patch.object(probe, 'command', return_value='[{"Config":{"Labels":{}}}]') as command:
            with self.assertRaises(AssertionError):
                instance.cleanup()
            command.assert_called_once_with('inspect', 'unrelated-id')

    def test_cleanup_refuses_nonempty_or_unowned_network(self):
        for data in ({'Labels': {}, 'Containers': {}},
                     {'Labels': {probe.LABEL: 'owned'}, 'Containers': {'other': {}}}):
            instance = probe.Probe(Path('.'), Path('.'))
            instance.scope, instance.network = 'owned', 'network-id'
            import json
            with self.subTest(data=data), patch.object(probe, 'command', return_value=json.dumps([data])) as command:
                with self.assertRaises(AssertionError):
                    instance.cleanup()
                command.assert_called_once_with('network', 'inspect', 'network-id')


if __name__ == '__main__':
    unittest.main()
