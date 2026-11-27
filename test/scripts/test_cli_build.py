"""Actual Bash build entrypoint fail-fast contracts, not packaged runtime acceptance."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from shell_fixture import BASH, bash_directory

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(BASH, 'Bash required for actual build-entrypoint contract')
class CliBuildTest(unittest.TestCase):
    def run_build(self, root, offline):
        script = root / 'scripts/cli/build-cli.sh'
        script.parent.mkdir(parents=True)
        shutil.copy2(ROOT / 'scripts/cli/build-cli.sh', script)
        capture = root / 'calls.txt'
        tools = root / 'tools'
        tools.mkdir()
        poison = tools / 'mvn'
        poison.write_text('#!/bin/bash\nexit 99\n', encoding='utf-8', newline='\n')
        poison.chmod(0o755)
        env = dict(os.environ, ONIONALL_OFFLINE=str(int(offline)), CAPTURE=str(capture),
                   PATH=str(tools) + os.pathsep + os.environ['PATH'])
        # Only the version preflight is faked; the actual script selects and executes
        # its project wrapper. A failing wrapper must stop before any packaging.
        command = 'javac() { echo "javac 25.0.5"; }; export -f javac; exec bash "$1"'
        result = subprocess.run([BASH, '-c', command, 'build-contract', str(script)],
                                env=env, capture_output=True, text=True, timeout=15)
        return result, capture

    def test_project_wrapper_failure_propagates_with_offline_flag(self):
        with tempfile.TemporaryDirectory(prefix='cli build with spaces ') as tmp:
            root = Path(tmp)
            wrapper = root / 'mvnw'
            wrapper.write_text('#!/bin/bash\nprintf "%s\\n" "$@" > "$CAPTURE"\nexit 42\n', encoding='utf-8', newline='\n')
            wrapper.chmod(0o755)
            result, capture = self.run_build(root, True)
            self.assertEqual(42, result.returncode, result.stderr)
            shell_root = bash_directory(root)
            self.assertEqual(['-B', '-o', '-f', shell_root + '/crawler/pom.xml',
                              'verify', 'dependency:build-classpath',
                              '-Dmdep.outputFile=' + shell_root + '/target/cli-classpath.txt'],
                             capture.read_text().splitlines())
            self.assertFalse((root / 'target').exists())

    def test_missing_wrapper_never_falls_back_to_path_maven(self):
        with tempfile.TemporaryDirectory(prefix='missing project wrapper ') as tmp:
            root = Path(tmp)
            result, capture = self.run_build(root, False)
            self.assertEqual(127, result.returncode, result.stderr)
            self.assertFalse(capture.exists())
            self.assertFalse((root / 'target').exists())


if __name__ == '__main__':
    unittest.main()
