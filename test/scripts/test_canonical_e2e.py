"""Negative controls for acceptance plus a real loopback SOCKS/HTTP redirect."""
import importlib.util
from pathlib import Path
import socket
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


acceptance = module('canonical_e2e', 'test/admission/canonical_e2e.py')
socks = module('research_socks', 'test/archive/socks_fixture.py')


class CanonicalAcceptanceTest(unittest.TestCase):
    def traffic(self):
        return [dict(phase='http', path=path, status=status, atyp=3) for path, status in
                [('/robots.txt', 200), ('/a', 200), ('/b', 503), ('/redirect', 302), ('/b', 200), ('/c', 200)]]

    def test_acceptance_rejects_depth_escape_duplicates_and_missing_retry(self):
        traffic = self.traffic()
        self.assertEqual(6, len(acceptance.verify_traffic(traffic)))
        for extra in ('/deep', '/blocked', '/dump.zip', '/a'):
            with self.subTest(extra=extra), self.assertRaises(AssertionError):
                acceptance.verify_traffic(traffic + [dict(phase='http', path=extra, status=200, atyp=3)])
        with self.assertRaises(AssertionError):
            acceptance.verify_traffic([r for r in traffic if r['status'] != 503])
        with self.assertRaises(AssertionError):
            acceptance.verify_traffic([dict(r, atyp=1) for r in traffic])

    def test_real_socks_request_returns_only_local_redirect(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            host = 'a' * 56 + '.onion'
            (root / 'onion-hostname.txt').write_text(host)
            (root / 'foreign-hostname.txt').write_text('b' * 56 + '.onion')
            with socks.Fixture(('127.0.0.1', 0), root, research=True) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    with socket.create_connection(server.server_address, timeout=5) as client:
                        client.sendall(b'\x05\x01\x00')
                        self.assertEqual(b'\x05\x00', socks.receive(client, 2))
                        client.sendall(b'\x05\x01\x00\x03' + bytes([len(host)]) + host.encode() + b'\x00\x50')
                        self.assertEqual(b'\x05\x00', socks.receive(client, 10)[:2])
                        client.sendall(b'GET /redirect HTTP/1.0\r\nHost: ' + host.encode() + b'\r\n\r\n')
                        data = bytearray()
                        while chunk := client.recv(1024):
                            data.extend(chunk)
                        self.assertIn(b'HTTP/1.0 302 Found\r\nLocation: /b\r\n', data)
                        self.assertIn('href="redirect"', server.response('/a')[2].decode())
                        self.assertEqual({'/redirect': 1, '/a': 1}, dict(server.counts))
                finally:
                    server.shutdown()
                    thread.join(timeout=5)

    def test_optional_branching_graph_serves_relative_links_redirect_and_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            host = 'a' * 56 + '.onion'
            (root / 'onion-hostname.txt').write_text(host)
            (root / 'foreign-hostname.txt').write_text('b' * 56 + '.onion')
            with socks.Fixture(('127.0.0.1', 0), root, branching=True) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    def get(path):
                        with socket.create_connection(server.server_address, timeout=5) as client:
                            client.sendall(b'\x05\x01\x00')
                            self.assertEqual(b'\x05\x00', socks.receive(client, 2))
                            client.sendall(b'\x05\x01\x00\x03' + bytes([len(host)]) + host.encode() + b'\x00\x50')
                            self.assertEqual(b'\x05\x00', socks.receive(client, 10)[:2])
                            client.sendall(b'GET ' + path.encode() + b' HTTP/1.0\r\nHost: ' + host.encode() + b'\r\n\r\n')
                            data = bytearray()
                            while chunk := client.recv(1024):
                                data.extend(chunk)
                            return bytes(data)

                    index = get('/index.html')
                    self.assertIn(b'href="category-a.html"', index)
                    self.assertIn(b'href="category-a.html#same"', index)
                    self.assertIn(b'href="/category-b.html"', index)
                    self.assertIn(b'href="article-1.html"', get('/category-a.html'))
                    self.assertIn(b'href="article-3.html"', get('/category-b.html'))
                    self.assertIn(b'href="deep.html"', get('/article-1.html'))
                    self.assertIn(b'HTTP/1.0 503 Service Unavailable', get('/article-2.html'))
                    self.assertIn(b'ARCHIVE_ARTICLE_2', get('/article-2.html'))
                    self.assertIn(b'ARCHIVE_ARTICLE_3', get('/article-3.html'))
                    self.assertIn(b'HTTP/1.0 302 Found\r\nLocation: /category-a.html\r\n', get('/redirect'))
                    for forbidden in ('/deep.html', '/blocked', '/dump.zip'):
                        with self.subTest(path=forbidden), self.assertRaises(ValueError):
                            server.response(forbidden)
                finally:
                    server.shutdown()
                    thread.join(timeout=5)
            with socks.Fixture(('127.0.0.1', 0), root, research=True) as original:
                with self.assertRaises(ValueError):
                    original.response('/index.html')
                self.assertIn(b'ARCHIVE_ALPHA', original.response('/a')[2])


if __name__ == '__main__':
    unittest.main()
