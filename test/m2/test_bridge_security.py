"""Private-config regressions use generated dummy bridges, never user secrets."""
import base64
from contextlib import redirect_stdout
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location(
    'bridge_entrypoint', Path(__file__).resolve().parents[2] / 'docker/m2/entrypoint.py')
entrypoint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entrypoint)


def dummy_bridge(address='192.0.2.1:443'):
    # Documentation-only address, dummy fingerprint and dummy certificate.
    certificate = base64.b64encode(bytes(range(52))).decode().rstrip('=')
    return 'Bridge obfs4 ' + address + ' ' + 'A' * 40 + ' cert=' + certificate + ' iat-mode=0'


class BridgeSecurityTest(unittest.TestCase):
    def setUp(self):
        previous = os.umask(0o077)
        os.umask(previous)
        self.addCleanup(os.umask, previous)

    def test_one_or_multiple_bridge_lines_accept_bom_crlf_at_file_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'secret'
            path.write_bytes(('\ufeff# Comment\r\n' + dummy_bridge() + '\r\n\r\n' +
                              dummy_bridge('[2001:db8::1]:8443') + '\r\n').encode())
            lines = entrypoint.bridge_lines(path.read_text(encoding='utf-8-sig'))
            self.assertEqual(2, len(lines))
            self.assertNotIn('\r', ''.join(lines))

    def test_reject_config_injection_and_invalid_bridge_without_echo(self):
        invalid = [dummy_bridge() + '\nUseBridges 0',
                   dummy_bridge().replace('iat-mode=0', 'iat-mode=3'),
                   dummy_bridge().replace('192.0.2.1:443', 'secret-address.invalid:443'),
                   dummy_bridge().replace('cert=', 'secret-cert='),
                   dummy_bridge().replace('A' * 40, 'SECRET_FINGERPRINT'),
                   'HTTPSProxy private-proxy.invalid:443']
        for text in invalid:
            with self.subTest(case=invalid.index(text)):
                with self.assertRaises(ValueError) as caught:
                    entrypoint.bridge_lines(text)
                self.assertNotIn('192.0.2.1', str(caught.exception))
                self.assertNotIn('secret', str(caught.exception).lower())
                self.assertNotIn('AAAA', str(caught.exception))

    def test_empty_and_excessive_configuration_fail_closed(self):
        for text in ['', '# no bridges', (dummy_bridge() + '\n') * 33, 'x' * 65537]:
            with self.assertRaises(ValueError):
                entrypoint.bridge_lines(text)

    def test_missing_or_invalid_encoding_never_starts_tor(self):
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / 'secret'
            for content in [None, b'Bridge obfs4 SECRET_ADDRESS \xff PRIVATE_CERT']:
                if content is not None:
                    secret.write_bytes(content)
                output = io.StringIO()
                with patch.object(entrypoint, 'SECRET', secret), patch.object(entrypoint.subprocess, 'run') as run:
                    with redirect_stdout(output):
                        self.assertEqual(1, entrypoint.main())
                run.assert_not_called()
                self.assertNotIn('SECRET_ADDRESS', output.getvalue())
                self.assertNotIn('PRIVATE_CERT', output.getvalue())

    def invoke(self, verification_code, raw_logs=''):
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / 'secret'
            secret.write_text(dummy_bridge())
            base = Path(directory) / 'base'
            base.write_text('SocksPort 0\n')
            process = Mock()
            process.stdout = io.StringIO(raw_logs)
            process.wait.return_value = 0
            output = io.StringIO()
            def verify(command, **kwargs):
                generated = Path(command[command.index('-f') + 1])
                text = generated.read_text()
                self.assertIn('UseBridges 1\n', text)
                self.assertIn('ClientTransportPlugin obfs4 exec /usr/bin/obfs4proxy', text)
                self.assertEqual(0o600, generated.stat().st_mode & 0o777)
                return subprocess.CompletedProcess(command, verification_code, dummy_bridge(), dummy_bridge())
            with patch.object(entrypoint, 'SECRET', secret), \
                 patch.object(entrypoint, 'command_args', return_value=(base, [])), \
                 patch.object(entrypoint.subprocess, 'run', side_effect=verify), \
                 patch.object(entrypoint.subprocess, 'Popen', return_value=process) as popen, \
                 patch.object(entrypoint.signal, 'signal'):
                with redirect_stdout(output):
                    code = entrypoint.main()
            self.assertNotIn(dummy_bridge(), output.getvalue())
            self.assertNotIn('192.0.2.1', output.getvalue())
            self.assertNotIn('cert=', output.getvalue())
            return code, output.getvalue(), popen.call_count

    def test_tor_verify_errors_are_withheld_and_no_fallback_started(self):
        code, output, launches = self.invoke(1)
        self.assertEqual(1, code)
        self.assertEqual(0, launches)
        self.assertIn('TOR_CONFIG_ERROR', output)

    def test_runtime_warnings_errors_transport_and_bootstrap_are_sanitized(self):
        logs = ('[notice] Registered client transport obfs4 ' + dummy_bridge() + '\n' +
                '[warn] Could not reach ' + dummy_bridge() + '\n' +
                '[err] Private state ' + dummy_bridge() + '\n' +
                '[notice] Bootstrapped 100% (done): Done ' + dummy_bridge() + '\n')
        code, output, launches = self.invoke(0, logs)
        self.assertEqual(0, code)
        self.assertEqual(1, launches)
        self.assertIn('TOR_TRANSPORT_STARTED: obfs4', output)
        self.assertIn('Bootstrapped 100%', output)
        self.assertIn('TOR_WARNING:', output)
        self.assertIn('TOR_ERROR:', output)

    def test_runtime_cannot_override_bridge_requirement_or_transport(self):
        for args in [['--UseBridges', '0'], ['--ClientTransportPlugin', 'obfs4 exec unsafe'],
                     ['-f', '/private/foreign-torrc']]:
            with self.assertRaises(ValueError):
                entrypoint.command_args(args)
