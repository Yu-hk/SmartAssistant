import unittest
from profile_backup_catalog import catalog_names

class BackupCatalogTest(unittest.TestCase):
    def test_toc_reports_only_public_table_names_not_owners_or_comments(self):
        data = '1; 1259 44 TABLE public user_profiles private-owner\n2; 0 44 TABLE DATA public user_profiles private-owner\n'
        data += '3; 1259 45 TABLE public routing_call_log private-owner\n; private-body\n'
        self.assertEqual(['routing_call_log', 'user_profiles'], catalog_names(data, True))

    def test_sql_is_never_executed_and_copy_payload_is_not_reported(self):
        data = 'CREATE TABLE public.profile_lifecycle (user_id bigint);\nCOPY public.users (id,name) FROM stdin;\n'
        data += '12\tprivate-user-body\nCREATE TABLE private_body_looks_like_ddl (id int);\n\\.\nDROP TABLE profile_lifecycle;\n'
        self.assertEqual(['profile_lifecycle', 'users'], catalog_names(data, False))

if __name__ == '__main__': unittest.main()
