"""Packaged CLI -> actual TorProtocol -> controlled loopback SOCKS5. Never Tor/public network."""
import json
import os
import select
import time
from pathlib import Path
import signal
import socket
import struct
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'target/onionall/bin/onionall'
HOST = 'juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion'
URL = 'http://' + HOST + '/'


def receive(connection, size):
    data = bytearray()
    while len(data) < size:
        chunk = connection.recv(size - len(data))
        if not chunk:
            raise EOFError('SOCKS connection closed')
        data.extend(chunk)
    return bytes(data)


class SocksFixture:
    def __init__(self, status=200, hold=False):
        self.status = status
        self.hold = hold
        self.connections = 0
        self.targets = []
        self.requests = []
        self.errors = []
        self.accepted = threading.Event()
        self.stop = threading.Event()
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.port = self.listener.getsockname()[1]
        self.listener.listen(4)
        self.listener.settimeout(.1)
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def serve(self):
        while not self.stop.is_set():
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            self.connections += 1
            self.accepted.set()
            with connection:
                connection.settimeout(5)
                if self.hold:
                    self.stop.wait(6)
                    continue
                try:
                    version, count = receive(connection, 2)
                    assert version == 5
                    methods = receive(connection, count)
                    assert 0 in methods, 'No unauthenticated SOCKS method offered'
                    connection.sendall(b'\x05\x00')
                    version, command, reserved, atyp = receive(connection, 4)
                    assert (version, command, reserved, atyp) == (5, 1, 0, 3), 'Destination must use SOCKS hostname ATYP3'
                    hostname = receive(connection, receive(connection, 1)[0]).decode('ascii')
                    port = struct.unpack('!H', receive(connection, 2))[0]
                    self.targets.append((hostname, port, atyp))
                    connection.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x50')
                    request = bytearray()
                    while b'\r\n\r\n' not in request:
                        request.extend(receive(connection, 1))
                        assert len(request) <= 16384
                    self.requests.append(bytes(request))
                    body = b'<!doctype html><html><title>Fixture &amp; Onion</title></html>'
                    location = 'Location: /next\r\n' if self.status == 302 else ''
                    headers = (f'HTTP/1.1 {self.status} Fixture\r\nContent-Type: text/html; charset=UTF-8\r\n'
                               f'{location}Content-Length: {len(body)}\r\nConnection: close\r\n\r\n').encode('ascii')
                    connection.sendall(headers + body)
                except (OSError, EOFError, AssertionError) as error:
                    self.errors.append(str(error))

    def close(self):
        self.stop.set()
        self.listener.close()
        self.thread.join(timeout=7)
        assert not self.thread.is_alive(), 'Fixture did not terminate'

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()


