"""Canonical Compose mount contract. Container execution is unnecessary."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class RuntimeComposeTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('docker'), 'Compose CLI required; Docker Engine is not needed')
    def test_compose_keeps_tmpfs_options_on_one_absolute_mount(self):
        with tempfile.TemporaryDirectory() as directory:
            environment = dict(os.environ, ONIONALL_RUNTIME_DIR=str(Path(directory) / 'local-fixture'),
                               ONIONALL_RUNTIME_USER='1000:1000')
            result = subprocess.run(['docker', 'compose', '-f', str(ROOT / 'compose.runtime.yaml'),
                                     '--profile', 'fixture', '--profile', 'tools', 'config', '--format', 'json'],
                                    env=environment, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)
        services = json.loads(result.stdout)['services']
        for name in ('frontier', 'crawler', 'probe'):
            self.assertEqual(['/tmp:rw,exec,nosuid,nodev,mode=1777'], services[name]['tmpfs'], name)
            self.assertTrue(services[name]['read_only'], name)


if __name__ == '__main__':
    unittest.main()
