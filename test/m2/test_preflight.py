import tempfile
from pathlib import Path
import unittest
from tor_preflight import progress, run


class PreflightTest(unittest.TestCase):
    def test_progress_is_observed_not_inferred(self):
        self.assertIsNone(progress('Socks listener ready; TLS_ERROR'))
        self.assertEqual(14, progress('Bootstrapped 10%\nBootstrapped 14%'))
        self.assertEqual(100, progress('Bootstrapped 100% (done): Done'))

    def test_last_progress_wins_after_restart(self):
        self.assertEqual(10, progress('Bootstrapped 100%\nBootstrapped 10%'))

    def test_invalid_deadline_does_not_launch_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                run(Path(directory), 0)
