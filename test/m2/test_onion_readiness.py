import unittest
from unittest.mock import patch
import network_probe


class OnionReadinessTest(unittest.TestCase):
    def test_observed_health_is_required_after_initial_failure(self):
        healthy={'http_status':200,'health_body':'ready','socks_atyp':3,'remote_hostname_sent':True}
        with patch.object(network_probe,'remote_fetch',side_effect=[OSError('unavailable'),healthy]) as fetch:
            with patch.object(network_probe.time,'sleep'):
                result=network_probe.onion_ready('controlled.onion',seconds=2)
        self.assertTrue(result['ready'])
        self.assertEqual(len(result['attempts']),2)
        self.assertEqual(result['attempts'][0]['error_type'],'OSError')
        self.assertIsNotNone(fetch.call_args.kwargs['deadline'])

    def test_deadline_preserves_failure_instead_of_inferring_readiness(self):
        clock=[0.0]
        def failed(*args,**kwargs):
            clock[0] += .5
            raise TimeoutError()
        def sleep(seconds): clock[0] += seconds
        with patch.object(network_probe.time,'monotonic',side_effect=lambda:clock[0]):
            with patch.object(network_probe.time,'sleep',side_effect=sleep):
                with patch.object(network_probe,'remote_fetch',side_effect=failed):
                    result=network_probe.onion_ready('controlled.onion',seconds=1)
        self.assertFalse(result['ready'])
        self.assertFalse(result['passed'])
        self.assertEqual(result['elapsed_seconds'],1)
        self.assertEqual(len(result['attempts']),1)

    def test_wrong_health_body_does_not_pass(self):
        clock=[0.0]
        def response(*args,**kwargs):
            clock[0] += 1
            return {'http_status':200,'health_body':'unexpected'}
        with patch.object(network_probe.time,'monotonic',side_effect=lambda:clock[0]):
            with patch.object(network_probe,'remote_fetch',side_effect=response):
                result=network_probe.onion_ready('controlled.onion',seconds=1)
        self.assertFalse(result['ready'])


    def isolation_probe(self, readiness):
        from unittest.mock import MagicMock
        dns = MagicMock()
        dns.__enter__.return_value.connect.side_effect = OSError('UDP unavailable')
        with patch.object(network_probe.socket, 'getaddrinfo', side_effect=OSError('DNS forbidden')):
            with patch.object(network_probe.socket, 'socket', return_value=dns):
                with patch.object(network_probe, 'connect', side_effect=[{'connected':False}, {'connected':False}, {'connected':True}, {'connected':False}]):
                    with patch.object(network_probe, 'onion_ready', return_value=readiness) as gate:
                        result = network_probe.run('192.0.2.1', 'controlled.onion')
                        gate.assert_called_once_with('controlled.onion')
                        return result

    def test_initial_probe_cannot_pass_on_socks_listener_alone(self):
        result = self.isolation_probe({'ready':False, 'attempts':[{'error_type':'TimeoutError'}]})
        self.assertFalse(result['passed'])
        self.assertEqual(result['onion_health']['error_type'], 'OnionReadinessDeadlineExceeded')

    def test_initial_probe_retains_observed_onion_readiness(self):
        health = {'http_status':200, 'health_body':'ready', 'socks_atyp':3}
        result = self.isolation_probe({'ready':True, 'attempts':[{'error_type':'TimeoutError'}, {'health':health}]})
        self.assertTrue(result['passed'])
        self.assertEqual(len(result['onion_readiness']['attempts']),2)
        self.assertEqual(result['onion_health'],health)
