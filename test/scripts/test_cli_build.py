"""Actual Bash build entrypoint fail-fast contracts, not packaged runtime acceptance."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from shell_fixture import BASH, bash_directory

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(BASH, 'Bash required for actual build-entrypoint contract')
class CliBuildTest(unittest.TestCase):
    def run_build(self, root, offline, *, package_fixture=False):
        script = root / 'scripts/cli/build-cli.sh'
        script.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / 'scripts/cli/build-cli.sh', script)
        capture = root / 'calls.txt'
        tools = root / 'tools'
        tools.mkdir()
        poison = tools / 'mvn'
        poison.write_text('#!/bin/bash\nexit 99\n', encoding='utf-8', newline='\n')
        poison.chmod(0o755)
        env = dict(os.environ, ONIONALL_OFFLINE=str(int(offline)), CAPTURE=str(capture),
                   PATH=str(tools) + os.pathsep + os.environ['PATH'])
        # The actual script selects and executes its project wrapper. A failing
        # wrapper must stop before packaging.
        command = 'javac() { echo "javac 25.0.5"; }; export -f javac; '
        if package_fixture:
            env['ONIONALL_CONTENT_LIBRARY'] = 'target/content.lib'
            command += ('jar() { [[ $1 == --create && $2 == --file ]] || return 2; '
                        'printf package > "$3"; }; export -f jar; ')
        command += 'exec "$BASH" "$1"'
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

    @unittest.skipUnless(os.name == 'nt', 'Windows drive-qualified JAVA_HOME')
    def test_windows_java_home_selects_jdk_before_path(self):
        with tempfile.TemporaryDirectory(prefix='cli native Java home ') as tmp:
            root = Path(tmp)
            script = root / 'scripts/cli/build-cli.sh'
            script.parent.mkdir(parents=True)
            shutil.copy2(ROOT / 'scripts/cli/build-cli.sh', script)
            jdk = root / 'selected jdk'
            javac = jdk / 'bin/javac'
            javac.parent.mkdir(parents=True)
            javac.write_text('#!/bin/bash\nprintf "%s\\n" "selected" > "$TRACE"\necho "javac 25.0.5"\n',
                             encoding='utf-8', newline='\n')
            javac.chmod(0o755)
            fallback = root / 'fallback/javac'
            fallback.parent.mkdir()
            fallback.write_text('#!/bin/bash\necho "javac 17"\n', encoding='utf-8', newline='\n')
            fallback.chmod(0o755)
            wrapper = root / 'mvnw'
            wrapper.write_text('#!/bin/bash\nexit 42\n', encoding='utf-8', newline='\n')
            wrapper.chmod(0o755)
            trace = root / 'jdk-trace.txt'

            for home in (str(jdk), jdk.as_posix()):
                with self.subTest(home=home):
                    result = subprocess.run([BASH, str(script)], env=dict(
                        os.environ, JAVA_HOME=home, TRACE=str(trace),
                        PATH=str(fallback.parent) + os.pathsep + os.environ['PATH'],
                    ), capture_output=True, text=True, timeout=15)
                    self.assertEqual(42, result.returncode, result.stderr)
                    self.assertEqual('selected', trace.read_text(encoding='utf-8').strip())
                    trace.unlink()

    def test_missing_wrapper_never_falls_back_to_path_maven(self):
        with tempfile.TemporaryDirectory(prefix='missing project wrapper ') as tmp:
            root = Path(tmp)
            result, capture = self.run_build(root, False)
            self.assertEqual(127, result.returncode, result.stderr)
            self.assertFalse(capture.exists())
            self.assertFalse((root / 'target').exists())


    def test_packages_allowlisted_dependencies_from_platform_maven_classpath(self):
        with tempfile.TemporaryDirectory(prefix='cli classpath with spaces ') as tmp:
            root = Path(tmp)
            wrapper = root / 'mvnw'
            wrapper.write_text('#!/bin/bash\nexit 0\n', encoding='utf-8', newline='\n')
            wrapper.chmod(0o755)
            scripts = root / 'scripts/cli'
            scripts.mkdir(parents=True)
            shutil.copy2(ROOT / 'scripts/cli/cli-launcher.sh', scripts / 'cli-launcher.sh')
            target = root / 'target'
            classes = target / 'classes/dev/onionall/brawler'
            for package in ('cli', 'tor', 'tools', 'discovery', 'archive', 'admission', 'crawl', 'research'):
                (classes / package).mkdir(parents=True)
            (target / 'content.lib').write_bytes(b'fixture')
            dependencies = root / 'dependencies with spaces'
            dependencies.mkdir()
            expected = {'picocli-4.7.7.jar': b'cli', 'storm-client-2.8.3.jar': b'storm'}
            for name, body in (expected | {'tika-core-3.2.1.jar': b'not included'}).items():
                (dependencies / name).write_bytes(body)

            # Windows Maven uses drive-prefixed semicolon-separated paths,
            # even when it was invoked from Git Bash.
            paths = sorted(dependencies.iterdir())
            separator = ';' if os.name == 'nt' else ':'
            (target / 'cli-classpath.txt').write_text(
                separator.join(str(path.resolve()) for path in paths) + '\n',
                encoding='utf-8', newline='\r\n' if os.name == 'nt' else '\n',
            )

            result, _ = self.run_build(root, True, package_fixture=True)
            self.assertEqual(0, result.returncode, result.stderr)
            bundle = target / 'onionall'
            for name, body in expected.items():
                self.assertEqual(body, (bundle / 'lib' / name).read_bytes())
            self.assertFalse((bundle / 'lib/tika-core-3.2.1.jar').exists())
            manifest = (bundle / '.files').read_text().splitlines()
            self.assertTrue(all('lib/' + name in manifest for name in expected))


    def test_launcher_passes_native_internal_paths_and_preserves_cli_arguments(self):
        with tempfile.TemporaryDirectory(prefix='onionall launcher with spaces ') as tmp:
            root = Path(tmp)
            application = root / 'installed bundle'
            (application / 'bin').mkdir(parents=True)
            (application / 'lib').mkdir()
            launcher = application / 'bin/onionall'
            shutil.copy2(ROOT / 'scripts/cli/cli-launcher.sh', launcher)
            java = root / 'test jdk/bin/java'
            java.parent.mkdir(parents=True)
            java.write_text('#!/bin/sh\nif [ "$1" = -version ]; then printf \'openjdk version "25.0.5"\\n\'; '
                            'else printf "%s\\n" "$@" > "$TRACE"; fi\n',
                            encoding='utf-8', newline='\n')
            java.chmod(0o755)
            trace = root / 'java-args.txt'
            launch = ('JAVA_HOME=$(cd -- "$1" && pwd -P) || exit; export JAVA_HOME; '
                      'shift; exec "$BASH" "$@"')
            query = ['research', 'search', '--query', 'two distinct words']
            completed = subprocess.run(
                [BASH, '-c', launch, 'launcher-fixture', str(java.parent.parent), str(launcher), *query],
                env=dict(os.environ, TRACE=str(trace)), capture_output=True, text=True, timeout=10,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            args = trace.read_text().splitlines()
            if os.name == 'nt':
                directory = subprocess.check_output(
                    [BASH, '-c', 'cygpath -am -- "$1"', 'launcher-fixture', str(application)], text=True, timeout=5,
                ).strip()
                native = 'onionall_content.dll'
            else:
                directory = bash_directory(application)
                native = 'libonionall_content.dylib' if sys.platform == 'darwin' else 'libonionall_content.so'
            self.assertIn('-Donionall.content.library=' + directory + '/lib/' + native, args)
            self.assertEqual(directory + '/lib/*', args[args.index('-cp') + 1])
            self.assertEqual(query, args[-len(query):])


if __name__ == '__main__':
    unittest.main()
