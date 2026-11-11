"""Controlled internal SOCKS+HTTP graph; never forwards or resolves targets.

One approved onion origin: /a -> /b -> /c with cycles, duplicate links,
robots refusal, extension-only metadata link, scope escape and one HTTP503 retry.
Optional branching mode serves six HTML pages at depths 0/1/2.
"""
import argparse
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
    """Only internal synthetic responses; no forwarding or target name resolution.

    @author Стефан Колесников
    @author GPT-6.1 Sol
    """
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, evidence, research=False, branching=False):
        self.evidence = Path(evidence)
        self.host = (self.evidence/'onion-hostname.txt').read_text().strip()
        self.foreign = (self.evidence/'foreign-hostname.txt').read_text().strip()
        self.lock = threading.Lock()
        self.counts = Counter()
        self.research = research
        self.branching = branching
        super().__init__(address, Handler)

    def event(self, **record):
        with self.lock:
            with (self.evidence/'archive-requests.jsonl').open('a') as output:
                output.write(json.dumps(dict(monotonic=time.monotonic(), **record))+'\n')

    def response(self, path):
        with self.lock:
            self.counts[path] += 1
            count = self.counts[path]
        if path == '/robots.txt':
            return 200, 'text/plain', b'User-agent: *\nDisallow: /blocked\n'
        if self.branching:
            return self.branching_response(path, count)
        if path == '/a':
            body = '<html><head><title>Alpha archive</title></head><body>ARCHIVE_ALPHA <a href="/b">B</a><a href="/b#same">duplicate B</a><a href="/blocked">robots</a><a href="/dump.zip">binary</a><a href="http://'+self.foreign+'/escape">outside</a></body></html>'
            if self.research:
                body = body.replace('</body>', '<a href="redirect">relative redirect</a></body>')
        elif path == '/b':
            if count == 1:
                return 503, 'text/html', b'<html><body>CONTROLLED_TEMPORARY_FAILURE</body></html>'
            body = '<html><head><title>Beta archive</title></head><body>ARCHIVE_BETA <a href="/c">C</a><a href="/a">cycle A</a><a href="/c">duplicate C</a></body></html>'
        elif path == '/c':
            body = '<html><head><title>Gamma archive</title></head><body>ARCHIVE_GAMMA <a href="/a">cycle A</a><a href="/deep">too deep</a></body></html>'
        elif path == '/redirect' and self.research:
            return 302, 'text/html', b''
        else:
            raise ValueError('Unexpected fixture HTTP path')
        return 200, 'text/html', body.encode('utf-8')

    def branching_response(self, path, count):
        """Optional bounded branching graph; forbidden destinations remain unserved."""
        if path == '/redirect':
            return 302, 'text/html', b''
        if path == '/article-2.html' and count == 1:
            return 503, 'text/html', b'<html><body>CONTROLLED_TEMPORARY_FAILURE</body></html>'
        pages = {
            '/index.html': ('INDEX', '<a href="category-a.html">A</a><a href="/category-b.html">B</a>'
                            '<a href="category-a.html#same">duplicate A</a><a href="/redirect">redirect</a>'
                            '<a href="/blocked">robots</a><a href="/dump.zip">binary</a>'
                            '<a href="http://' + self.foreign + '/escape">outside</a>'),
            '/category-a.html': ('CATEGORY_A', '<a href="article-1.html">one</a>'
                                 '<a href="/article-2.html">two</a><a href="article-1.html">duplicate</a>'),
            '/category-b.html': ('CATEGORY_B', '<a href="article-3.html">three</a>'),
            '/article-1.html': ('ARTICLE_1', '<a href="deep.html">depth three</a>'),
            '/article-2.html': ('ARTICLE_2', '<a href="index.html">cycle</a>'),
            '/article-3.html': ('ARTICLE_3', '<a href="index.html">cycle</a>'),
        }
        if path not in pages:
            raise ValueError('Unexpected branching fixture HTTP path')
        marker, links = pages[path]
        body = f'<html><head><title>{marker}</title></head><body>ARCHIVE_{marker} {links}</body></html>'
        return 200, 'text/html', body.encode('utf-8')


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
            if host != self.server.host or port != 80:
                self.server.event(phase='scope_rejected', atyp=3)
                sock.sendall(b'\x05\x02\x00\x01'+b'\0'*6)
                return
            sock.sendall(b'\x05\x00\x00\x01'+b'\0'*6)
            request = b''
            while b'\r\n\r\n' not in request:
                request += receive(sock, 1)
                if len(request) > 8192:
                    raise ValueError('HTTP request exceeds fixture limit')
            method, raw_path, _ = request.split(b'\r\n', 1)[0].split(b' ', 2)
            if method != b'GET':
                raise ValueError('Only GET permitted')
            path = raw_path.decode('ascii')
            code, mime, content = self.server.response(path)
            self.server.event(phase='http', path=path, method='GET', status=code, atyp=3)
            reason = {200: 'OK', 302: 'Found', 503: 'Service Unavailable'}[code]
            # Цель уже допущена из корневой страницы: redirect не обходит Admission.
            target = '/category-a.html' if self.server.branching else '/b'
            location = f'Location: {target}\r\n' if code == 302 else ''
            sock.sendall((f'HTTP/1.0 {code} {reason}\r\n{location}Content-Type: {mime}\r\nContent-Length: {len(content)}\r\nConnection: close\r\n\r\n').encode()+content)
        except (OSError, EOFError, ValueError) as error:
            self.server.event(phase='handler_end', error_type=type(error).__name__)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', default='/evidence')
    parser.add_argument('--port', type=int, default=9050)
    parser.add_argument('--research', action='store_true', help='Add a relative redirect to the admitted graph')
    parser.add_argument('--branching', action='store_true', help='Use the six-page branching depth-two graph')
    args = parser.parse_args()
    with Fixture(('0.0.0.0', args.port), args.evidence, research=args.research, branching=args.branching) as server:
        server.event(phase='listening')
        server.serve_forever()
