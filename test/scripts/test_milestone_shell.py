"""Entrypoint ownership/exit contracts with fake tools; never infrastructure acceptance."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RunnerShellTest(unittest.TestCase):
    def test_failed_run_survives_failed_cleanup_and_m1_env_precedes_compose(self):
        for name in ('run-controlled-crawl.sh', 'run-startup-check.sh'):
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='scripts with spaces ') as tmp:
                root = Path(tmp) / 'checkout with spaces'
                (root / 'scripts').mkdir(parents=True)
                (root / 'target').mkdir()
                (root / 'target/onionall-brawler-0.1.0-SNAPSHOT.jar').write_bytes(b'fixture')
                script = root / 'scripts' / name
                shutil.copy2(ROOT / 'scripts' / name, script)
                (root / 'scripts/runners').mkdir()
                shutil.copy2(ROOT / 'scripts/runners' / name, root / 'scripts/runners' / name)
                script.chmod(0o644)
                tools = root / 'fake tools'
                tools.mkdir()
                docker = tools / 'docker'
                docker.write_text('#!' + sys.executable + '''
import json,os,sys
from pathlib import Path
a=sys.argv[1:]
with Path(os.environ['CALLS']).open('a') as f:
 f.write(json.dumps(dict(args=a,relative=os.environ.get('M1_RUN_REL'),directory=os.environ.get('M1_RUN_DIR'))) + '\\n')
if 'up' in a: sys.exit(42)
if 'down' in a: sys.exit(43)
''')
                docker.chmod(0o755)
                for tool in ('javac', 'jar'):
                    path = tools / tool
                    path.write_text('#!/bin/sh\nexit 0\n')
                    path.chmod(0o755)
                python = tools / 'runner-python'
                python.write_text('#!/bin/sh\nexit 0\n')
                python.chmod(0o755)
                calls = root / 'calls.jsonl'
                env = dict(os.environ, PATH=str(tools)+os.pathsep+os.environ['PATH'],
                           PYTHON=str(python), M1_PROJECT_NAME='onionall-brawler-m1-012345abcdef',
                           M1_CACHED_IMAGES='1', CALLS=str(calls))
                env.pop('JAVA_HOME', None)
                result = subprocess.run(['bash', str(script)], cwd='/', env=env,
                                        capture_output=True, text=True, timeout=15)
                self.assertEqual(42, result.returncode, result.stderr)
                rows = [json.loads(line) for line in calls.read_text().splitlines()]
                self.assertTrue(any('down' in row['args'] for row in rows))
                self.assertFalse(any('-v' in row['args'] or 'prune' in row['args'] for row in rows))
                if name == 'run-controlled-crawl.sh':
                    for row in rows:
                        if row['args'][0] == 'compose':
                            self.assertEqual('.runtime/m1/onionall-brawler-m1-012345abcdef', row['relative'])
                            self.assertEqual(str(root / row['relative']), row['directory'])
                    self.assertIn('Cleanup could not be verified', result.stderr)

    def test_help_and_legacy_aliases_do_not_start_tools(self):
        pairs = [('m1.sh', 'run-controlled-crawl.sh'), ('m2.sh', 'run-tor-acceptance.sh'),
                 ('m2-preflight.sh', 'check-tor-connectivity.sh'), ('onion-smoke.sh', 'check-onion-fetch.sh')]
        for old, new in pairs:
            for name in (old, new):
                with self.subTest(name=name):
                    result = subprocess.run(['bash', str(ROOT / 'scripts' / name), '--help'], cwd='/',
                                            capture_output=True, text=True, timeout=10)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertTrue(result.stdout.strip())

    def test_powershell_source_contract_not_a_runtime_claim(self):
        source = (ROOT / 'scripts/runners/run-controlled-crawl.ps1').read_text()
        self.assertLess(source.index('$env:M1_RUN_DIR = $runDir'), source.index("Invoke-M1Compose -DockerArgs @('config'"))
        self.assertLess(source.index('$env:M1_RUN_REL = $relative'), source.index("Invoke-M1Compose -DockerArgs @('config'"))
        self.assertIn('retained-volume.json', source)
        self.assertIn('Secondary M1 cleanup failure', source)
        self.assertIn('@PSBoundParameters', (ROOT / 'scripts/m1.ps1').read_text())
        self.assertIn('--cached-images', (ROOT / 'scripts/runners/run-tor-acceptance.ps1').read_text())
        for name in ('m2.ps1', 'm2-preflight.ps1'):
            self.assertTrue((ROOT / 'scripts' / name).read_text().rstrip().endswith('exit $LASTEXITCODE'))


class CompatibilityTest(unittest.TestCase):
    def test_shell_alias_forwards_spaced_arguments_and_exit_code(self):
        pairs = [('m1.sh', 'run-controlled-crawl.sh'), ('m2.sh', 'run-tor-acceptance.sh'),
                 ('m2-preflight.sh', 'check-tor-connectivity.sh'), ('onion-smoke.sh', 'check-onion-fetch.sh'),
                 ('m0-container.sh', 'run-startup-container.sh'),
                 ('m1-container.sh', 'run-controlled-crawl-container.sh'),
                 ('m2-container.sh', 'run-tor-crawl-container.sh')]
        for old, new in pairs:
            with self.subTest(name=old), tempfile.TemporaryDirectory(prefix='aliases with spaces ') as tmp:
                root = Path(tmp)
                shutil.copyfile(ROOT / 'scripts' / old, root / old)
                (root / new).write_text('#!/bin/bash\n[[ "$#" == 2 && "$1" == "one two" && "$2" == "three" ]] || exit 2\nexit 37\n')
                result = subprocess.run(['bash', str(root / old), 'one two', 'three'], cwd='/',
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(37, result.returncode, result.stderr)

    def test_legacy_python_import_identity_and_help(self):
        code = """
import sys
sys.path.insert(0, sys.argv[1])
import m2, tor_acceptance_runner
import m2_recovery, recover_tor_gateway
import m2_supervise, supervise_local_crawl
assert m2 is tor_acceptance_runner
assert m2_recovery is recover_tor_gateway
assert m2_supervise is supervise_local_crawl
"""
        result = subprocess.run([sys.executable, '-B', '-c', code, str(ROOT / 'scripts')], cwd='/',
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        for name in ('m2.py', 'm2_recovery.py', 'm2_supervise.py'):
            result = subprocess.run([sys.executable, '-B', str(ROOT / 'scripts' / name), '--help'], cwd='/',
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
