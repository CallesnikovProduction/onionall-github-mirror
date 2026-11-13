"""Canonical lifecycle guards; these tests do not claim Docker runtime acceptance."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch, Mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('runtime', ROOT / 'scripts/runners/runtime.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeTest(unittest.TestCase):
    def test_shutdown_stops_only_owned_workers_before_frontier_and_retains_volumes(self):
        calls = []
        def execute(command, **kwargs):
            calls.append([str(v) for v in command])
            return Mock(stdout='owned-container\n' if command[:3] == ['docker', 'ps', '-q'] else '')
        with patch.object(runtime, 'execute', side_effect=execute):
            self.assertEqual(runtime.Runtime().down(), 0)
        ps = next(c for c in calls if c[:3] == ['docker', 'ps', '-q'])
        self.assertIn('label=com.docker.compose.project=onionall-runtime', ps)
        self.assertIn('label=com.docker.compose.service=crawler', ps)
        stop = ['docker', 'stop', '--time', '120', 'owned-container']
        self.assertIn(stop, calls)
        self.assertLess(calls.index(stop), next(i for i, c in enumerate(calls) if 'down' in c))
        self.assertFalse(any('-v' in c or '--volumes' in c for c in calls))

    def test_docker_failure_is_actionable_and_creates_no_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            instance = runtime.Runtime()
            instance.state = Path(temporary) / 'absent'
            with patch.object(runtime, 'execute', side_effect=FileNotFoundError()):
                with self.assertRaisesRegex(RuntimeError, 'Docker Engine/Compose unavailable'):
                    instance.up()
            self.assertFalse(instance.state.exists())

    def test_other_mode_blocks_second_frontier(self):
        instance = runtime.Runtime(fixture=True)
        with patch.object(instance, 'check_docker'), patch.object(instance, 'prepare') as prepare:
            with patch.object(runtime, 'execute', return_value=Mock(stdout='live-tor-container')):
                with self.assertRaisesRegex(RuntimeError, 'Another canonical runtime mode'):
                    instance.up()
            prepare.assert_not_called()

    def test_cli_preserves_explicit_approval_and_forwards_start(self):
        instance = runtime.Runtime()
        with patch.object(instance, 'check_docker'), patch.object(instance, 'dc') as dc:
            instance.cli(['crawl', 'start', '--plan', 'job'])
        args = dc.call_args.args
        self.assertIn('/state/runtime.json', args)
        self.assertIn('/state/admission-token', args)
        self.assertNotIn('approve', args)

    def test_build_targets_existing_crawler_project_and_packages_crawl_services(self):
        source = (ROOT / 'scripts/cli/build-cli.sh').read_text()
        self.assertIn('-f "$root/crawler/pom.xml"', source)
        self.assertIn('archive admission crawl research;', source)
        self.assertIn('kotlinx-coroutines-core-jvm-[0-9]*.jar', source)
        self.assertNotIn('-pl :onionall-brawler', source)

    def test_thread_subgroup_forwards_runtime_and_bounded_budget_without_approval(self):
        instance = runtime.Runtime()
        with patch.object(instance, 'check_docker'), patch.object(instance, 'dc') as dc:
            instance.cli(['--json', '--archive-dir', '/state/archive', 'research', 'thread', 'crawl', 'session', '--total-seconds', '900'])
        args = dc.call_args.args
        self.assertIn('/state/runtime.json', args)
        self.assertIn('/state/admission-token', args)
        self.assertNotIn('--approve', args)
        self.assertEqual(1140, dc.call_args.kwargs['timeout'])
        with patch.object(instance, 'check_docker'), patch.object(instance, 'dc') as dc:
            instance.cli(['research', 'thread', 'plan', 'http://example.invalid/thread'])
        self.assertNotIn('--runtime', dc.call_args.args)
        self.assertNotIn('--admission-token-file', dc.call_args.args)


if __name__ == '__main__':
    unittest.main()