@unittest.skipUnless(BUNDLE.is_file(), 'Build target/onionall first with scripts/cli/build-cli.sh')
class DistributionTransportTest(unittest.TestCase):
    def args(self, port, output, url=URL, timeout=1500):
        return [str(BUNDLE), 'fetch', url, '--socks-port', str(port), '--timeout-ms', str(timeout),
                '--output', str(output), '--json']

    def invoke(self, port, output, url=URL):
        result = subprocess.run(self.args(port, output, url), cwd=ROOT,
                                env=dict(os.environ, NO_COLOR='1'), capture_output=True, text=True, timeout=15)
        self.assertNotIn('\x1b', result.stdout)
        self.assertNotIn('\x1b', result.stderr)
        try:
            parsed = json.loads(result.stdout)
        except json.JSONDecodeError:
            self.fail(f'CLI did not emit JSON (exit {result.returncode}): {result.stderr} {result.stdout}')
        return result, parsed

    def test_offline_discovery_export_from_packaged_cli_does_not_contact_socks(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'fixture.html'
            export = Path(directory) / 'report.jsonl'
            source.write_text("<a href='/A'>A</a><a href='/A#dup'>dup</a><a href='https://example.com/'>excluded</a>")
            fixture = SocksFixture()
            try:
                args = [str(BUNDLE), 'discover', str(source), '--base-url', URL,
                        '--socks-port', str(fixture.port), '--json']
                first = subprocess.run(args, cwd=directory, capture_output=True, text=True, timeout=10)
                second = subprocess.run(args, cwd=directory, capture_output=True, text=True, timeout=10)
                self.assertEqual(0, first.returncode, first.stderr)
                self.assertEqual('', first.stderr)
                self.assertEqual(first.stdout, second.stdout)
                self.assertNotIn('\x1b', first.stdout)
                report = json.loads(first.stdout)
                self.assertEqual((3, 1, 1, 1), (report['links_found'], report['unique'], report['duplicates'], report['rejected']))
                self.assertEqual(0, report['network_requests'])
                result = subprocess.run(args + ['--export', str(export)], cwd=directory, capture_output=True, text=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(3, len(export.read_text().splitlines()))
                self.assertEqual(0, fixture.connections)
            finally:
                fixture.close()

    @unittest.skipUnless(os.name == 'posix', 'PTY is POSIX-only')
    def test_interactive_brand_and_no_color_with_real_terminal(self):
        import pty
        for no_color, width in ((False, '40'), (True, '80')):
            master, slave = pty.openpty()
            env = dict(os.environ, TERM='xterm-256color', COLUMNS=width)
            env.pop('NO_COLOR', None)
            if no_color:
                env['NO_COLOR'] = '1'
            process = subprocess.Popen([str(BUNDLE)], cwd=ROOT, env=env,
                                       stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
            os.close(slave)
            output = bytearray()
            try:
                deadline = time.monotonic() + 5
                while b'Choose ' not in output and time.monotonic() < deadline:
                    if select.select([master], [], [], .1)[0]:
                        try: output.extend(os.read(master, 8192))
                        except OSError: break
                self.assertIn(b'Choose ', output)
                self.assertIn(b'Tor-native fetch engine', output)
                self.assertEqual(not no_color, b'\x1b[' in output)
                os.write(master, b'0\n')
                self.assertEqual(0, process.wait(timeout=3))
            finally:
                if process.poll() is None:
                    process.kill(); process.wait(timeout=3)
                os.close(master)

    def test_real_protocol_uses_remote_hostname_and_saves_html(self):
        with tempfile.TemporaryDirectory() as directory, SocksFixture() as wire:
            output = Path(directory) / 'download.html'
            process, result = self.invoke(wire.port, output)
            self.assertEqual(0, process.returncode, process.stderr + process.stdout)
            self.assertTrue(result['passed'])
            self.assertEqual(200, result['http_status'])
            self.assertEqual('Fixture & Onion', result['title'])
            self.assertEqual(result['bytes'], output.stat().st_size)
            self.assertIn(b'<title>Fixture &amp; Onion</title>', output.read_bytes())
            self.assertEqual([(HOST, 80, 3)], wire.targets)
            self.assertEqual(1, wire.connections)
            self.assertEqual([], wire.errors)
            self.assertTrue(wire.requests[0].startswith(b'GET / HTTP/1.1\r\n'))

    def test_redirect_is_returned_without_follow_or_output(self):
        with tempfile.TemporaryDirectory() as directory, SocksFixture(status=302) as wire:
            output = Path(directory) / 'redirect.html'
            process, result = self.invoke(wire.port, output)
            self.assertEqual(5, process.returncode, process.stderr + process.stdout)
            self.assertFalse(result['passed'])
            self.assertEqual(302, result['http_status'])
            self.assertFalse(output.exists())
            self.assertEqual(1, wire.connections)
            self.assertEqual([(HOST, 80, 3)], wire.targets)
            self.assertEqual([], wire.errors)

    def test_refused_proxy_fails_closed_without_creating_output(self):
        with tempfile.TemporaryDirectory() as directory:
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                port = reservation.getsockname()[1]
            output = Path(directory) / 'failure.html'
            process, result = self.invoke(port, output)
            self.assertEqual(4, process.returncode, process.stderr + process.stdout)
            self.assertFalse(result['passed'])
            self.assertTrue(any(category in result['errors'][0]
                                for category in ('SOCKS_FAILURE', 'ONION_UNREACHABLE')), result)
            self.assertTrue(any('ConnectException' in error for error in result['errors']), result)
            self.assertFalse(output.exists())
            # This proves refusal/error/no-output, not system-wide DNS-leak or no-DIRECT absence.
            # ProxySelector and resolver-guard regressions establish their separately scoped guarantees.

    def test_scope_rejection_precedes_any_socks_connection(self):
        with tempfile.TemporaryDirectory() as directory, SocksFixture() as wire:
            output = Path(directory) / 'rejected.html'
            process, result = self.invoke(wire.port, output, url='http://127.0.0.1/')
            self.assertEqual(3, process.returncode, process.stderr + process.stdout)
            self.assertFalse(result['passed'])
            self.assertEqual(0, wire.connections)
            self.assertFalse(output.exists())

    @unittest.skipUnless(os.name == 'posix', 'SIGINT exit-code contract requires POSIX')
    def test_sigint_cancels_waiting_request_without_complete_file(self):
        with tempfile.TemporaryDirectory() as directory, SocksFixture(hold=True) as wire:
            output = Path(directory) / 'cancelled.html'
            process = subprocess.Popen(self.args(wire.port, output, timeout=3000), cwd=ROOT,
                                       env=dict(os.environ, NO_COLOR='1'), stdout=subprocess.PIPE,
                                       stderr=subprocess.PIPE, text=True)
            try:
                if not wire.accepted.wait(5):
                    process.kill()
                    stdout, stderr = process.communicate(timeout=3)
                    self.fail('CLI did not reach controlled proxy: ' + stdout + stderr)
                process.send_signal(signal.SIGINT)
                stdout, stderr = process.communicate(timeout=6)
                self.assertEqual(130, process.returncode, stdout + stderr)
                self.assertFalse(output.exists())
            finally:
                if process.poll() is None:
                    process.kill()
                process.communicate(timeout=3)


if __name__ == '__main__':
    unittest.main()
