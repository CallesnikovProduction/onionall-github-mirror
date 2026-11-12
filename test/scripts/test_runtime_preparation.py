"""Real filesystem checks for token preparation; no Docker or network access."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('runtime_preparation', ROOT / 'scripts/runners/runtime.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimePreparationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for artifact in ('target/onionall/bin/onionall',
                         'target/onionall-brawler-0.1.0-SNAPSHOT.jar',
                         'frontier-server/target/urlfrontier-admission.jar'):
            path = self.root / artifact
            path.parent.mkdir(parents=True, exist_ok=True)
            path.touch()
        self.enterContext(patch.object(runtime, 'ROOT', self.root))
        # Контроллер предназначен для Linux-контейнера; здесь проверяем файловый контракт.
        self.enterContext(patch.object(runtime.os, 'getuid', return_value=1000, create=True))
        self.enterContext(patch.object(runtime.os, 'getgid', return_value=1000, create=True))
        self.instance = runtime.Runtime(fixture=True)
        self.token = self.instance.state / 'admission-token'

    def prepare_on_platform(self):
        if os.name == 'nt':
            # Windows chmod не доказывает POSIX mode 600: отказ должен сохраниться.
            with self.assertRaisesRegex(RuntimeError, 'private regular file'):
                self.instance.prepare()
        else:
            self.instance.prepare()

    def test_real_token_creation_requests_private_mode_and_preserves_existing_value(self):
        with patch.object(runtime.os, 'open', wraps=os.open) as opened:
            self.prepare_on_platform()
        self.assertTrue(any(args[0] == str(self.token) and args[2] == 0o600
                            for args, _ in opened.call_args_list))
        value = self.token.read_text()
        self.assertRegex(value, r'\A[0-9a-f]{64}\Z')
        if os.name == 'posix':
            self.assertEqual(0o600, self.token.stat().st_mode & 0o777)
            self.assertTrue((self.instance.state / 'runtime.json').is_file())
        else:
            self.assertFalse((self.instance.state / 'runtime.json').exists())
        self.prepare_on_platform()
        self.assertEqual(value, self.token.read_text())

    def test_exclusive_open_refuses_a_token_created_after_existence_check(self):
        self.instance.state.mkdir(parents=True)
        self.token.write_text('existing-fixture-capability')
        real_exists = Path.exists
        with patch.object(Path, 'exists', lambda path: False if path == self.token else real_exists(path)):
            with self.assertRaises(FileExistsError):
                self.instance.prepare()
        self.assertEqual('existing-fixture-capability', self.token.read_text())
        self.assertFalse((self.instance.state / 'runtime.json').exists())

    @unittest.skipUnless(shutil.which('docker'), 'Compose CLI required; Docker Engine is not needed')
    def test_compose_keeps_tmpfs_options_on_one_absolute_mount(self):
        environment = dict(os.environ, ONIONALL_RUNTIME_DIR=str(self.instance.state),
                           ONIONALL_RUNTIME_USER='1000:1000')
        result = subprocess.run(['docker', 'compose', '-f', str(ROOT / 'compose.runtime.yaml'),
                                 '--profile', 'fixture', '--profile', 'tools', 'config', '--format', 'json'],
                                env=environment, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)
        services = json.loads(result.stdout)['services']
        for name in ('frontier', 'crawler', 'probe'):
            self.assertEqual(['/tmp:rw,exec,nosuid,nodev,mode=1777'], services[name]['tmpfs'], name)
            self.assertTrue(services[name]['read_only'], name)

    def test_research_forwarding_retains_explicit_runtime_and_never_inserts_approval(self):
        for action in ('run', 'results'):
            with patch.object(self.instance, 'check_docker'), patch.object(self.instance, 'dc') as dc:
                self.instance.cli(['research', action, '--runtime=/chosen/runtime.json',
                                   '--admission-token-file=/chosen/token'])
            arguments = dc.call_args.args
            self.assertNotIn('--approve', arguments)
            self.assertNotIn('/state/runtime.json', arguments)
            self.assertNotIn('/state/admission-token', arguments)

    def test_operator_proxy_survives_prepare_and_invalid_value_has_no_fallback(self):
        self.instance.state.mkdir(parents=True)
        descriptor = self.instance.state / 'runtime.json'
        descriptor.write_text(json.dumps(dict(socks_host='operator-gateway', socks_port=9150)))
        self.assertEqual(('operator-gateway', 9150), self.instance.configured_proxy())
        if os.name == 'posix':
            self.instance.prepare()
            self.assertEqual(('operator-gateway', 9150), self.instance.configured_proxy())
        descriptor.write_text(json.dumps(dict(socks_host='target.onion', socks_port=9050)))
        with self.assertRaisesRegex(RuntimeError, 'no default fallback'):
            self.instance.configured_proxy()

    def test_proxy_commands_receive_descriptor_without_capability_or_approval(self):
        for command in (['config', 'proxy', 'show'], ['system', 'doctor'], ['research', 'discover']):
            with patch.object(self.instance, 'check_docker'), patch.object(self.instance, 'dc') as dc:
                self.instance.cli(command)
            self.assertIn('/state/runtime.json', dc.call_args.args)
            self.assertNotIn('/state/admission-token', dc.call_args.args)
            self.assertNotIn('--approve', dc.call_args.args)

    def test_batch_status_and_deadline_forwarding(self):
        with patch.object(self.instance, 'check_docker'), patch.object(self.instance, 'dc') as dc:
            self.instance.cli(['--json', 'research', 'status', 'session'])
        self.assertIn('/state/runtime.json', dc.call_args.args)
        self.assertIn('/state/admission-token', dc.call_args.args)
        with patch.object(self.instance, 'check_docker'), patch.object(self.instance, 'dc') as dc:
            self.instance.cli(['research', 'run', '--session', 'session', '--total-seconds=1200'])
        self.assertEqual(1440, dc.call_args.kwargs['timeout'])
        self.assertNotIn('--approve', dc.call_args.args)

    def test_global_options_do_not_hide_commands_or_consume_option_values_as_commands(self):
        for prefix in (['--json'], ['--archive-dir', 'research'],
                       ['--json', '--archive-dir=/chosen/archive', '--no-color']):
            for command, capability in ((['research', 'run'], True),
                                        (['crawl', 'status'], True),
                                        (['config', 'proxy', 'show'], False),
                                        (['system', 'doctor'], False)):
                with self.subTest(prefix=prefix, command=command):
                    with patch.object(self.instance, 'check_docker'), patch.object(self.instance, 'dc') as dc:
                        self.instance.cli([*prefix, *command])
                    forwarded = dc.call_args.args
                    self.assertIn('/state/runtime.json', forwarded)
                    self.assertEqual(capability, '/state/admission-token' in forwarded)
                    self.assertNotIn('--approve', forwarded)
        with patch.object(self.instance, 'check_docker'), patch.object(self.instance, 'dc') as dc:
            self.instance.cli(['--archive-dir', 'research', 'search-local', 'run'])
        self.assertNotIn('/state/runtime.json', dc.call_args.args)


if __name__ == '__main__':
    unittest.main()
