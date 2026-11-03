"""Recovery attribution uses synthetic observations; never Tor acceptance."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location('restart_attribution', Path(__file__).with_name('restart_recovery.py'))
restart = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restart)


class RestartAttributionTest(unittest.TestCase):
    def setUp(self):
        self.recover = Mock()
        self.health = dict(passed=True, health=dict(http_status=200, health_body='ready',
                          socks_atyp=3, remote_hostname_sent=True))
        self.failure = dict(passed=False, phase='socks_connect_reply', error_type='TimeoutError')

    def restore(self, code, observation):
        return restart.restore_transport('owned-project', Path('/unused'), code, observation, self.recover)

    def test_unassisted_http_proof_passes_transport_without_claiming_assisted_or_application(self):
        result = self.restore(0, self.health)
        self.assertEqual('PASS', result['transport_recovery'])
        self.assertEqual('NOT_EXERCISED', result['assisted_recovery'])
        self.assertFalse(result['recovery_exercised'])
        self.assertNotIn('application_recovery', result)
        self.assertNotIn('m2_verified', result)
        self.recover.assert_not_called()

    def test_exit_zero_without_each_required_health_proof_is_rejected(self):
        for key, value in [('http_status', 503), ('health_body', 'wrong'), ('socks_atyp', 1),
                           ('remote_hostname_sent', False)]:
            with self.subTest(key=key):
                observation = dict(passed=True, health={**self.health['health'], key: value})
                with self.assertRaises(ValueError):
                    self.restore(0, observation)
        for observation in ({}, {'passed': False}, {'passed': True, 'health': []}):
            with self.assertRaises(ValueError):
                self.restore(0, observation)
        self.recover.assert_not_called()

    def test_nonzero_exit_cannot_override_successful_health_document(self):
        with self.assertRaises(ValueError):
            self.restore(1, self.health)
        self.recover.assert_not_called()

    def test_qualifying_failure_exercises_exactly_one_assisted_cycle(self):
        self.recover.return_value = dict(passed=True, state='recovered')
        result = self.restore(1, self.failure)
        self.assertEqual('PASS', result['transport_recovery'])
        self.assertEqual('PASS', result['assisted_recovery'])
        self.assertTrue(result['recovery_exercised'])
        self.recover.assert_called_once_with('owned-project', Path('/unused'), diagnostics=True)

    def test_failed_assisted_cycle_is_dependency_unavailable_not_application_failure_or_pass(self):
        self.recover.return_value = dict(passed=False, state='blocked')
        result = self.restore(1, self.failure)
        self.assertEqual('DEPENDENCY_UNAVAILABLE', result['transport_recovery'])
        self.assertEqual('FAILED', result['assisted_recovery'])
        self.assertNotIn('application_recovery', result)
        self.recover.assert_called_once()

    def test_http_policy_greeting_and_success_reply_failures_never_trigger_gateway_reset(self):
        for phase in ('socks_greeting', 'http_response', 'policy'):
            with self.subTest(phase=phase), self.assertRaises(ValueError):
                self.restore(1, {**self.failure, 'phase': phase})
        with self.assertRaises(ValueError):
            self.restore(1, {**self.failure, 'socks_reply_code': 0})
        self.recover.assert_not_called()


if __name__ == '__main__':
    unittest.main()
