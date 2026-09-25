import tempfile
import unittest
from pathlib import Path

from verify_jacoco_class import coverage


class CoverageGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.report = Path(self.directory.name) / "jacoco.xml"
        self.report.write_text("""<report><package name="example">
            <class name="example/Foo">
              <counter type="INSTRUCTION" missed="2" covered="8"/>
              <counter type="BRANCH" missed="1" covered="3"/>
            </class></package></report>""", encoding="utf-8")

    def tearDown(self):
        self.directory.cleanup()

    def test_reads_named_class_not_package_total(self):
        self.assertEqual((8, 2), coverage(self.report, "example/Foo", "INSTRUCTION"))
        self.assertEqual((3, 1), coverage(self.report, "example/Foo", "BRANCH"))

    def test_missing_class_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "found 0"):
            coverage(self.report, "example/Bar", "INSTRUCTION")

    def test_empty_counter_fails_closed(self):
        self.report.write_text("""<report><class name="example/Foo">
            <counter type="BRANCH" missed="0" covered="0"/>
            </class></report>""", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "empty BRANCH"):
            coverage(self.report, "example/Foo", "BRANCH")


if __name__ == "__main__":
    unittest.main()
