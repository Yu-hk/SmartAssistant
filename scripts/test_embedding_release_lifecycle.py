"""Release-plan tests only: no Docker, HTTP, live models or configured credentials."""
import copy
import hashlib
import json
import pathlib
import tempfile
import unittest
import warnings
import zipfile
from types import SimpleNamespace
from unittest.mock import patch

import release_embedding_lifecycle_20261001 as release


def fixture():
    return {'Id': 'original-id', 'Name': '/' + release.SERVICE, 'Image': 'sha256:' + release.IMAGE,
            'Config': {'Image': 'original-image-reference', 'Hostname': 'original-host', 'Domainname': '',
                       'Entrypoint': ['/__cacert_entrypoint.sh'], 'Cmd': list(release.COMMAND),
                       'WorkingDir': '/app', 'User': '', 'Env': ['TOKEN=synthetic-secret', 'BGE_MODEL_PATH=/app/models/model.onnx'],
                       'Labels': {'unrelated': 'preserved'}, 'StopSignal': '15', 'StopTimeout': 10,
                       'OpenStdin': False, 'Tty': False, 'StdinOnce': False, 'AttachStdin': False,
                       'AttachStdout': False, 'AttachStderr': False},
            'HostConfig': {'Runtime': 'runc', 'Memory': 2147483648, 'MemorySwap': 4294967296,
                           'RestartPolicy': {'Name': 'unless-stopped', 'MaximumRetryCount': 0},
                           'Dns': ['10.89.1.1'], 'DnsSearch': ['internal'], 'DnsOptions': ['ndots:0'],
                           'LogConfig': {'Type': 'json-file', 'Config': {'max-size': '10m'}},
                           'NetworkMode': release.NETWORK, 'PortBindings': {}, 'Privileged': False},
            'Mounts': [{'Type': 'bind', 'Source': release.BASELINE_JAR, 'Destination': '/app/app.jar',
                        'RW': False, 'Propagation': 'rprivate'},
                       {'Type': 'bind', 'Source': release.MODELS, 'Destination': '/app/models',
                        'RW': False, 'Propagation': 'rprivate'}],
            'State': {'Running': True, 'Status': 'running', 'ExitCode': 0, 'OOMKilled': False},
            'NetworkSettings': {'Networks': {release.NETWORK: {'IPAddress': '10.89.1.32',
                                                              'Aliases': [release.SERVICE]}}}}


def podman_fixture():
    source = fixture()
    source['HostConfig']['Runtime'] = 'oci'
    source['OCIRuntime'] = 'runc'
    source['Config']['Annotations'] = {'io.container.manager': 'libpod',
                                      'org.opencontainers.image.stopSignal': '15'}
    source['Config']['CreateCommand'] = ['podman', 'run', '--env', 'TOKEN=synthetic-secret']
    source['HostConfig']['LogConfig'] = {'Type': 'k8s-file', 'Config': {'max-size': '10m'},
        'Path': '/var/lib/containers/storage/overlay-containers/' + source['Id'] + '/userdata/ctr.log'}
    return source


