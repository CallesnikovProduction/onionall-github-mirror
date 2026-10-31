import json
from pathlib import Path
import tempfile
import unittest
from bootstrap_evidence import summarize


class BootstrapEvidenceTest(unittest.TestCase):
    def test_historical_success_is_not_live_connectivity(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path/'services.log').write_text('tor-1 | Bootstrapped 100%\nonion-1 | Bootstrapped 100%\ntor-1 | Connection failed\n')
            result = summarize(path)
            self.assertTrue(result['bootstrap_100_observed'])
            self.assertEqual(result['observation_kind'], 'log_history')
            self.assertFalse(result['current_connectivity_verified'])
            self.assertNotIn('bootstrap_complete', result)

    def test_missing_logs_never_imply_readiness(self):
        with tempfile.TemporaryDirectory() as directory:
            result = summarize(Path(directory))
            self.assertFalse(result['bootstrap_100_observed'])
            self.assertFalse(result['current_connectivity_verified'])
