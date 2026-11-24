import json
import subprocess
import tempfile
from pathlib import Path
import unittest
from unittest.mock import Mock, patch
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

    def test_missing_wire_proof_or_unknown_fields_cannot_qualify_readiness(self):
        fields = dict(socks_usable=True, remote_hostname_sent=True, socks_atyp=3,
                      http_status=200, is_tor=True, transport_running=True,
                      current_bootstrap_complete=True)
        for proof, ready in [(fields, True), ({**fields, 'socks_atyp': 1}, False),
                             ({**fields, 'http_status': 302, 'image': 'unexpected'}, False)]:
            with self.subTest(proof=proof), tempfile.TemporaryDirectory() as directory:
                path = Path(directory)
                child = Mock()

                def boot(*args, **kwargs):
                    kwargs['stdout'].write('Bootstrapped 100%\n')
                    kwargs['stdout'].flush()
                    return child

                def docker(args, **kwargs):
                    return subprocess.CompletedProcess(args, 0, stdout=json.dumps(proof).encode(), stderr=b'')

                with (patch('tor_preflight.bridge_file', return_value=path / 'private fixture'),
                      patch('tor_preflight.subprocess.Popen', side_effect=boot),
                      patch('tor_preflight.subprocess.run', side_effect=docker) as docker_call):
                    result = run(path, 15)
                self.assertEqual(ready, result['ready'])
                self.assertEqual(ready, result['blocker'] is None)
                self.assertNotEqual('unexpected', result['image'])
                self.assertTrue(result['container_removed'])
                self.assertEqual(['exec', 'rm'], [call.args[0][1] for call in docker_call.call_args_list])
