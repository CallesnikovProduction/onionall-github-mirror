"""Actual shell ordering, no Docker, Tor or external sockets."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class SameContainerReadinessTest(unittest.TestCase):
    def run_shell(self, enabled, code=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trace = root / 'calls'
            for command in ('java', 'storm'):
                stub = root / command
                stub.write_text('#!/bin/sh\nprintf "%s\\n" ' + command + ' >> "$TRACE"\n'
                                + ('exit "$STUB_STATUS"\n' if command == 'java' else 'exit 0\n'))
                stub.chmod(0o700)
            env = dict(os.environ, PATH=str(root), TRACE=str(trace), STUB_STATUS=str(code),
                       M2_RUN_REL='.runtime/m2/fixture', M2_VERIFY_BEFORE_CRAWL=str(enabled),
                       M2_SERIES_OUTPUT='resumed-series.json')
            result = subprocess.run(['/bin/bash', str(ROOT / 'scripts/run-tor-crawl-container.sh')], env=env,
                                    capture_output=True, text=True, timeout=3)
            return result.returncode, trace.read_text().splitlines() if trace.exists() else []

    def test_invalid_opt_in_cannot_silently_start_crawler(self):
        self.assertEqual((2, []), self.run_shell('invalid'))

    def test_default_variant_preserves_original_direct_storm_launch(self):
        self.assertEqual((0, ['storm']), self.run_shell(0))

    def test_opt_in_series_runs_before_storm_in_same_container(self):
        self.assertEqual((0, ['java', 'storm']), self.run_shell(1))

    def test_failed_series_prevents_crawler_start_without_retry(self):
        self.assertEqual((7, ['java']), self.run_shell(1, 7))


if __name__ == '__main__':
    unittest.main()
