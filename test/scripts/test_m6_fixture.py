"""Socket-level M6 fixture checks; not a substitute for native product execution."""
import importlib.util
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('m6_fixture', ROOT / 'test/admission/m6_socks_fixture.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class M6FixtureTest(unittest.TestCase):
    def test_real_socks_records_three_hosts_relative_pagination_retry_and_redirect(self):
        with tempfile.TemporaryDirectory() as directory:
            hosts = ['a' * 56 + '.onion', 'b' * 56 + '.onion', 'c' * 56 + '.onion']
            with fixture.Fixture(('127.0.0.1', 0), directory, hosts) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
                try:
                    def get(host, path):
                        with socket.create_connection(server.server_address, timeout=5) as client:
                            client.sendall(b'\x05\x01\x00')
                            self.assertEqual(b'\x05\x00', fixture.receive(client, 2))
                            client.sendall(b'\x05\x01\x00\x03' + bytes([len(host)]) + host.encode() + b'\x00\x50')
                            self.assertEqual(b'\x05\x00', fixture.receive(client, 10)[:2])
                            client.sendall(b'GET ' + path.encode() + b' HTTP/1.0\r\nHost: ' + host.encode() + b'\r\n\r\n')
                            data = bytearray()
                            while part := client.recv(4096):
                                data.extend(part)
                            return bytes(data)
                    for number, host in enumerate(hosts, 1):
                        body = get(host, '/thread/1')
                        self.assertIn(b'href="2" rel="next"', body)
                        self.assertIn(('M6_HOST_' + str(number) + '_PAGE_1').encode(), body)
                        self.assertIn(b'download="paper.pdf" type="application/pdf"', body)
                        self.assertIn(b'Location: /thread/2\r\n', get(host, '/redirect'))
                    self.assertIn(b'HTTP/1.0 503', get(hosts[1], '/thread/2'))
                    self.assertIn(b'HTTP/1.0 200', get(hosts[1], '/thread/2'))
                    self.assertIn(b'M6_HOST_2_PAGE_3', get(hosts[1], '/thread/3'))
                finally:
                    server.shutdown(); thread.join(timeout=5)
            events = [json.loads(line) for line in (Path(directory) / 'requests.jsonl').read_text().splitlines()]
            http = [event for event in events if event['phase'] == 'http']
            self.assertEqual(9, len(http))
            self.assertEqual(set(hosts), {event['host'] for event in http})
            self.assertTrue(all(event['atyp'] == 3 for event in http))

    def test_fixture_refuses_binaries_depth_escape_and_unknown_hosts(self):
        with tempfile.TemporaryDirectory() as directory:
            hosts = ['a.onion', 'b.onion', 'c.onion']
            with fixture.Fixture(('127.0.0.1', 0), directory, hosts) as server:
                for path in ('/thread/4', '/blocked', '/escape', '/paper.pdf', '/book.epub', '/notes.txt'):
                    with self.subTest(path=path), self.assertRaises(ValueError):
                        server.response(hosts[0], path)
                with self.assertRaises(ValueError):
                    server.response('external.onion', '/thread/1')
                self.assertIn(b'Disallow: /blocked', server.response(hosts[0], '/robots.txt')[2])

    def test_fixture_requires_three_distinct_identities(self):
        with tempfile.TemporaryDirectory() as directory:
            for hosts in ([], ['a', 'b'], ['a', 'a', 'c']):
                with self.subTest(hosts=hosts), self.assertRaises(ValueError):
                    fixture.Fixture(('127.0.0.1', 0), directory, hosts)


if __name__ == '__main__':
    unittest.main()
