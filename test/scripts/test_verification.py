"""Verification signal tests, including deliberately failing disposable fixtures."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('project_verification', ROOT / 'scripts/runners/verify.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class VerificationTest(unittest.TestCase):
    def test_required_skip_is_not_green_and_failure_wins(self):
        self.assertEqual(verify.aggregate([dict(status='PASS'), dict(status='NOT RUN')]), ('NOT RUN', 2))
        self.assertEqual(verify.aggregate([dict(status='NOT RUN'), dict(status='FAIL')]), ('FAIL', 1))

    def test_broken_link_fails_then_passes_after_target_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'README.md').write_text('[bad](missing.md)\n[web](https://example.invalid/x)\n'
                                           '```sh\n[example](ignored.md)\n```\n', encoding='utf-8')
            self.assertEqual(verify.markdown_links(root, ['README.md']),
                             [dict(file='README.md', target='missing.md')])
            (root / 'missing.md').write_text('ok', encoding='utf-8')
            self.assertEqual(verify.markdown_links(root, ['README.md']), [])

    def test_reference_encoded_and_root_relative_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'space name.md').touch()
            (root / 'README.md').write_text('[ref]: <space%20name.md>\n[x](/space%20name.md#heading)\n', encoding='utf-8')
            self.assertEqual(verify.markdown_links(root, ['README.md']), [])

    def test_real_failed_unittest_is_not_hidden(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'test_red.py').write_text('import unittest\nclass Red(unittest.TestCase):\n'
                                            ' def test_red(self): self.assertEqual(1, 2)\n', encoding='utf-8')
            run = verify.Verification(root)
            code, log = run.run('deliberate-red', [sys.executable, '-B', '-m', 'unittest', 'discover', '-s', tmp])
            self.assertEqual(code, 1)
            self.assertIn('FAILED (failures=1)', log.read_text(encoding='utf-8'))

    def test_missing_or_wrong_frontier_cannot_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = verify.Verification(Path(tmp))
            run.frontier(None, None)
            self.assertEqual(run.gates[-1]['status'], 'NOT RUN')
            jar = Path(tmp) / 'not-a-jar'
            jar.write_bytes(b'untrusted')
            run.frontier(jar, tmp)
            self.assertEqual(run.gates[-1]['status'], 'FAIL')


if __name__ == '__main__':
    unittest.main()
