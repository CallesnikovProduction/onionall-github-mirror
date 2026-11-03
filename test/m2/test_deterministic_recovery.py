"""Failure injection for diagnostic teardown and pre-pair evidence gates."""
from types import SimpleNamespace
from unittest.mock import Mock
import subprocess
import unittest
from deterministic_recovery import cleanup_project
from retained_cold import publication_observed


class CleanupTest(unittest.TestCase):
    def test_failed_stop_and_logs_cannot_skip_owned_project_down(self):
        runner = Mock(project='onionall-brawler-m2-123456abcdef', started=True)
        def command(args, *a, **kw):
            if args[1] == 'stop':
                raise subprocess.TimeoutExpired(args, 20)
            return SimpleNamespace(stdout='[{}]' if args[1] == 'volume' else '')
        def dc(args, *a, **kw):
            if args[0] == 'logs':
                raise subprocess.TimeoutExpired(args, 40)
            return SimpleNamespace(returncode=0)
        runner.command.side_effect = command
        runner.dc.side_effect = dc
        worker = Mock()
        worker.poll.return_value = None
        worker.wait.side_effect = subprocess.TimeoutExpired('worker', 30)
        result = {'passed': True}
        cleanup_project(runner, worker, None, result)
        self.assertTrue(any(call.args[0][0] == 'down' for call in runner.dc.call_args_list))
        self.assertFalse(result['passed'])
        self.assertEqual(3, len(result['cleanup_blockers']))
        self.assertTrue(result['containers_removed'])
        self.assertTrue(result['volume_retained'])

    def test_publication_without_revision_period_cannot_start_pair(self):
        event = dict(kind='HS_DESC', action='UPLOADED', blinded_key_hash='key')
        self.assertFalse(publication_observed([event]))
        self.assertFalse(publication_observed([dict(event, revision_counter=12)]))
        self.assertTrue(publication_observed([dict(event, revision_counter=12, period=10)]))
