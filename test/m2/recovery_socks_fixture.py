"""Internal-only SOCKS fixture for deterministic recovery; never forwards traffic.

Only the generated approved onion identity, port80 and robots/pending paths exist.
Host-owned mode file arms/stalls/restores CONNECT; every handler is time-bounded.
"""
import argparse
import json
from pathlib import Path
import socket
import socketserver
import threading
import time


def receive(sock, count):
    value = b''
    while len(value) < count:
        part = sock.recv(count - len(value))
        if not part:
            raise EOFError('peer closed')
        value += part
    return value


class Fixture(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, evidence):
        self.evidence = Path(evidence)
        self.host = (self.evidence / 'onion-hostname.txt').read_text().strip()
        self.lock = threading.Lock()
        super().__init__(address, Handler)

    def event(self, event, **fields):
        with self.lock:
            with (self.evidence / 'socks-events.jsonl').open('a') as out:
                out.write(json.dumps(dict(event=event, monotonic=time.monotonic(), **fields)) + '\n')


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        sock.settimeout(8)
        try:
            version, count = receive(sock, 2)
            methods = receive(sock, count)
            if version != 5 or 0 not in methods:
                raise ValueError('unsupported greeting')
            sock.sendall(b'\x05\x00')
            if receive(sock, 4) != b'\x05\x01\x00\x03':
                raise ValueError('DOMAIN CONNECT required')
            host = receive(sock, receive(sock, 1)[0]).decode('ascii')
            port = int.from_bytes(receive(sock, 2), 'big')
            if host != self.server.host or port != 80:
                self.server.event('scope_rejected')
                sock.sendall(b'\x05\x02\x00\x01' + b'\0' * 6)
                return
            self.server.event('connect_received', atyp=3)
            mode = self.server.evidence / 'socks-mode'
            deadline = time.monotonic() + 8
            while mode.read_text().strip() in ('armed', 'stall'):
                if time.monotonic() >= deadline:
                    raise TimeoutError('bounded fixture stall')
                # Detect call cancellation without waiting for a mode change.
                sock.settimeout(.05)
                try:
                    if sock.recv(1, socket.MSG_PEEK) == b'':
                        self.server.event('client_closed_during_connect')
                        return
                except socket.timeout:
                    pass
            sock.settimeout(8)
            if mode.read_text().strip() != 'ready':
                raise ValueError('invalid mode')
            sock.sendall(b'\x05\x00\x00\x01' + b'\0' * 6)
            request = b''
            while b'\r\n\r\n' not in request:
                request += receive(sock, 1)
                if len(request) > 8192:
                    raise ValueError('request too large')
            path = request.split(b' ', 2)[1].decode('ascii')
            if path == '/robots.txt':
                mime, body = 'text/plain', b'User-agent: *\nDisallow: /blocked\n'
            elif path == '/pending':
                mime, body = 'text/html', b'<html><body>M2_PENDING_MARKER</body></html>'
            else:
                raise ValueError('unexpected HTTP path')
            self.server.event('http', path=path, atyp=3)
            sock.sendall((f'HTTP/1.0 200 OK\r\nContent-Type: {mime}\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n').encode() + body)
        except (OSError, EOFError, ValueError) as error:
            self.server.event('handler_end', error_type=type(error).__name__)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', default='/evidence')
    parser.add_argument('--port', type=int, default=9050)
    args = parser.parse_args()
    with Fixture(('0.0.0.0', args.port), args.evidence) as server:
        server.event('listening')
        server.serve_forever()
