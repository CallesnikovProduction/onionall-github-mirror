"""Packaged RPC dependency closure and fail-closed behavior; loopback only."""
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'target/onionall/bin/onionall'
URL = 'http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion/'


@unittest.skipUnless(BUNDLE.is_file(), 'Build the packaged CLI first')
class AdmissionDistributionTest(unittest.TestCase):
    def test_packaged_rpc_runtime_loads_and_unavailable_frontier_never_admits(self):
        with tempfile.TemporaryDirectory() as directory, socket.socket() as unavailable:
            root = Path(directory)
            unavailable.bind(('127.0.0.1', 0))  # Reserved but not listening; no external service.
            archive = root / 'archive'
            def cli(*args):
                result = subprocess.run([str(BUNDLE), '--json', '--archive-dir', str(archive), *args],
                                        capture_output=True, text=True, timeout=20)
                return result.returncode, json.loads(result.stdout)
            code, plan = cli('crawl', 'plan', '--seed', URL, '--max-pages', '2')
            self.assertEqual(code, 0)
            job = plan['plan_id']
            self.assertEqual(cli('crawl', 'approve', job)[0], 0)
            runtime = root / 'runtime.json'
            runtime.write_text(json.dumps(dict(frontier_host='127.0.0.1', frontier_port=unavailable.getsockname()[1])))
            token = root / 'token'
            token.write_text('a' * 32)
            code, response = cli('crawl', 'status', job, '--runtime', str(runtime), '--admission-token-file', str(token))
            self.assertNotEqual(code, 0)
            self.assertIn('UNAVAILABLE', json.dumps(response))
            self.assertNotIn('ClassNotFound', json.dumps(response))
            self.assertNotIn('a' * 32, json.dumps(response))
            code, status = cli('crawl', 'status', job)
            self.assertEqual(code, 0)
            self.assertEqual(status['state'], 'APPROVED')
            self.assertNotIn('authoritative_admission', status)


if __name__ == '__main__':
    unittest.main()
