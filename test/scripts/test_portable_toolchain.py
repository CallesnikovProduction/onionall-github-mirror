"""Execute launcher with no host JVM/Python; fake Docker checks orchestration only."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PortableToolchainTest(unittest.TestCase):
    def launch(self, args, fail='', runtime_socket=False):
        self.temporary = tempfile.TemporaryDirectory(prefix='portable checkout ')
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        shutil.copy2(ROOT / 'onionall', root / 'onionall')
        tools = root / 'tools'
        tools.mkdir()
        for tool in ('bash', 'dirname', 'id', 'mkdir', 'cat', 'stat'):
            (tools / tool).symlink_to(shutil.which(tool))
        for tool in ('java', 'javac', 'mvn', 'python3'):
            path = tools / tool
            path.write_text('#!/bin/sh\nexit 98\n')
            path.chmod(0o755)
        docker = tools / 'docker'
        docker.write_text('''#!/usr/bin/python3
import json, os, sys
with open(os.environ['CALLS'], 'a') as stream:
    stream.write(json.dumps(sys.argv[1:]) + '\\n')
if os.environ.get('FAIL') == sys.argv[1]:
    sys.exit(42)
''')
        docker.chmod(0o755)
        calls = root / 'calls.jsonl'
        env = dict(os.environ, PATH=str(tools), JAVA_HOME='/absent', CALLS=str(calls), FAIL=fail)
        if runtime_socket:
            sock = socket.socket(socket.AF_UNIX)
            self.addCleanup(sock.close)
            path = root / 'docker.sock'
            sock.bind(str(path))
            env['DOCKER_HOST'] = 'unix://' + str(path)
        result = subprocess.run([str(tools / 'bash'), str(root / 'onionall'), *args],
                                cwd='/', env=env, capture_output=True, text=True, timeout=20)
        return result, [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else [], root

    def test_build_without_host_java_maven_or_python_and_with_spaces(self):
        result, calls, root = self.launch(['build', '--clean', '--images'])
        self.assertEqual(0, result.returncode, result.stderr)
        run = next(call for call in calls if call[0] == 'run')
        self.assertEqual(['bash', 'docker/toolchain/build.sh', 'build', '--clean'], run[-4:])
        self.assertIn(f'type=bind,src={root},dst={root}', run)
        self.assertIn('--user', run)
        self.assertNotIn('-e JAVA_HOME', ' '.join(run))
        self.assertTrue(any('docker/storm-lifecycle/Dockerfile.portable' in c for c in calls))
        self.assertTrue((root / '.runtime/maven-linux-repository').is_dir())

    def test_test_dispatches_inside_container(self):
        result, calls, _ = self.launch(['test'])
        self.assertEqual(0, result.returncode, result.stderr)
        run = next(call for call in calls if call[0] == 'run')
        self.assertEqual(['bash', 'docker/toolchain/build.sh', 'test', ''], run[-4:])

    def test_container_failure_stops_before_image_packaging(self):
        result, calls, _ = self.launch(['build', '--images'], fail='run')
        self.assertEqual(42, result.returncode)
        self.assertEqual(1, sum(call[0] == 'build' for call in calls))

    def test_missing_daemon_creates_no_cache(self):
        result, calls, root = self.launch(['build'], fail='info')
        self.assertEqual(69, result.returncode)
        self.assertEqual(1, len(calls))
        self.assertFalse((root / '.runtime').exists())

    def test_lifecycle_runs_controller_with_caller_and_same_checkout_path(self):
        result, calls, root = self.launch(['--fixture', 'down'], runtime_socket=True)
        self.assertEqual(0, result.returncode, result.stderr)
        run = next(call for call in calls if call[0] == 'run')
        self.assertEqual(['python3', '-B', 'scripts/runners/runtime.py', '--fixture', 'down'], run[-5:])
        self.assertIn(f'type=bind,src={root},dst={root}', run)
        self.assertIn('--group-add', run)
        self.assertNotIn('--volumes', run)
        self.assertNotIn('-v', run)

    def test_help_does_not_contact_engine(self):
        result, calls, _ = self.launch(['--help'])
        self.assertEqual(0, result.returncode)
        self.assertEqual([], calls)


if __name__ == '__main__':
    unittest.main()
