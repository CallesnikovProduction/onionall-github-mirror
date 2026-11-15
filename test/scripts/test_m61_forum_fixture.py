"""Real socket checks for the M6.1 fixture, independent of crawler acceptance.

@author Стефан Колесников
@author GPT-6.1 Sol
"""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('m61_fixture', ROOT / 'test/admission/m61_forum_fixture.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
sys.path.insert(0, str(ROOT / 'test/admission'))
import m61_native_smoke as native


def get(server, host, path):
    with socket.create_connection(server.server_address, timeout=8) as client:
        client.sendall(b'\x05\x01\x00')
        if fixture.receive(client, 2) != b'\x05\x00':
            raise AssertionError('SOCKS negotiation failed')
        client.sendall(b'\x05\x01\x00\x03' + bytes([len(host)]) + host.encode() + b'\x00\x50')
        if fixture.receive(client, 10)[:2] != b'\x05\x00':
            raise AssertionError('SOCKS target rejected')
        client.sendall(b'GET ' + path.encode() + b' HTTP/1.0\r\nHost: ' + host.encode() + b'\r\n\r\n')
        body = bytearray()
        while part := client.recv(4096):
            body.extend(part)
        return bytes(body)


class ForumFixtureTest(unittest.TestCase):
    def test_cooperative_interrupt_does_not_kill_the_current_bounded_child(self):
        with tempfile.TemporaryDirectory() as directory:
            pause = native.PauseControl(Path(directory))
            previous = signal.signal(signal.SIGINT, pause.request)
            timer = threading.Timer(.05, os.kill, args=(os.getpid(), signal.SIGINT))
            try:
                timer.start()
                result = subprocess.run([sys.executable, '-c',
                    'import time; time.sleep(.2); print("bounded child finished")'],
                    capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode)
                self.assertEqual('bounded child finished\n', result.stdout)
                with self.assertRaises(native.FixturePause):
                    pause.check()
            finally:
                timer.cancel(); timer.join(timeout=5)
                signal.signal(signal.SIGINT, previous)

    def test_pause_marker_prevents_starting_another_cli_call(self):
        with tempfile.TemporaryDirectory() as directory:
            run = native.NativeRun.__new__(native.NativeRun)
            run.state = Path(directory)
            run.pause = native.PauseControl(run.state)
            run.pause.marker.touch()
            with patch.object(native.subprocess, 'run') as launch:
                with self.assertRaises(native.FixturePause):
                    run.cli(['research', 'thread', 'plan', 'unexecuted'], 'must-not-start')
                launch.assert_not_called()
            self.assertFalse((run.state / 'must-not-start.log').exists())

    def test_two_layouts_relative_navigation_retry_attachments_and_redirect(self):
        with tempfile.TemporaryDirectory() as directory:
            hosts = ['a.onion', 'b.onion']
            with fixture.Fixture(('127.0.0.1', 0), directory, hosts, slow_seconds=0) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
                try:
                    self.assertIn(b'href="/forum/"', get(server, hosts[0], '/'))
                    self.assertIn(b'href="security/"', get(server, hosts[0], '/forum/'))
                    self.assertIn(b'/thread?id=123&amp;page=1', get(server, hosts[0], '/forum/security/'))
                    first = get(server, hosts[0], '/thread?id=123&page=1')
                    self.assertIn(b'id="p101"', first)
                    self.assertIn(b'?page=2&amp;id=123#same', first)
                    self.assertIn(b'/manual.pdf?download=1&amp;lang=en', first)
                    self.assertIn(b'HTTP/1.0 503', get(server, hosts[0], '/thread?id=123&page=2'))
                    self.assertIn(b'HTTP/1.0 200', get(server, hosts[0], '/thread?id=123&page=2'))
                    self.assertIn(b'CONTROLLED_A_POST_104', get(server, hosts[0], '/thread?id=123&page=3'))
                    self.assertIn(b'Location: /forum/\r\n', get(server, hosts[0], '/redirect'))
                    first_b = get(server, hosts[1], '/thread/alternative/')
                    self.assertIn(b'itemtype="https://schema.org/DiscussionForumPosting"', first_b)
                    self.assertIn(b'href="page/2/"', first_b)
                    self.assertIn(b'href="../3/"', get(server, hosts[1], '/thread/alternative/page/2/'))
                    self.assertIn(b'CONTROLLED_B_POST_IDLESS', get(server, hosts[1], '/thread/alternative/page/3/'))
                finally:
                    server.shutdown(); thread.join(timeout=5)
                self.assertEqual(0, server.metrics()['active_remaining'])
            events = [json.loads(line) for line in (Path(directory) / 'requests.jsonl').read_text().splitlines()]
            starts = [event for event in events if event['phase'] == 'http_start']
            ends = [event for event in events if event['phase'] == 'http_end']
            self.assertEqual({event['request_id'] for event in starts}, {event['request_id'] for event in ends})
            self.assertTrue(all(event['atyp'] == 3 for event in starts))

    def test_instrumentation_detects_real_cross_host_overlap_without_enforcing_it(self):
        with tempfile.TemporaryDirectory() as directory:
            hosts = ['a.onion', 'b.onion']
            with fixture.Fixture(('127.0.0.1', 0), directory, hosts, slow_seconds=1) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
                try:
                    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                        first = pool.submit(get, server, hosts[0], '/')
                        deadline = time.monotonic() + 3
                        while not server.metrics()['active_remaining'] and time.monotonic() < deadline:
                            time.sleep(.01)
                        self.assertEqual(1, server.metrics()['active_remaining'])
                        self.assertIn(b'CONTROLLED_B_HOME', pool.submit(get, server, hosts[1], '/').result(timeout=5))
                        self.assertIn(b'CONTROLLED_A_HOME', first.result(timeout=5))
                    metrics = server.metrics()
                    self.assertEqual(2, metrics['max_global_in_flight'])
                    self.assertEqual({host: 1 for host in hosts}, metrics['max_per_host'])
                    self.assertEqual(0, metrics['active_remaining'])
                finally:
                    server.shutdown(); thread.join(timeout=5)

    def test_never_serves_binary_robots_escape_or_unknown_origins(self):
        with tempfile.TemporaryDirectory() as directory:
            with fixture.Fixture(('127.0.0.1', 0), directory, ['a.onion', 'b.onion'], slow_seconds=0) as server:
                for path in ('/blocked', '/escape', '/manual.pdf', '/book.epub', '/notes.txt', '/notes.md',
                             '/thread?id=999&page=1', '/thread?id=123&page=4'):
                    with self.subTest(path=path), self.assertRaises(ValueError):
                        server.response('a.onion', path)
                with self.assertRaises(ValueError):
                    server.response('unknown.onion', '/')
                self.assertIn(b'Disallow: /blocked', server.response('a.onion', '/robots.txt')[2])


if __name__ == '__main__':
    unittest.main()
