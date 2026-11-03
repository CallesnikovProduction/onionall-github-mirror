import unittest
from unittest.mock import patch
import network_probe


class OnionReadinessTest(unittest.TestCase):
    def test_single_health_never_retries_or_infers_success(self):
        with patch.object(network_probe, 'remote_fetch', side_effect=TimeoutError()) as fetch:
            outcome = network_probe.single_health('controlled.onion', 10)
        self.assertFalse(outcome['passed'])
        self.assertEqual('TimeoutError', outcome['error_type'])
        fetch.assert_called_once()
        for budget in (0, 31):
            with self.assertRaises(ValueError):
                network_probe.single_health('controlled.onion', budget)

    def test_cold_gateway_observer_keeps_domain_socks_request(self):
        from unittest.mock import MagicMock
        sock = MagicMock()
        sock.__enter__.return_value = sock
        sock.recv.side_effect = [b'\x05\x00', b'\x05\x00\x00\x01', b'\x7f\x00\x00\x01\x00\x50',
                                b'HTTP/1.0 200 OK\r\n\r\nready', b'']
        with patch.object(network_probe.socket, 'create_connection', return_value=sock) as connect:
            result = network_probe.remote_fetch('controlled.onion', proxy_host='cold')
        self.assertEqual(('cold', 9050), connect.call_args.args[0])
        self.assertEqual(b'\x05\x01\x00\x03', sock.sendall.call_args_list[1].args[0][:4])
        self.assertTrue(result['remote_hostname_sent'])

    @unittest.skipIf(__import__('os').name == 'nt', 'Diagnostic account lookup runs inside Linux containers')
    def test_jdk_auth_offer_has_separate_phase_and_never_sends_http_after_auth_failure(self):
        from unittest.mock import MagicMock
        sock=MagicMock();sock.__enter__.return_value=sock
        sock.recv.side_effect=[b'\x05\x02',b'\x01\x01']
        observation={}
        with patch.object(network_probe.socket,'create_connection',return_value=sock):
            with self.assertRaises(OSError):network_probe.remote_fetch('controlled.onion',observation=observation,jdk_auth=True)
        self.assertEqual('socks_authentication',observation['phase'])
        self.assertEqual(2,observation['socks_auth_method'])
        self.assertEqual(b'\x05\x02\x00\x02',sock.sendall.call_args_list[0].args[0])
        self.assertFalse(any(c.args[0].startswith(b'GET ') for c in sock.sendall.call_args_list))
        self.assertNotIn('username',observation)

    def test_timeout_before_socks_connect_reply_is_not_reported_as_http(self):
        from unittest.mock import MagicMock
        sock = MagicMock()
        sock.__enter__.return_value = sock
        sock.recv.side_effect = [b'\x05\x00', TimeoutError()]
        observation = {}
        with patch.object(network_probe.socket, 'create_connection', return_value=sock):
            with self.assertRaises(TimeoutError):
                network_probe.remote_fetch('controlled.onion', observation=observation)
        self.assertEqual(observation['phase'], 'socks_connect_reply')
        self.assertNotIn('socks_reply_code', observation)
        self.assertFalse(any(call.args[0].startswith(b'GET ') for call in sock.sendall.call_args_list))

    def test_http_timeout_is_separate_from_successful_socks_connect(self):
        from unittest.mock import MagicMock
        sock = MagicMock()
        sock.__enter__.return_value = sock
        sock.recv.side_effect = [b'\x05\x00', b'\x05\x00\x00\x01', b'\x7f\x00\x00\x01\x00\x50', TimeoutError()]
        observation = {}
        with patch.object(network_probe.socket, 'create_connection', return_value=sock):
            with self.assertRaises(TimeoutError):
                network_probe.remote_fetch('controlled.onion', observation=observation)
        self.assertEqual(observation['phase'], 'http_response')
        self.assertEqual(observation['socks_reply_code'], 0)

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
