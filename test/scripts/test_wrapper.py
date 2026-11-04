"""Pinned wrapper/source contract; Windows runtime checked separately in ledger."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class WrapperTest(unittest.TestCase):
    def test_version_and_distribution_checksum_are_pinned(self):
        props = dict(line.split('=', 1) for line in
                     (ROOT / '.mvn/wrapper/maven-wrapper.properties').read_text().splitlines())
        self.assertEqual(props['wrapperVersion'], '3.3.4')
        self.assertEqual(props['distributionType'], 'only-script')
        self.assertEqual(props['distributionUrl'], 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.zip')
        self.assertEqual(props['distributionSha256Sum'], '1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff')
        self.assertFalse((ROOT / '.mvn/wrapper/maven-wrapper.jar').exists())

    def test_both_source_scripts_enforce_distribution_checksum(self):
        for name in ('mvnw', 'mvnw.cmd'):
            self.assertIn('distributionSha256Sum', (ROOT / name).read_text())


if __name__ == '__main__':
    unittest.main()
