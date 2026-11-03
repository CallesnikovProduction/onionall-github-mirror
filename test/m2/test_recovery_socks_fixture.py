"""Wire contracts of the internal-only deterministic SOCKS fixture."""
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from recovery_socks_fixture import Fixture, receive


class RecoveryFixtureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name)
        self.host = 'a' * 56 + '.onion'
        (self.path / 'onion-hostname.txt').write_text(self.host)
        (self.path / 'socks-mode').write_text('ready')
        self.server = Fixture(('127.0.0.1', 0), self.path)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.directory.cleanup()

    def connect(self, host=None):
        sock = socket.create_connection(self.server.server_address, timeout=2)
        self.addCleanup(sock.close)
        sock.sendall(b'\x05\x02\x00\x02')
        self.assertEqual(receive(sock, 2), b'\x05\x00')
        encoded = (host or self.host).encode()
        sock.sendall(b'\x05\x01\x00\x03' + bytes([len(encoded)]) + encoded + b'\x00\x50')
        return sock

    def test_ready_only_serves_owned_robots(self):
        sock = self.connect()
        self.assertEqual(receive(sock, 10)[:2], b'\x05\x00')
        sock.sendall(b'GET /robots.txt HTTP/1.0\r\n\r\n')
        response = b''
        while chunk := sock.recv(4096):
            response += chunk
        self.assertIn(b'Disallow: /blocked', response)

    def test_foreign_target_denied_before_http(self):
        sock = self.connect('b' * 56 + '.onion')
        self.assertEqual(receive(sock, 10)[:2], b'\x05\x02')

    def test_stall_observes_client_cancellation(self):
        (self.path / 'socks-mode').write_text('stall')
        sock = self.connect()
        sock.settimeout(.1)
        with self.assertRaises(socket.timeout):
            sock.recv(1)
        sock.close()
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            file = self.path / 'socks-events.jsonl'
            if file.exists() and 'client_closed_during_connect' in file.read_text():
                return
            time.sleep(.02)
        self.fail('Cancellation was not observed')
