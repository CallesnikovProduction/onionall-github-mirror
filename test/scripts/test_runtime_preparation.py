"""Real filesystem checks for token preparation; no Docker or network access."""
import importlib.util
import os
from pathlib import Path
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


if __name__ == '__main__':
    unittest.main()
