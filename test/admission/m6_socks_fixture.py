"""Three internal onion identities and bounded forum pages; no external forwarding.

@author Стефан Колесников
@author GPT-6.1 Sol
"""
from collections import Counter
import json
from pathlib import Path
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
    """Serve only three explicit synthetic hosts; record actual SOCKS/HTTP traffic."""
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, evidence, hosts):
        if len(hosts) != 3 or len(set(hosts)) != 3:
            raise ValueError('Require three distinct fixture hosts')
        self.evidence = Path(evidence)
        self.hosts = tuple(hosts)
        self.lock = threading.Lock()
        self.counts = Counter()
        super().__init__(address, Handler)

    def event(self, **record):
        with self.lock:
            with (self.evidence / 'requests.jsonl').open('a') as stream:
                stream.write(json.dumps(dict(monotonic=time.monotonic(), **record)) + '\n')

    def response(self, host, path):
        if host not in self.hosts:
            raise ValueError('Unapproved fixture host')
        with self.lock:
            self.counts[(host, path)] += 1
            count = self.counts[(host, path)]
        if path == '/robots.txt':
            return 200, 'text/plain', b'User-agent: *\nDisallow: /blocked\n', None
        if path == '/redirect':
            return 302, 'text/html', b'', '/thread/2'
        if host == self.hosts[1] and path == '/thread/2' and count == 1:
            return 503, 'text/html', b'<html><body>CONTROLLED_TEMPORARY_FAILURE</body></html>', None
        number = self.hosts.index(host) + 1
        if path == '/thread/1':
            foreign = self.hosts[number % 3]
            links = ('<a href="2" rel="next">next</a><a href="2#same">duplicate</a>'
                     '<a href="/redirect">redirect</a><a href="/blocked">robots</a>'
                     f'<a href="http://{foreign}/escape">other approved domain outside this child</a>'
                     '<a href="/paper.pdf" download="paper.pdf" type="application/pdf">PDF</a>'
                     '<a href="/book.epub" type="application/epub+zip">EPUB</a>'
                     '<a href="/notes.txt" download="notes.txt">TXT</a>')
            text = f'M6_HOST_{number}_PAGE_1 Discussion begins.'
        elif path == '/thread/2':
            links = '<a href="3" rel="next">next</a><a href="1">previous</a><a href="3">duplicate next</a>'
            text = f'M6_HOST_{number}_PAGE_2 Discussion continues.'
        elif path == '/thread/3':
            links = '<a href="4" rel="next">too deep</a><a href="1">first page</a>'
            text = f'M6_HOST_{number}_PAGE_3 Final captured discussion page.'
        else:
            raise ValueError('Unexpected fixture HTTP path')
        body = f'<html><head><title>Forum {number} {path}</title></head><body><article>{text}</article>{links}</body></html>'
        return 200, 'text/html', body.encode('utf-8'), None


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        sock.settimeout(5)
        try:
            version, count = receive(sock, 2)
            methods = receive(sock, count)
            if version != 5 or 0 not in methods:
                raise ValueError('NO_AUTH SOCKS5 required')
            sock.sendall(b'\x05\x00')
            if receive(sock, 4) != b'\x05\x01\x00\x03':
                raise ValueError('ATYP3 CONNECT required')
            host = receive(sock, receive(sock, 1)[0]).decode('ascii')
            port = int.from_bytes(receive(sock, 2), 'big')
            if host not in self.server.hosts or port != 80:
                self.server.event(phase='socks_rejected', host=host, port=port, atyp=3)
                sock.sendall(b'\x05\x02\x00\x01' + b'\0' * 6)
                return
            sock.sendall(b'\x05\x00\x00\x01' + b'\0' * 6)
            request = b''
            while b'\r\n\r\n' not in request:
                request += receive(sock, 1)
                if len(request) > 8192:
                    raise ValueError('HTTP request exceeds fixture limit')
            method, raw_path, _ = request.split(b'\r\n', 1)[0].split(b' ', 2)
            if method != b'GET':
                raise ValueError('Only GET permitted')
            path = raw_path.decode('ascii')
            code, mime, content, location = self.server.response(host, path)
            self.server.event(phase='http', host=host, path=path, method='GET', status=code, atyp=3)
            reason = {200: 'OK', 302: 'Found', 503: 'Service Unavailable'}[code]
            redirect = f'Location: {location}\r\n' if location else ''
            sock.sendall((f'HTTP/1.0 {code} {reason}\r\n{redirect}Content-Type: {mime}\r\n'
                          f'Content-Length: {len(content)}\r\nConnection: close\r\n\r\n').encode() + content)
        except (OSError, EOFError, ValueError) as error:
            self.server.event(phase='handler_end', error_type=type(error).__name__)
