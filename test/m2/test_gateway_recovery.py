"""Fail-closed controller regressions: no Docker or network needed."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import m2_recovery as recovery
import outage_pipeline


class GatewayRecoveryTest(unittest.TestCase):
    def execute(self, health=True, fail=None, detection=None, journal_failure=False, malformed=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = 'onionall-brawler-m2-123456abcdef'
            path = root / '.runtime/m2' / project
            path.mkdir(parents=True)
            failure = dict(passed=False, phase='socks_connect_reply', error_type='TimeoutError')
            (path / 'detected.json').write_text(json.dumps(detection or failure))
            calls = []
            def command(args, **kwargs):
                calls.append(args)
                code = int(fail is not None and fail in args)
                if '--single-health' in args:
                    (path / 'recovery-health.json').write_text(json.dumps(dict(passed=health,
                        health=dict(http_status=200, health_body='ready', socks_atyp=3,
                                    remote_hostname_sent=True))))
                    if malformed == 'health':
                        (path / 'recovery-health.json').write_text('[]')
                    code = int(not health)
                if 'dev.onionall.brawler.m2.RecoverySeriesProbe' in args:
                    (path / 'recovery-series.json').write_text(json.dumps(dict(passed=True, completed=['/robots.txt','/health']*3)))
                if malformed == 'series' and 'dev.onionall.brawler.m2.RecoverySeriesProbe' in args:
                    (path / 'recovery-series.json').write_text('[]')
                return subprocess.CompletedProcess(args, code, stdout='crawler-id\n' if 'ps' in args else '')
            original_write = Path.write_text
            def write(destination, content, *args, **kwargs):
                if (journal_failure and destination.name == 'gateway-recovery.json'
                        and json.loads(content).get('state') == 'recovered'):
                    raise OSError('Injected journal write failure')
                return original_write(destination, content, *args, **kwargs)
            with patch.object(recovery, 'ROOT', root), patch.object(recovery.subprocess, 'run', side_effect=command), \
                    patch.object(Path, 'write_text', new=write):
                result = recovery.recover(project, path, 'detected.json')
                with self.assertRaises(FileExistsError):
                    recovery.recover(project, path, 'detected.json')
            self.assertEqual(result, json.loads((path / 'gateway-recovery.json').read_text()))
            return result, calls

    def test_replacement_once_and_resume_only_after_real_health(self):
        result, calls = self.execute()
        self.assertTrue(result['passed'])
        stages = [event['state'] for event in result['events']]
        self.assertEqual(['quarantining', 'quarantined', 'replacing', 'verifying', 'resuming', 'recovered'], stages)
        self.assertEqual(1, sum('restart' in args for args in calls))
        self.assertLess(next(i for i, a in enumerate(calls) if 'pause' in a),
                        next(i for i, a in enumerate(calls) if 'restart' in a))
        self.assertLess(next(i for i, a in enumerate(calls) if '--single-health' in a),
                        next(i for i, a in enumerate(calls) if 'unpause' in a))
        for args in calls:
            self.assertNotIn('-v', args)
            self.assertNotIn('onion', args)
            self.assertNotIn('frontier', args)

    def test_failed_http_keeps_consumers_quarantined_and_stops_gateway(self):
        result, calls = self.execute(health=False)
        self.assertFalse(result['passed'])
        self.assertEqual('blocked', result['state'])
        self.assertFalse(any('unpause' in args for args in calls))
        self.assertIn('stop', calls[-1])

    def test_malformed_health_or_series_retains_quarantine(self):
        for document in ('health','series'):
            with self.subTest(document=document):
                result,calls=self.execute(malformed=document)
                self.assertFalse(result['passed'])
                self.assertEqual('blocked',result['state'])
                self.assertFalse(any('unpause' in args for args in calls))
                self.assertIn('stop',calls[-1])

    def test_protocol_series_failure_cannot_resume(self):
        result,calls=self.execute(fail='dev.onionall.brawler.m2.RecoverySeriesProbe')
        self.assertFalse(result['passed'])
        self.assertFalse(any('unpause' in args for args in calls))

    def test_bootstrap_alone_cannot_resume(self):
        result, calls = self.execute(fail='restart')
        self.assertFalse(result['passed'])
        self.assertFalse(any('--single-health' in args or 'unpause' in args for args in calls))

    def test_journal_failure_cannot_return_passed_true_in_blocked_state(self):
        result, calls = self.execute(journal_failure=True)
        self.assertFalse(result['passed'])
        self.assertEqual('blocked', result['state'])
        self.assertIn('pause', calls[-2])
        self.assertIn('stop', calls[-1])

    def test_partial_quarantine_failure_closes_gateway(self):
        result, calls = self.execute(fail='pause')
        self.assertFalse(result['passed'])
        self.assertFalse(any('restart' in args or 'unpause' in args for args in calls))
        self.assertIn('stop', calls[-1])

    def test_http_error_does_not_trigger_replacement(self):
        result, calls = self.execute(detection=dict(passed=False, phase='http_response', error_type='TimeoutError', socks_reply_code=0))
        self.assertFalse(result['passed'])
        self.assertEqual([], calls)

    def test_policy_and_non_m2_projects_rejected(self):
        for document in (None, [], {'passed': False, 'attempts': [None]}):
            self.assertFalse(recovery.connect_failure(document))
        self.assertFalse(recovery.connect_failure(dict(passed=False, phase='proxy_tcp_connect', error_type='OSError')))
        self.assertFalse(recovery.connect_failure(dict(passed=True, phase='socks_connect_reply', error_type='OSError')))
        with self.assertRaises(ValueError):
            recovery.recover('unrelated-project', Path('/tmp'))

    def test_next_pipeline_cannot_bypass_active_quarantine(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'gateway-recovery.json').write_text('{"passed":false,"state":"blocked"}')
            with patch.object(outage_pipeline.subprocess, 'run') as command:
                self.assertFalse(outage_pipeline.run('isolated-m2', path))
                command.assert_not_called()
            document = json.loads((path / 'outage-pipeline.json').read_text())
            self.assertIn('quarantine is active', document['blocker'])


if __name__ == '__main__':
    unittest.main()