class CloneContractTest(unittest.TestCase):
    def podman_candidate(self, source):
        candidate = release.expected_source(source)
        candidate['Id'] = 'candidate-id'
        candidate['Config']['CreateCommand'] = ['podman', 'create', '--name', release.PREFLIGHT]
        candidate['Config']['Env'] = list(reversed(candidate['Config']['Env']))
        candidate['HostConfig']['LogConfig']['Path'] = \
            '/var/lib/containers/storage/overlay-containers/' + candidate['Id'] + '/userdata/ctr.log'
        return candidate

    def test_reviewed_podman_copies_annotations_and_accepts_only_generated_differences(self):
        source = podman_fixture()
        original = copy.deepcopy(source)
        with patch.object(release, 'run', return_value='candidate-id') as run:
            release.clone(source, release.PREFLIGHT)
        args = run.call_args.args
        self.assertIn('io.container.manager=libpod', args)
        self.assertIn('org.opencontainers.image.stopSignal=15', args)
        self.assertEqual(2, args.count('--annotation'))
        release.equivalent(source, self.podman_candidate(source))
        self.assertEqual(original, source)
        self.assertEqual(original['Config']['CreateCommand'], release.identity(source)['Config']['CreateCommand'])

    def test_podman_environment_values_missing_names_and_duplicates_are_not_ignored(self):
        source = podman_fixture()
        for env in (['TOKEN=changed', 'BGE_MODEL_PATH=/app/models/model.onnx'],
                    ['TOKEN=synthetic-secret'], source['Config']['Env'] + ['TOKEN=synthetic-secret'],
                    source['Config']['Env'] + ['TOKEN=changed'], ['TOKEN']):
            candidate = self.podman_candidate(source)
            candidate['Config']['Env'] = env
            with self.subTest(env=env), self.assertRaises(RuntimeError):
                release.equivalent(source, candidate)
        source['Config']['Env'].append('TOKEN=duplicate')
        with patch.object(release, 'run') as run, self.assertRaises(RuntimeError):
            release.clone(source, release.PREFLIGHT)
        run.assert_not_called()

    def test_podman_annotation_missing_changed_or_extra_is_rejected(self):
        source = podman_fixture()
        for annotations in ({}, {'io.container.manager': 'libpod'},
                            dict(source['Config']['Annotations'], **{'org.opencontainers.image.stopSignal': '9'}),
                            dict(source['Config']['Annotations'], unrelated='extra')):
            candidate = self.podman_candidate(source)
            candidate['Config']['Annotations'] = annotations
            with self.subTest(annotations=annotations), self.assertRaises(RuntimeError):
                release.equivalent(source, candidate)

    def test_only_own_id_bound_default_k8s_log_path_is_normalized(self):
        source = podman_fixture()
        for value in ('/tmp/explicit.log', source['HostConfig']['LogConfig']['Path'], None):
            candidate = self.podman_candidate(source)
            candidate['HostConfig']['LogConfig']['Path'] = value
            with self.subTest(path=value), self.assertRaises(RuntimeError):
                release.equivalent(source, candidate)
        explicit = podman_fixture()
        explicit['HostConfig']['LogConfig']['Path'] = '/approved/explicit.log'
        candidate = self.podman_candidate(explicit)
        candidate['HostConfig']['LogConfig']['Path'] = '/approved/explicit.log'
        release.equivalent(explicit, candidate)
        candidate['HostConfig']['LogConfig']['Path'] = '/other/explicit.log'
        with self.assertRaises(RuntimeError):
            release.equivalent(explicit, candidate)
        candidate = self.podman_candidate(source)
        candidate['HostConfig']['LogConfig']['Config']['max-size'] = '100m'
        with self.assertRaises(RuntimeError):
            release.equivalent(source, candidate)

    def test_podman_create_command_requires_metadata_and_plain_engines_still_compare_it(self):
        source = podman_fixture()
        for command in (None, [], 'podman create', [True]):
            candidate = self.podman_candidate(source)
            candidate['Config']['CreateCommand'] = command
            with self.subTest(command=command), self.assertRaises(RuntimeError):
                release.equivalent(source, candidate)
        plain = fixture()
        plain['Config']['CreateCommand'] = ['original']
        candidate = release.expected_source(plain)
        candidate['Config']['CreateCommand'] = ['changed']
        with self.assertRaises(RuntimeError):
            release.equivalent(plain, candidate)

    def test_podman_oci_marker_uses_actual_runc_and_keeps_inspect_host_config(self):
        source = podman_fixture()
        with patch.object(release, 'run', return_value='candidate-id') as run:
            release.clone(source, release.PREFLIGHT)
        args = run.call_args.args
        self.assertEqual('runc', args[args.index('--runtime') + 1])
        self.assertEqual('oci', source['HostConfig']['Runtime'])
        self.assertEqual('runc', release.identity(source)['OCIRuntime'])
        release.equivalent(source, release.expected_source(source))

    def test_podman_oci_runtime_missing_or_unreviewed_metadata_fails_before_create(self):
        for actual in (None, '', 'oci', 'crun', '/usr/bin/runc', True, 15):
            source = podman_fixture()
            if actual is not None:
                source['OCIRuntime'] = actual
            else:
                source.pop('OCIRuntime')
            with self.subTest(actual=actual), patch.object(release, 'run') as run:
                for operation in (lambda: release.validate_live(source),
                                  lambda: release.clone(source, release.PREFLIGHT),
                                  lambda: release.identity(source)):
                    with self.assertRaises(RuntimeError):
                        operation()
                run.assert_not_called()

    def test_actual_oci_runtime_drift_or_host_marker_change_is_not_normalized_away(self):
        source = podman_fixture()
        for field, value in (('OCIRuntime', None), ('OCIRuntime', 'crun'), ('Runtime', 'runc')):
            candidate = release.expected_source(source)
            if field == 'Runtime':
                candidate['HostConfig'][field] = value
            elif value is None:
                candidate.pop(field)
            else:
                candidate[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                release.equivalent(source, candidate)

    def test_plain_engine_runtime_is_preserved_without_inventing_podman_metadata(self):
        source = fixture()
        source['HostConfig']['Runtime'] = 'crun'
        with patch.object(release, 'run', return_value='candidate-id') as run:
            release.clone(source, release.PREFLIGHT)
        args = run.call_args.args
        self.assertEqual('crun', args[args.index('--runtime') + 1])
        self.assertNotIn('OCIRuntime', release.identity(source))
        release.equivalent(source, release.expected_source(source))

    def test_podman_integer_stop_signal_passes_live_validation_and_is_a_cli_string(self):
        source = fixture()
        source['Config']['StopSignal'] = 15
        with patch.object(release, 'digest', return_value=release.BASELINE_SHA), \
                patch.object(release, 'run', return_value=release.BASELINE_SHA + ' /app/app.jar'):
            release.validate_live(source)
        with patch.object(release, 'run', return_value='candidate-id') as run:
            release.clone(source, release.PREFLIGHT)
        args = run.call_args.args
        self.assertEqual('15', args[args.index('--stop-signal') + 1])
        self.assertTrue(all(isinstance(value, str) for value in args))
        self.assertEqual(15, source['Config']['StopSignal'])

    def test_only_integer15_and_string15_signal_representations_are_equivalent(self):
        for old_signal, new_signal in ((15, '15'), ('15', 15), (15, 15), ('15', '15')):
            source = fixture()
            source['Config']['StopSignal'] = old_signal
            candidate = release.expected_source(source)
            candidate['Config']['StopSignal'] = new_signal
            with self.subTest(old=old_signal, new=new_signal):
                release.equivalent(source, candidate)
            self.assertEqual(old_signal, source['Config']['StopSignal'])
            self.assertEqual(new_signal, candidate['Config']['StopSignal'])

    def test_boolean_float_missing_and_other_signals_are_rejected_before_create(self):
        for signal in (True, False, 15.0, None, 9, '9', 'TERM', 'SIGTERM', '015', '15 '):
            source = fixture()
            source['Config']['StopSignal'] = signal
            with self.subTest(signal=signal), patch.object(release, 'run') as run:
                with self.assertRaises(RuntimeError):
                    release.validate_live(source)
                with self.assertRaises(RuntimeError):
                    release.clone(source, release.PREFLIGHT)
                run.assert_not_called()

    def test_candidate_signal_normalization_does_not_relax_other_config_fields(self):
        source = fixture()
        source['Config']['StopSignal'] = 15
        candidate = release.expected_source(source)
        candidate['Config']['StopSignal'] = '15'
        for key, value in (('StopTimeout', 60), ('Cmd', ['other']), ('Env', ['other']),
                           ('StopSignal', True), ('StopSignal', 'TERM')):
            bad = copy.deepcopy(candidate)
            bad['Config'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                release.equivalent(source, bad)

    def test_resource_gate_rejects_low_disk_or_memory_before_any_docker_call(self):
        for free, memory in ((1024 ** 3 - 1, 'MemAvailable: 4000000 kB\n'),
                             (1024 ** 3, 'MemAvailable: 3145727 kB\n'),
                             (1024 ** 3, 'no memory counter\n')):
            with self.subTest(free=free, memory=memory), \
                    patch.object(release.shutil, 'disk_usage', return_value=SimpleNamespace(free=free)), \
                    patch.object(release.pathlib.Path, 'read_text', return_value=memory), \
                    patch.object(release, 'run') as run, self.assertRaises(RuntimeError):
                release.resource_gate()
            run.assert_not_called()
        with patch.object(release.shutil, 'disk_usage', return_value=SimpleNamespace(free=1024 ** 3)), \
                patch.object(release.pathlib.Path, 'read_text', return_value='MemAvailable: 3145728 kB\n'):
            release.resource_gate()

    def test_clone_preserves_models_env_runtime_dns_logging_restart_and_stop_fields(self):
        source = fixture()
        before = copy.deepcopy(source)
        with patch.object(release, 'run', return_value='candidate-id') as run:
            self.assertEqual('candidate-id', release.clone(source, release.PREFLIGHT))
        args = run.call_args.args
        for flag, value in (('--runtime', 'runc'), ('--restart', 'unless-stopped'),
                            ('--stop-signal', '15'), ('--stop-timeout', '10'), ('--dns', '10.89.1.1'),
                            ('--dns-search', 'internal'), ('--dns-option', 'ndots:0'),
                            ('--log-driver', 'json-file'), ('--log-opt', 'max-size=10m'),
                            ('--env', 'TOKEN=synthetic-secret')):
            self.assertEqual(value, args[args.index(flag) + 1])
        self.assertIn('type=bind,source=' + release.MODELS + ',target=/app/models,readonly,bind-propagation=rprivate', args)
        self.assertIn('type=bind,source=' + str(release.ROOT / 'embedding-candidate.jar')
                      + ',target=/app/app.jar,readonly,bind-propagation=rprivate', args)
        self.assertEqual(tuple(release.COMMAND), args[-len(release.COMMAND):])
        self.assertEqual(before, source)

    def test_canary_changes_only_discovery_command_and_identity(self):
        source = fixture()
        with patch.object(release, 'run', return_value='canary') as run:
            release.clone(source, release.CANARY, canary=True)
        args = run.call_args.args
        self.assertEqual(release.DISABLE_DISCOVERY, args[-1])
        self.assertEqual(release.CANARY, args[args.index('--network-alias') + 1])
        self.assertIn('TOKEN=synthetic-secret', args)
        self.assertEqual(release.COMMAND, source['Config']['Cmd'])

    def test_clone_rejects_unrelated_name_or_unsupported_settings_before_create(self):
        with patch.object(release, 'run') as run:
            with self.assertRaises(RuntimeError):
                release.clone(fixture(), 'smart-product')
            source = fixture()
            source['HostConfig']['Privileged'] = True
            with self.assertRaises(RuntimeError):
                release.clone(source, release.PREFLIGHT)
            run.assert_not_called()

    def test_equivalent_checks_stop_fields_environment_runtime_and_models(self):
        source = fixture()
        good = release.expected_source(source)
        good['Config']['Image'] = source['Image']
        release.equivalent(source, good)
        for section, key, value in (('Config', 'StopTimeout', 60), ('Config', 'StopSignal', 'TERM'),
                                    ('Config', 'Env', ['wrong']), ('HostConfig', 'Runtime', 'other'),
                                    ('HostConfig', 'Dns', []), ('HostConfig', 'NewRiskyField', True)):
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                bad = copy.deepcopy(good)
                bad[section][key] = value
                release.equivalent(source, bad)
        bad = copy.deepcopy(good)
        bad['Mounts'][1]['RW'] = True
        with self.assertRaises(RuntimeError):
            release.equivalent(source, bad)

    def test_canary_production_alias_is_rejected(self):
        source = fixture()
        candidate = release.expected_source(source, canary=True)
        with self.assertRaises(RuntimeError):
            release.equivalent(source, candidate, canary=True)
        candidate['NetworkSettings']['Networks'][release.NETWORK]['Aliases'] = [release.CANARY]
        release.equivalent(source, candidate, canary=True)

    def test_preflight_never_starts_or_force_removes_container(self):
        source = fixture()
        candidate = release.expected_source(source)
        candidate['State']['Running'] = False
        with patch.object(release, 'exists', return_value=False), patch.object(release, 'clone', return_value='preflight'), \
                patch.object(release, 'inspect', return_value=candidate), patch.object(release, 'run') as run:
            release.preflight(source)
        run.assert_called_once_with('docker', 'rm', 'preflight')


class ArtifactScopeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.old = self.root / 'baseline.jar'
        self.new = self.root / 'embedding-candidate.jar'
        self.write(self.old, [(release.ENTRY, b'old-class'), ('BOOT-INF/lib/model.jar', b'unchanged-library')])
        self.write(self.new, [(release.ENTRY, b'new-class'), ('BOOT-INF/lib/model.jar', b'unchanged-library')])
        self.before = hashlib.sha256(self.old.read_bytes()).hexdigest()
        self.root_patch = patch.object(release, 'ROOT', self.root)
        self.jar_patch = patch.object(release, 'BASELINE_JAR', str(self.old))
        self.sha_patch = patch.object(release, 'BASELINE_SHA', self.before)
        for guard in (self.root_patch, self.jar_patch, self.sha_patch):
            guard.start()
            self.addCleanup(guard.stop)

    def write(self, path, entries):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(path, 'w') as archive:
                for name, value in entries:
                    archive.writestr(name, value)

    def manifest(self):
        return {'baseline_sha256': self.before, 'sha256': hashlib.sha256(self.new.read_bytes()).hexdigest(),
                'classes_replaced': 1, 'embedding_entries_replaced': [release.ENTRY],
                'unrelated_entries_preserved': True,
                'before_class_sha256': hashlib.sha256(b'old-class').hexdigest(),
                'after_class_sha256': hashlib.sha256(b'new-class').hexdigest()}

    def test_exact_single_entry_overlay_passes(self):
        self.assertEqual(self.manifest()['sha256'], release.verify_artifact(self.manifest()))

    def test_unrelated_zip_changes_missing_entries_and_duplicates_fail(self):
        for entries in ([(release.ENTRY, b'new-class'), ('BOOT-INF/lib/model.jar', b'changed-library')],
                        [(release.ENTRY, b'new-class')],
                        [(release.ENTRY, b'new-class'), (release.ENTRY, b'new-class'),
                         ('BOOT-INF/lib/model.jar', b'unchanged-library')]):
            with self.subTest(entries=entries):
                self.write(self.new, entries)
                with self.assertRaises(RuntimeError):
                    release.verify_artifact(self.manifest())

    def test_manifest_must_not_broaden_scope_or_fake_binary_proof(self):
        for key, value in (('classes_replaced', 2), ('classes_replaced', True),
                           ('unrelated_entries_preserved', False), ('embedding_entries_replaced', []),
                           ('before_class_sha256', '0' * 64), ('after_class_sha256', '0' * 64),
                           ('baseline_sha256', '0' * 64), ('sha256', '0' * 64)):
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                manifest = self.manifest()
                manifest[key] = value
                release.verify_artifact(manifest)

    def test_private_receipt_uses_exclusive_creation_and_never_overwrites_snapshot(self):
        release.private_json('before-container.json', {'Env': ['synthetic-secret']})
        with self.assertRaises(FileExistsError):
            release.private_json('before-container.json', {'changed': True})
        self.assertEqual({'Env': ['synthetic-secret']}, json.loads((self.root / 'before-container.json').read_text()))

    def test_any_existing_deployment_receipt_blocks_repeat_before_inspection(self):
        (self.root / 'deployment.json').write_text('{}', encoding='utf-8')
        with patch.object(release, 'inspect') as inspect, patch.object(release, 'resource_gate') as resources, \
                self.assertRaises(RuntimeError):
            release.prepare()
        inspect.assert_not_called()
        resources.assert_not_called()

    def test_binary_gate_requires_matching_annotation_bound_v2_proof(self):
        manifest = self.manifest()
        good = {'status': 'passed', 'baseline_sha256': release.BASELINE_SHA, 'sha256': manifest['sha256'],
                'changed_entries': [release.ENTRY], 'method_bytecode_identical': True,
                'annotations_bound_checked': True, 'model_calls': 0}
        with patch.object(release, 'read_private', return_value=good) as read:
            release.binary_gate(manifest)
        read.assert_called_once_with('binary-verified-v2.json')
        for key, value in (('status', 'failed'), ('baseline_sha256', '0' * 64), ('sha256', '0' * 64),
                           ('changed_entries', []), ('method_bytecode_identical', False),
                           ('annotations_bound_checked', False), ('model_calls', 1), ('model_calls', False)):
            with self.subTest(key=key), patch.object(release, 'read_private', return_value=dict(good, **{key: value})), \
                    self.assertRaises(RuntimeError):
                release.binary_gate(manifest)


class StopAndPromotionTest(unittest.TestCase):
    def test_rollback_restores_original_when_failed_stop_actually_left_candidate_stopped(self):
        old = fixture()
        previous = copy.deepcopy(old)
        previous['Name'] = '/' + release.PREVIOUS
        previous['State']['Running'] = False
        previous['NetworkSettings']['Networks'] = {}
        candidate = release.expected_source(old)
        candidate['Id'] = 'candidate-id'
        candidate['Name'] = '/' + release.SERVICE
        stopped = copy.deepcopy(candidate)
        stopped['State']['Running'] = False
        def inspection(name):
            return candidate if name == release.SERVICE else stopped if name == 'candidate-id' else previous
        with patch.object(release, 'exists', side_effect=lambda name: name == release.SERVICE), \
                patch.object(release, 'inspect', side_effect=inspection), patch.object(release, 'owned'), \
                patch.object(release, 'stop_checked', side_effect=RuntimeError('stop CLI failed')), \
                patch.object(release, 'run') as run, patch.object(release, 'digest', return_value=release.BASELINE_SHA), \
                patch.object(release, 'wait'), patch.object(release, 'live_hash'):
            result = release.rollback(old, 'a' * 64)
        self.assertEqual('RuntimeError', result['candidate_stop_error_type'])
        self.assertIn(('docker', 'rename', 'candidate-id', release.FAILED), [call.args for call in run.call_args_list])
        self.assertIn(('docker', 'rename', old['Id'], release.SERVICE), [call.args for call in run.call_args_list])
        self.assertIn(('docker', 'start', old['Id']), [call.args for call in run.call_args_list])
        self.assertFalse(any(call.args[:2] == ('docker', 'rm') for call in run.call_args_list))

    def test_normal_stop_records_non137_and_explicit_60_seconds(self):
        state = {'Running': False, 'Status': 'exited', 'ExitCode': 143, 'OOMKilled': False}
        with patch.object(release, 'run') as run, patch.object(release, 'inspect', return_value={'State': state}), \
                patch.object(release, 'private_json') as receipt:
            result = release.stop_checked('canary-id', 'stop.json')
        run.assert_called_once_with('docker', 'stop', '--time', '60', 'canary-id', timeout=90)
        self.assertTrue(result['cli_ok'])
        self.assertEqual(143, receipt.call_args.args[1]['exit_code'])

    def test_stop_cli_failure_137_oom_and_running_are_never_success(self):
        for cli_error, state in ((RuntimeError('synthetic-secret'), {'Running': False, 'Status': 'exited', 'ExitCode': 0}),
                                 (None, {'Running': False, 'Status': 'exited', 'ExitCode': 137}),
                                 (None, {'Running': False, 'Status': 'exited', 'ExitCode': 1}),
                                 (None, {'Running': False, 'Status': 'exited', 'ExitCode': 0, 'OOMKilled': True}),
                                 (None, {'Running': True, 'Status': 'running', 'ExitCode': 0}),
                                 (None, {'Running': False, 'Status': 'created', 'ExitCode': 0})):
            with self.subTest(state=state), patch.object(release, 'run', side_effect=cli_error), \
                    patch.object(release, 'inspect', return_value={'State': state}), \
                    patch.object(release, 'private_json') as receipt, self.assertRaises(RuntimeError) as failure:
                release.stop_checked('canary-id', 'stop.json')
            receipt.assert_called_once()
            self.assertNotIn('synthetic-secret', str(failure.exception))

    def canary_test(self, verify_error=None, stop_error=None, reachable=False):
        old = fixture()
        candidate = release.expected_source(old, canary=True)
        candidate['Id'] = 'canary-id'
        candidate['Name'] = '/' + release.CANARY
        candidate['NetworkSettings']['Networks'][release.NETWORK]['Aliases'] = [release.CANARY]
        def inspection(name):
            return old if name == release.SERVICE else candidate
        with tempfile.TemporaryDirectory() as directory, patch.object(release, 'ROOT', pathlib.Path(directory)), \
                patch.object(release, 'exists', return_value=False), patch.object(release, 'clone', return_value='canary-id'), \
                patch.object(release, 'inspect', side_effect=inspection), patch.object(release, 'equivalent'), \
                patch.object(release, 'owned'), patch.object(release, 'run', return_value='') as run, patch.object(release, 'wait'), \
                patch.object(release, 'address', return_value='http://10.89.1.33:8091'), \
                patch.object(release, 'request', side_effect=None if reachable else OSError()), \
                patch.object(release, 'live_hash'), patch.object(release, 'verify8', side_effect=verify_error,
                    return_value={'checks': 8, 'status': 'passed'}), \
                patch.object(release, 'stop_checked', side_effect=stop_error,
                    return_value={'cli_ok': True, 'exit_code': 0}) as stop, \
                patch.object(release, 'private_json') as receipt:
            if verify_error or stop_error or reachable:
                with self.assertRaises(RuntimeError):
                    release.canary(old, {'sha256': 'a' * 64})
            else:
                release.canary(old, {'sha256': 'a' * 64})
        stop.assert_called_once_with('canary-id', 'canary-stop.json')
        self.assertFalse(any(call.args[:2] == ('docker', 'rm') for call in run.call_args_list))
        return receipt.call_args.args[1]

    def test_verified_canary_must_also_stop_normally_and_is_retained(self):
        self.assertEqual('passed', self.canary_test()['status'])

    def test_probe_or_stop_failure_keeps_canary_failed_and_redacts_exception(self):
        failed = self.canary_test(verify_error=ValueError('synthetic-secret'))
        self.assertEqual('failed', failed['status'])
        self.assertNotIn('synthetic-secret', json.dumps(failed))
        self.assertEqual('failed', self.canary_test(stop_error=RuntimeError('stop failed'))['status'])

    def test_stopped_canary_that_still_answers_http_cannot_pass(self):
        self.assertEqual('failed', self.canary_test(reachable=True)['status'])

    def test_deploy_failure_always_attempts_original_and_gateway_restore(self):
        old = fixture()
        old['Config']['StopSignal'] = 15
        gateway = {'Id': 'gateway-id', 'State': {'Running': True}}
        stopped_gateway = {'Id': 'gateway-id', 'State': {'Running': False}}
        def inspection(name):
            return gateway if name == 'smart-gateway' else stopped_gateway if name == 'gateway-id' else old
        with patch.object(release, 'canary_gate'), patch.object(release, 'exists', return_value=False), \
                patch.object(release, 'inspect', side_effect=inspection), patch.object(release, 'run'), \
                patch.object(release, 'drain'), patch.object(release, 'stop_checked', side_effect=RuntimeError('stop failed')), \
                patch.object(release, 'rollback', return_value={}) as rollback, \
                patch.object(release, 'restore_gateway') as gateway_restore, patch.object(release, 'private_json') as receipt:
            with self.assertRaises(RuntimeError):
                release.deploy(old, {'sha256': 'a' * 64})
        rollback.assert_called_once_with(old, 'a' * 64)
        gateway_restore.assert_called_once_with(gateway)
        self.assertEqual('rolled_back', receipt.call_args.args[1]['status'])
        self.assertTrue(receipt.call_args.args[1]['gateway_restored'])
        self.assertIs(type(receipt.call_args.args[1]['preserved_stop_signal']), int)
        self.assertEqual(15, receipt.call_args.args[1]['preserved_stop_signal'])

    def test_gateway_stop_error_is_also_guarded_before_command_returns(self):
        old = fixture()
        gateway = {'Id': 'gateway-id', 'State': {'Running': False}}
        initial_gateway = {'Id': 'gateway-id', 'State': {'Running': True}}
        def inspection(name):
            return initial_gateway if name == 'smart-gateway' else gateway
        with patch.object(release, 'canary_gate'), patch.object(release, 'exists', return_value=False), \
                patch.object(release, 'inspect', side_effect=inspection), patch.object(release, 'run', side_effect=RuntimeError()), \
                patch.object(release, 'rollback', return_value={}) as rollback, \
                patch.object(release, 'restore_gateway') as restore, patch.object(release, 'private_json'):
            with self.assertRaises(RuntimeError):
                release.deploy(old, {'sha256': 'a' * 64})
        restore.assert_called_once_with(initial_gateway)
        rollback.assert_called_once()

    def test_rollback_failure_does_not_skip_gateway_restore_or_claim_success(self):
        old = fixture()
        gateway = {'Id': 'gateway-id', 'State': {'Running': True}}
        stopped = {'Id': 'gateway-id', 'State': {'Running': False}}
        with patch.object(release, 'canary_gate'), patch.object(release, 'exists', return_value=False), \
                patch.object(release, 'inspect', side_effect=lambda name: gateway if name == 'smart-gateway' else stopped), \
                patch.object(release, 'run'), patch.object(release, 'drain', side_effect=RuntimeError()), \
                patch.object(release, 'rollback', side_effect=RuntimeError()) as rollback, \
                patch.object(release, 'restore_gateway') as restore, patch.object(release, 'private_json') as receipt:
            with self.assertRaises(RuntimeError):
                release.deploy(old, {'sha256': 'a' * 64})
        restore.assert_called_once_with(gateway)
        self.assertEqual('rollback_failed', receipt.call_args.args[1]['status'])

    def test_canary_gate_rejects_mismatched_candidate_or_forced_stop(self):
        old = fixture()
        good = {'status': 'passed', 'sha256': 'a' * 64, 'baseline_sha256': release.BASELINE_SHA,
                'original_id': old['Id'], 'canary_id': 'canary-id', 'verification': {'checks': 8},
                'stop': {'cli_ok': True, 'exit_code': 0}}
        for key, value in (('sha256', 'b' * 64), ('original_id', 'other'),
                           ('stop', {'cli_ok': True, 'exit_code': 137}), ('verification', {'checks': 7})):
            receipt = dict(good, **{key: value})
            with self.subTest(key=key), patch.object(release, 'read_private', return_value=receipt), \
                    patch.object(release, 'inspect') as inspect, self.assertRaises(RuntimeError):
                release.canary_gate(old, {'sha256': 'a' * 64})
            inspect.assert_not_called()


if __name__ == '__main__':
    unittest.main()
