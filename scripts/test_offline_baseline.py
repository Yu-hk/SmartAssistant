import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import run_offline_baseline as baseline


class OfflineBaselineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.item = {'module': 'module', 'suites': [
            {'name': 'example.SampleTest', 'selected': True},
            {'name': 'example.ExternalIntegrationTest', 'selected': False}]}
        self.reports = self.repo / 'module/target/surefire-reports'
        self.reports.mkdir(parents=True)
        self.jacoco = self.repo / 'module/target/site/jacoco/jacoco.xml'
        self.jacoco.parent.mkdir(parents=True)
        self.write_suite()
        self.jacoco.write_text('<report>' + ''.join(
            f'<counter type="{kind}" covered="2" missed="3"/>'
            for kind in baseline.COUNTERS) + '</report>')

    def write_suite(self, name='example.SampleTest', tests=1, extra='', counts=''):
        (self.reports / ('TEST-' + name + '.xml')).write_text(
            f'<testsuite name="{name}" tests="{tests}" failures="0" errors="0" skipped="0" {counts}>'
            + (f'<testcase name="example">{extra}</testcase>' if tests else '') + '</testsuite>')

    def test_pass_and_root_coverage_not_summed(self):
        report = baseline.read_module(self.repo, self.item)
        self.assertEqual(1, report['testcases']['tests'])
        self.assertEqual(0.4, report['coverage']['INSTRUCTION']['ratio'])

    def test_snapshot_keeps_raw_reports_separate_from_module_targets(self):
        output = self.repo / 'snapshot'
        baseline.snapshot_reports(self.repo, output, [self.item])
        self.assertEqual(self.jacoco.read_bytes(), (output / 'reports/module/jacoco.xml').read_bytes())
        self.assertEqual((self.reports / 'TEST-example.SampleTest.xml').read_bytes(),
                         (output / 'reports/module/surefire/TEST-example.SampleTest.xml').read_bytes())

    def test_missing_report_fails(self):
        for path in self.reports.iterdir():
            path.unlink()
        with self.assertRaisesRegex(ValueError, 'missing Surefire'):
            baseline.read_module(self.repo, self.item)

    def test_unexpected_external_report_fails(self):
        self.write_suite('example.ExternalIntegrationTest')
        with self.assertRaisesRegex(ValueError, 'unexpected suite'):
            baseline.read_module(self.repo, self.item)

    def test_failure_error_skip_elements_fail_even_with_zero_header(self):
        for tag in ('failure', 'error', 'skipped'):
            with self.subTest(tag=tag):
                self.write_suite(extra=f'<{tag}/>')
                with self.assertRaises(ValueError):
                    baseline.read_module(self.repo, self.item)

    def test_empty_suite_fails(self):
        self.write_suite(tests=0)
        with self.assertRaisesRegex(ValueError, 'no executed tests'):
            baseline.read_module(self.repo, self.item)

    def test_missing_selected_suite_fails(self):
        self.item['suites'].append({'name': 'example.OtherTest', 'selected': True})
        with self.assertRaisesRegex(ValueError, 'OtherTest'):
            baseline.read_module(self.repo, self.item)

    def test_nested_suite_is_counted_under_owner(self):
        self.write_suite(tests=0)
        self.write_suite('example.SampleTest$Nested')
        self.assertEqual(1, baseline.read_module(self.repo, self.item)['testcases']['tests'])

    def test_testcase_counter_mismatch_fails(self):
        self.write_suite(tests=2)
        with self.assertRaisesRegex(ValueError, 'mismatch'):
            baseline.read_module(self.repo, self.item)

    def test_invalid_counters_fail(self):
        for counter in (None, '-1', '1.5', 'nan', '2x'):
            with self.subTest(counter=counter), self.assertRaises(ValueError):
                baseline.int_count(counter)

    def test_missing_duplicate_empty_jacoco_fails(self):
        original = self.jacoco.read_text()
        for content in (original.replace('type="BRANCH"', 'type="OTHER"'),
                        original.replace('</report>', '<counter type="BRANCH" covered="1" missed="0"/></report>'),
                        original.replace('covered="2" missed="3"', 'covered="0" missed="0"')):
            with self.subTest(content=content):
                self.jacoco.write_text(content)
                with self.assertRaises(ValueError):
                    baseline.read_module(self.repo, self.item)

    def test_zero_branch_denominator_not_fabricated_as_full_coverage(self):
        self.jacoco.write_text(self.jacoco.read_text().replace(
            'type="BRANCH" covered="2" missed="3"', 'type="BRANCH" covered="0" missed="0"'))
        self.assertIsNone(baseline.read_module(self.repo, self.item)['coverage']['BRANCH']['ratio'])

    def test_env_drops_secrets_test_storage_and_jvm_overrides(self):
        result = baseline.clean_environment({'PATH': 'runtime', 'JAVA_HOME': 'jdk',
            'DEEPSEEK_API_KEY': 'secret', 'PG_TEST_PASSWORD': 'secret',
            'RUN_REDIS_INTEGRATION_TESTS': 'true', 'JAVA_TOOL_OPTIONS': '-Dprofile.pg.integration=true',
            'MAVEN_OPTS': '-Dsecret=value', 'SPRING_CONFIG_IMPORT': 'file:credentials'})
        self.assertEqual({'PATH': 'runtime', 'JAVA_HOME': 'jdk'}, result)

    def test_timeout_cleans_only_owned_process_group(self):
        import subprocess
        process = Mock(pid=81234)
        process.wait.side_effect = [subprocess.TimeoutExpired('mvn', 1), 1]
        process.poll.return_value = None
        with patch.object(baseline.os, 'name', 'posix'), \
                patch.object(baseline.subprocess, 'Popen', return_value=process) as start, \
                patch.object(baseline.os, 'killpg', create=True) as kill, \
                patch.object(baseline.signal, 'SIGKILL', 9, create=True):
            with self.assertRaises(subprocess.TimeoutExpired):
                baseline.execute(['mvn'], self.repo, {}, None, 1)
            self.assertTrue(start.call_args.kwargs['start_new_session'])
            kill.assert_called_once_with(81234, baseline.signal.SIGKILL)

    def test_completed_process_not_killed_on_exception(self):
        process = Mock(pid=81234)
        process.wait.side_effect = KeyboardInterrupt()
        process.poll.return_value = 0
        with patch.object(baseline.os, 'name', 'posix'), \
                patch.object(baseline.subprocess, 'Popen', return_value=process), \
                patch.object(baseline.os, 'killpg', create=True) as kill:
            with self.assertRaises(KeyboardInterrupt):
                baseline.execute(['mvn'], self.repo, {}, None, 1)
            kill.assert_not_called()

    def test_windows_timeout_targets_only_owned_pid_tree(self):
        import subprocess
        process = Mock(pid=81234)
        process.wait.side_effect = [subprocess.TimeoutExpired('mvn', 1), 1]
        process.poll.return_value = None
        with patch.object(baseline.os, 'name', 'nt'), \
                patch.object(baseline.subprocess, 'CREATE_NEW_PROCESS_GROUP', 512, create=True), \
                patch.object(baseline.subprocess, 'Popen', return_value=process), \
                patch.object(baseline.subprocess, 'run') as stop:
            with self.assertRaises(subprocess.TimeoutExpired):
                baseline.execute(['mvn'], self.repo, {}, None, 1)
            self.assertEqual(['taskkill', '/PID', '81234', '/T', '/F'], stop.call_args.args[0])
            self.assertTrue(stop.call_args.kwargs['check'])

    def test_repeat_binds_selection_source_test_and_coverage(self):
        old = {'source_fingerprint': 'sha', 'inventory': [self.item], 'policy_sha256': 'sha',
               'defensive_properties': [], 'runner_sha256': 'sha', 'java': '21', 'maven': '3.9.6',
               'modules': [baseline.read_module(self.repo, self.item)], 'elapsed_seconds': 1}
        new = copy.deepcopy(old)
        new['elapsed_seconds'] = 2
        self.assertEqual(baseline.comparable(old), baseline.comparable(new))
        new['modules'][0]['coverage']['INSTRUCTION']['covered'] += 1
        self.assertNotEqual(baseline.comparable(old), baseline.comparable(new))

    def test_coverage_hits_can_vary_but_ranges_are_preserved(self):
        old = {'source_fingerprint': 'sha', 'inventory': [self.item], 'policy_sha256': 'sha',
               'defensive_properties': [], 'runner_sha256': 'sha', 'java': '21', 'maven': '3.9.6',
               'modules': [baseline.read_module(self.repo, self.item)]}
        new = copy.deepcopy(old)
        new['modules'][0]['coverage']['INSTRUCTION'].update(covered=3, missed=2, ratio=0.6)
        measured = baseline.compare_reports(old, new)
        self.assertFalse(measured['coverage_repeat_exact'])
        row = measured['coverage_ranges'][0]
        self.assertEqual((0.4, 0.6), (row['ratio_min'], row['ratio_max']))

    def test_test_count_or_instrumented_denominator_change_fails_repeat(self):
        old = {'source_fingerprint': 'sha', 'inventory': [self.item], 'policy_sha256': 'sha',
               'defensive_properties': [], 'runner_sha256': 'sha', 'java': '21', 'maven': '3.9.6',
               'modules': [baseline.read_module(self.repo, self.item)]}
        for category in ('testcases', 'coverage'):
            new = copy.deepcopy(old)
            if category == 'testcases':
                new['modules'][0]['testcases']['tests'] += 1
            else:
                new['modules'][0]['coverage']['BRANCH']['missed'] += 1
            with self.subTest(category=category), self.assertRaises(ValueError):
                baseline.compare_reports(old, new)

    def test_policy_rejects_new_deleted_or_reclassified_suite(self):
        items = [{'module': 'module', 'suites': [dict(self.item['suites'][0],
                  source='module/src/test/java/example/SampleTest.java', exclusion=None)]}]
        policy = {'schema_version': 1, 'modules': copy.deepcopy(items)}
        baseline.verify_policy(items, policy)
        for change in ('new', 'deleted', 'reclassified'):
            changed = copy.deepcopy(items)
            if change == 'new':
                changed[0]['suites'].append(dict(changed[0]['suites'][0], name='example.NewTest'))
            elif change == 'deleted':
                changed[0]['suites'] = []
            else:
                changed[0]['suites'][0]['selected'] = False
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'inventory changed'):
                baseline.verify_policy(changed, policy)

    def test_policy_accepts_windows_and_linux_order_but_not_changed_entries(self):
        from pathlib import PurePosixPath, PureWindowsPath
        suites = [
            {'name': 'example.RootTest', 'source': 'module/RootTest.java',
             'selected': True, 'exclusion': None},
            {'name': 'example.nested.ChildTest', 'source': 'module/nested/ChildTest.java',
             'selected': True, 'exclusion': None},
            {'name': 'example.external.ExternalIntegrationTest', 'source': 'module/external/ExternalIntegrationTest.java',
             'selected': False, 'exclusion': 'external_storage_or_broker'}]
        windows = sorted(suites, key=lambda suite: PureWindowsPath(suite['source']))
        linux = sorted(suites, key=lambda suite: PurePosixPath(suite['source']))
        self.assertNotEqual(windows, linux)
        policy = {'schema_version': 1, 'modules': [{'module': 'module', 'suites': windows}]}
        items = [{'module': 'module', 'suites': linux}]
        baseline.verify_policy(items, policy)
        changed = copy.deepcopy(items)
        changed[0]['suites'][0]['source'] = 'module/renamed/RootTest.java'
        with self.assertRaisesRegex(ValueError, 'inventory changed'):
            baseline.verify_policy(changed, policy)

    def test_policy_rejects_duplicate_name_or_source_in_actual_and_policy(self):
        original = [{'module': 'module', 'suites': [dict(self.item['suites'][0],
                    source='module/SampleTest.java', exclusion=None)]}]
        for side in ('actual', 'policy'):
            for duplicate_key in ('name', 'source'):
                actual = copy.deepcopy(original)
                policy = {'schema_version': 1, 'modules': copy.deepcopy(original)}
                target = actual if side == 'actual' else policy['modules']
                duplicate = dict(target[0]['suites'][0], name='example.OtherTest', source='module/OtherTest.java')
                duplicate[duplicate_key] = target[0]['suites'][0][duplicate_key]
                target[0]['suites'].append(duplicate)
                with self.subTest(side=side, key=duplicate_key), self.assertRaisesRegex(ValueError, 'duplicate'):
                    baseline.verify_policy(actual, policy)

    def test_new_output_only_within_dedicated_root(self):
        with self.assertRaisesRegex(ValueError, 'NEW directory'):
            baseline.run(self.repo, self.repo / 'elsewhere', 'unused')
        output = self.repo / '.codex-output/offline-baseline/existing'
        output.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, 'NEW directory'):
            baseline.run(self.repo, output, 'unused')

    def test_inventory_excludes_real_dependencies_but_retains_safe_integration(self):
        (self.repo / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<modules><module>module</module></modules></project>')
        directory = self.repo / 'module/src/test/java'
        directory.mkdir(parents=True)
        for name, package in [('RagRerankTest', 'example'), ('ConcurrencyLoadTest', 'example'),
                              ('ExternalIntegrationTest', 'example'),
                              ('AgentMemoryServiceIntegrationTest', 'com.example.smartassistant.common.memory')]:
            (directory / (name + '.java')).write_text('package ' + package + ';\nclass ' + name + ' {}')
        suites = baseline.inventory(self.repo)[0]['suites']
        self.assertEqual(['com.example.smartassistant.common.memory.AgentMemoryServiceIntegrationTest'],
                         [suite['name'] for suite in suites if suite['selected']])


if __name__ == '__main__':
    unittest.main()
