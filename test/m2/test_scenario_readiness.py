"""A failed controlled service baseline must block dependent live scenarios."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import scenarios


class ScenarioReadinessTest(unittest.TestCase):
    def test_failed_service_recovery_stops_matrix_and_preserves_failed_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'requests.jsonl').write_text('')
            (path / 'policy.json').write_text('{"passed":true}')
            (path / 'network.json').write_text(json.dumps(dict(passed=True,
                direct_internet={'connected':False}, direct_fixture={'connected':False})))
            calls = []
            def execute(args, **kwargs):
                calls.append(args)
                if 'dev.onionall.brawler.m2.TorScenarioProbe' in args:
                    name = args[-1]
                    (path / ('scenario-' + name + '.json')).write_text('{"passed":true}')
                code = int('onion-readiness-controlled-return.json' in args)
                return subprocess.CompletedProcess(args, code)
            with patch.object(scenarios.subprocess, 'run', side_effect=execute), \
                    patch.object(scenarios, 'recover', return_value={'passed': False}) as recovery:
                with patch.object(scenarios.subprocess, 'Popen') as background:
                    self.assertFalse(scenarios.run('isolated-m2-test', path))
                    background.assert_not_called()
            recovery.assert_called_once()
            result = json.loads((path / 'security-scenarios.json').read_text())
            self.assertFalse(result['onion_unreachable']['passed'])
            self.assertFalse(result['redirect_loop']['passed'])
            self.assertIn('not executed', result['redirect_loop']['blocker'])
            probes = [args[-1] for args in calls if 'dev.onionall.brawler.m2.TorScenarioProbe' in args]
            self.assertNotIn('out_of_scope_redirect', probes)
            timeline = [json.loads(line) for line in (path / 'scenario-timeline.jsonl').read_text().splitlines()]
            failed = next(row for row in timeline if row['stage'] == 'controlled-onion-ready')
            self.assertEqual(1, failed['exit_code'])
            self.assertTrue(failed['started_at_utc'].endswith('+00:00'))


if __name__ == '__main__':
    unittest.main()
