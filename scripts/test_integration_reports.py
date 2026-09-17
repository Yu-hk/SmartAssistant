from pathlib import Path
import tempfile
import unittest
from verify_integration_reports import verify


class IntegrationReportTest(unittest.TestCase):
    def test_accepts_executed_success(self):
        self.check('<testsuite tests="7" failures="0" errors="0" skipped="0"/>', True)

    def test_rejects_skip_empty_failure_and_short_suite(self):
        for xml in ['<testsuite tests="7" skipped="7"/>', '<testsuite tests="0"/>',
                    '<testsuite tests="7" errors="1"/>', '<testsuite tests="6"/>',
                    '<testsuite tests="7"><testcase><skipped/></testcase></testsuite>']:
            self.check(xml, False)

    def test_missing_report_is_not_success(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(OSError):
                verify(Path(folder) / 'absent.xml')

    def check(self, xml, success):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'report.xml'
            path.write_text(xml, encoding='utf-8')
            if success:
                verify(path, 7)
            else:
                with self.assertRaises(ValueError):
                    verify(path, 7)
