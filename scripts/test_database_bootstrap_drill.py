import copy
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import database_bootstrap_drill as drill


class BootstrapDrillTests(unittest.TestCase):
    def test_pg_varchar_array_roundtrip_canonicalization(self):
        before = "CHECK (state = ANY ((ARRAY['PENDING'::character varying, 'RETRY'::character varying])::text[]))"
        after = "CHECK (state = ANY (ARRAY[('PENDING'::character varying)::text, ('RETRY'::character varying)::text]))"
        self.assertEqual(drill.canonical_varchar_literal_arrays(before), after)
        self.assertEqual(drill.canonical_varchar_literal_arrays(after), after)

    def test_normalization_retains_values_and_escaped_commas(self):
        before = "(ARRAY['can''t, retry'::character varying, 'x'::character varying])::text[]"
        after = "ARRAY[('can''t, retry'::character varying)::text, ('x'::character varying)::text]"
        self.assertEqual(drill.canonical_varchar_literal_arrays(before), after)
        self.assertNotEqual(drill.canonical_varchar_literal_arrays(before.replace("'x'", "'y'")), after)

    def test_other_casts_not_silently_ignored(self):
        for expression in ["(ARRAY['long'::character varying(2)])::text[]",
                           "(ARRAY[NULL::character varying])::text[]",
                           "(ARRAY[42::integer])::text[]",
                           "(ARRAY[column_name::character varying])::text[]"]:
            self.assertEqual(drill.canonical_varchar_literal_arrays(expression), expression)

    def setUp(self):
        self.meta = {'Id': 'fixture-id', 'Config': {'Labels': {drill.LABEL: 'tag'}},
                     'HostConfig': {'NetworkMode': 'none', 'Binds': None, 'PortBindings': {}, 'Privileged': False,
                                    'Tmpfs': {'/var/lib/postgresql/data': 'rw,size=268435456'}},
                     'Mounts': [{'Type': 'tmpfs', 'Destination': '/var/lib/postgresql/data'}]}

    def test_accepts_owned_ephemeral_fixture(self):
        drill.validate_container(self.meta, 'fixture-id', 'tag')

    def test_rejects_other_container(self):
        with self.assertRaises(ValueError): drill.validate_container(self.meta, 'other', 'tag')

    def test_rejects_wrong_label(self):
        with self.assertRaises(ValueError): drill.validate_container(self.meta, 'fixture-id', 'other')

    def test_rejects_network_ports_privileges_or_bind(self):
        for k, value in [('NetworkMode', 'smart-network'), ('Binds', ['/production:/data']),
                         ('PortBindings', {'5432/tcp': [{}]}), ('Privileged', True)]:
            meta = copy.deepcopy(self.meta); meta['HostConfig'][k] = value
            with self.subTest(k=k), self.assertRaises(ValueError): drill.validate_container(meta, 'fixture-id', 'tag')

    def test_rejects_named_volume(self):
        self.meta['Mounts'][0]['Type'] = 'volume'
        with self.assertRaises(ValueError): drill.validate_container(self.meta, 'fixture-id', 'tag')

    def test_requires_ephemeral_database(self):
        self.meta['HostConfig']['Tmpfs'] = {}
        with self.assertRaises(ValueError): drill.validate_container(self.meta, 'fixture-id', 'tag')

    def test_podman_reports_tmpfs_outside_mounts(self):
        self.meta['Mounts'] = []
        drill.validate_container(self.meta, 'fixture-id', 'tag')

    def test_catalog_identifier_allowlist(self):
        self.assertEqual(drill.qualified('profile_cleanup_job'), 'public."profile_cleanup_job"')
        for name in ['public.users', 'users;DROP DATABASE x', '"users"', '', '../users']:
            with self.assertRaises(ValueError): drill.qualified(name)

    def test_compares_full_snapshot_not_only_count(self):
        drill.compare_snapshots({'a': '2:hash'}, {'a': '2:hash'})
        for other in [{}, {'a': '2:changed'}, {'b': '2:hash'}]:
            with self.assertRaises(AssertionError): drill.compare_snapshots({'a': '2:hash'}, other)
        with self.assertRaises(AssertionError): drill.compare_snapshots({}, {})

    def test_diagnostic_keeps_sqlstate_not_payload(self):
        response = subprocess.CompletedProcess([], 1, b'private result', b'ERROR: 42704\nprivate SQL and password')
        with patch.object(drill.subprocess, 'run', return_value=response):
            with self.assertRaises(RuntimeError) as error: drill.command(['fixture'])
        self.assertEqual(str(error.exception), 'Fixture command failed SQLSTATE=42704')

    def test_refuses_report_overwrite_before_container_access(self):
        with tempfile.TemporaryDirectory() as folder:
            report = pathlib.Path(folder) / 'existing.json'; report.touch()
            with patch.object(drill, 'command') as command:
                with self.assertRaises(ValueError): drill.drill('image', pathlib.Path(folder), report)
                command.assert_not_called()

if __name__ == '__main__': unittest.main()
