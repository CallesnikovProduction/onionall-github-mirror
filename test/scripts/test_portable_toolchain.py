"""Execute launcher with no host JVM/Python; fake Docker checks orchestration only."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest

from shell_fixture import BASH, bash_directory

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(BASH, 'Bash required for portable launcher contract')
class PortableToolchainTest(unittest.TestCase):
    def launch(self, args, fail='', runtime_socket=False):
        self.temporary = tempfile.TemporaryDirectory(prefix='portable checkout ')
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        shutil.copy2(ROOT / 'onionall', root / 'onionall')
        tools = root / 'tools'
        tools.mkdir()
        for tool in ('bash', 'dirname', 'id', 'mkdir', 'cat', 'realpath', 'stat'):
            if os.name == 'nt':
                (tools / tool).write_text(f'#!/usr/bin/bash\nexec /usr/bin/{tool} "$@"\n', newline='\n')
            else:
                (tools / tool).symlink_to(shutil.which(tool))
        if os.name == 'nt':
            (tools / 'cygpath').write_text('#!/usr/bin/bash\nexec /usr/bin/cygpath "$@"\n', newline='\n')
        (root / 'bundle dir').mkdir()
        (root / 'docker/m2').mkdir(parents=True)
        (root / 'docker/m2/Dockerfile.security-candidate').touch()
        for tool in ('java', 'javac', 'mvn', 'python3'):
            path = tools / tool
            path.write_text('#!/bin/sh\nexit 98\n', newline='\n')
            path.chmod(0o755)
        docker = tools / 'docker'
        docker_code = '''#!/usr/bin/python3
import json, os, sys
with open(os.environ['CALLS'], 'a') as stream:
    stream.write(json.dumps(sys.argv[1:]) + '\\n')
if os.environ.get('FAIL') == sys.argv[1]:
    sys.exit(42)
'''
        calls = root / 'calls.jsonl'
        env = dict(os.environ, PATH=str(tools), JAVA_HOME='/absent', CALLS=str(calls), FAIL=fail)
        command = [str(tools / 'bash'), str(root / 'onionall'), *args]
        if os.name == 'nt':
            python_fixture = root / 'docker-fixture.py'
            python_fixture.write_text(docker_code, newline='\n')
            docker.write_text(
                '#!/usr/bin/bash\nexport MSYS2_ARG_CONV_EXCL=*\n'
                'exec "$FIXTURE_PYTHON" -B "$FIXTURE_DOCKER" "$@"\n', newline='\n',
            )
            env.update(FIXTURE_PYTHON=sys.executable, FIXTURE_DOCKER=str(python_fixture))
            # Set the restricted PATH after Bash startup has located its own tools.
            launch = ('tools=$(cd -- "$1" && pwd -P) || exit; shift; '
                      'export PATH="$tools"; exec "$BASH" "$@"')
            command = [BASH, '-c', launch, 'portable-fixture', str(tools), *command[1:]]
        else:
            docker.write_text(docker_code, newline='\n')
        docker.chmod(0o755)
        if runtime_socket:
            sock = socket.socket(socket.AF_UNIX)
            self.addCleanup(sock.close)
            path = root / 'docker.sock'
            sock.bind(str(path))
            env['DOCKER_HOST'] = 'unix://' + str(path)
        result = subprocess.run(command, cwd='/', env=env, capture_output=True, text=True, timeout=20)
        return result, [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else [], root

    def test_build_without_host_java_maven_or_python_and_with_spaces(self):
        result, calls, root = self.launch(['build', '--clean', '--images'])
        self.assertEqual(0, result.returncode, result.stderr)
        run = next(call for call in calls if call[0] == 'run')
        self.assertEqual(['bash', 'docker/toolchain/build.sh', 'build', '--clean'], run[-4:])
        shell_root = bash_directory(root)
        self.assertIn(f'type=bind,src={shell_root},dst={shell_root}', run)
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

    @unittest.skipUnless(hasattr(socket, 'AF_UNIX'), 'Unix socket required for actual socket mount contract')
    def test_lifecycle_runs_controller_with_caller_and_same_checkout_path(self):
        result, calls, root = self.launch(['--fixture', 'down'], runtime_socket=True)
        self.assertEqual(0, result.returncode, result.stderr)
        run = next(call for call in calls if call[0] == 'run')
        self.assertEqual(['onionall-tooling', 'runtime', '--fixture', 'down'], run[-4:])
        shell_root = bash_directory(root)
        self.assertIn(f'type=bind,src={shell_root},dst={shell_root}', run)
        self.assertIn('--group-add', run)
        self.assertNotIn('--volumes', run)
        self.assertNotIn('-v', run)

    def test_help_does_not_contact_engine(self):
        result, calls, _ = self.launch(['--help'])
        self.assertEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_candidate_launcher_has_no_network_or_docker_socket(self):
        result, calls, _ = self.launch(['verify-tor-candidate', 'bundle dir', '.runtime/candidate'])
        self.assertEqual(0, result.returncode, result.stderr)
        run = next(call for call in calls if call[0] == 'run')
        self.assertEqual(['onionall-tooling', 'verify-tor-candidate', '/bundle', '.runtime/candidate'],
                         run[-4:])
        self.assertEqual('none', run[run.index('--network') + 1])
        self.assertIn('--read-only', run)
        self.assertIn('--cap-drop', run)
        self.assertFalse(any('docker.sock' in arg or 'DOCKER_HOST' in arg for arg in run))
        mounts = [run[index + 1] for index, arg in enumerate(run) if arg == '--mount']
        self.assertTrue(any('dst=/bundle,readonly' in mount for mount in mounts))
        self.assertEqual(3, len(mounts))

    def test_candidate_help_and_outside_destination_do_not_start_a_container(self):
        help_result, help_calls, _ = self.launch(['verify-tor-candidate', '--help'])
        self.assertEqual(0, help_result.returncode, help_result.stderr)
        self.assertEqual([], help_calls)
        invalid, calls, root = self.launch(['verify-tor-candidate', 'bundle dir', 'outside'])
        self.assertEqual(1, invalid.returncode)
        self.assertFalse(any(call[0] == 'run' for call in calls))
        self.assertFalse((root / '.runtime').exists())


if __name__ == '__main__':
    unittest.main()
