"""Isolated install entrypoint checks: no build, Docker, network or user paths."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class InstallShellTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='onionall scripts ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repository with spaces'
        scripts = self.root / 'scripts'
        scripts.mkdir(parents=True)
        (scripts / 'cli').mkdir()
        self.script = scripts / 'cli/install-cli.sh'
        shutil.copy2(ROOT / 'scripts/cli/install-cli.sh', self.script)
        (scripts / 'cli/build-cli.sh').write_text('printf invoked > "$(dirname "$0")/build-called"\n')
        bundle = self.root / 'target/onionall'
        (bundle / 'bin').mkdir(parents=True)
        (bundle / 'lib').mkdir()
        (bundle / '.onionall-cli').write_text('onionall-cli-v1\n')
        (bundle / '.files').write_text('bin/onionall\nlib/example-1.jar\n')
        (bundle / 'bin/onionall').write_text('#!/bin/sh\nexit 0\n')
        (bundle / 'lib/example-1.jar').write_bytes(b'fixture')
        self.environment = dict(os.environ, XDG_DATA_HOME=str(Path(self.temp.name) / 'data with spaces'),
                                XDG_BIN_HOME=str(Path(self.temp.name) / 'bin with spaces'))

    def run_script(self, *args):
        return subprocess.run(['bash', str(self.script), *args], cwd='/', env=self.environment,
                              capture_output=True, text=True, timeout=10)

    def test_invalid_arguments_rejected_before_build(self):
        for args in [('',), ('', 'extra'), ('--unknown',), ('--no-build', 'extra')]:
            with self.subTest(args=args):
                (self.root / 'scripts/cli/build-called').unlink(missing_ok=True)
                result = self.run_script(*args)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse((self.root / 'scripts/cli/build-called').exists())
                self.assertFalse(Path(self.environment['XDG_DATA_HOME']).exists())

    def test_spaces_other_cwd_and_missing_executable_bit(self):
        self.script.chmod(0o644)
        for _ in range(2):  # Update an owned installation without losing its identity.
            result = self.run_script('--no-build')
            self.assertEqual(0, result.returncode, result.stderr)
        app = Path(self.environment['XDG_DATA_HOME']) / 'onionall/cli'
        self.assertEqual(b'fixture', (app / 'lib/example-1.jar').read_bytes())
        self.assertEqual(str(app / 'bin/onionall'), os.readlink(Path(self.environment['XDG_BIN_HOME']) / 'onionall'))

    def test_cleanup_failure_preserves_original_command_status(self):
        tools = Path(self.temp.name) / 'fake tools'
        tools.mkdir()
        for name, status in [('cp', 37), ('rm', 41)]:
            command = tools / name
            command.write_text('#!/bin/sh\nexit ' + str(status) + '\n')
            command.chmod(0o755)
        self.environment['PATH'] = str(tools) + os.pathsep + os.environ['PATH']
        result = self.run_script('--no-build')
        self.assertEqual(37, result.returncode, result.stderr)
        self.assertIn('Installation cleanup failed (exit 41)', result.stderr)

    def test_build_cleanup_preserves_packaging_failure(self):
        shutil.copy2(ROOT / 'scripts/cli/build-cli.sh', self.root / 'scripts/cli/build-cli.sh')
        for package in ('cli', 'tor', 'tools', 'discovery', 'archive', 'admission', 'crawl', 'research'):
            (self.root / 'target/classes/dev/onionall/brawler' / package).mkdir(parents=True)
        tools = Path(self.temp.name) / 'build tools'
        tools.mkdir()
        wrapper = self.root / 'mvnw'
        wrapper.write_text('#!/bin/sh\nexit 0\n', encoding='utf-8', newline='\n')
        wrapper.chmod(0o755)
        scripts = {'javac': '#!/bin/sh\nprintf "javac 25.0.5\\n"\n',
                   'jar': '#!/bin/sh\nexit 37\n',
                   'rm': '#!/bin/sh\nexit 41\n'}
        for name, body in scripts.items():
            command = tools / name
            command.write_text(body)
            command.chmod(0o755)
        self.environment.pop('JAVA_HOME', None)
        self.environment['PATH'] = str(tools) + os.pathsep + os.environ['PATH']
        # Resolve the fixture tool directory inside Bash before prepending PATH;
        # Git Bash may otherwise prepend its own GNU tools to the Windows PATH.
        launch = 'tools=$(cd -- "$1" && pwd -P); script=$2; export PATH="$tools:$PATH"; set --; source "$script"'
        result = subprocess.run(['bash', '-c', launch, 'build-fixture', str(tools),
                                 str(self.root / 'scripts/cli/build-cli.sh')], cwd='/',
                                env=self.environment, capture_output=True, text=True, timeout=10)
        self.assertEqual(37, result.returncode, result.stderr)
        self.assertIn('CLI staging cleanup failed', result.stderr)


if __name__ == '__main__':
    unittest.main()
