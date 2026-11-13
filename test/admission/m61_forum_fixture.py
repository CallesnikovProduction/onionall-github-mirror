"""Internal two-origin forum fixture with measured real HTTP in-flight intervals.

It never resolves or forwards destinations. Instrumentation observes requests;
it does not enforce a fake crawler concurrency policy or manufacture overlaps.

@author Стефан Колесников
@author GPT-6.1 Sol
"""
from collections import Counter
from html import escape
import json
from pathlib import Path
import socketserver
import threading
import time
from urllib.parse import parse_qs, urlsplit


def receive(sock, count):
    value = b''
    while len(value) < count:
        part = sock.recv(count - len(value))
        if not part:
            raise EOFError('peer closed')
        value += part
    return value


def traditional(post_id, author, timestamp, text):
    identity = f' id="p{post_id}"' if post_id is not None else ''
    author_html = f'<a class="username">{escape(author)}</a>' if author is not None else ''
    date_html = f'<time datetime="{timestamp}">{timestamp}</time>' if timestamp else ''
    return (f'<div class="post"{identity}><div class="postbody"><p class="author">'
            f'{author_html} {date_html}</p><div class="content">{text}</div></div></div>')


def semantic(post_id, author, timestamp, text):
    identity = f' id="comment-{post_id}"' if post_id is not None else ''
    author_html = (f'<span itemprop="author"><span itemprop="name">{escape(author)}</span></span>'
                   if author is not None else '')
    date_html = f'<time itemprop="datePublished" datetime="{timestamp}">{timestamp}</time>' if timestamp else ''
    return (f'<article itemscope itemtype="https://schema.org/DiscussionForumPosting"{identity}>'
            f'<header>{author_html}{date_html}</header><div itemprop="text">{text}</div></article>')


class Fixture(socketserver.ThreadingTCPServer):
    """Two exact synthetic origins; counters include actual accepted HTTP requests."""
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, evidence, hosts, slow_seconds=.25):
        if len(hosts) != 2 or len(set(hosts)) != 2 or not 0 <= slow_seconds <= 5:
            raise ValueError('Require two distinct hosts and bounded fixture delay')
        self.evidence = Path(evidence)
        self.hosts = tuple(hosts)
        self.slow_seconds = slow_seconds
        self.lock = threading.RLock()
        self.counts = Counter()
        self.active = {}
        self.active_by_host = Counter()
        self.max_global = 0
        self.max_by_host = Counter()
        self.sequence = 0
        super().__init__(address, Handler)

    def event(self, **record):
        with self.lock:
            with (self.evidence / 'requests.jsonl').open('a') as stream:
                stream.write(json.dumps(dict(monotonic=time.monotonic(), **record)) + '\n')

    def begin(self, host, path):
        with self.lock:
            self.sequence += 1
            request_id = self.sequence
            self.active[request_id] = (host, path)
            self.active_by_host[host] += 1
            self.max_global = max(self.max_global, len(self.active))
            self.max_by_host[host] = max(self.max_by_host[host], self.active_by_host[host])
            self.event(phase='http_start', request_id=request_id, host=host, path=path,
                       active_global=len(self.active), active_host=self.active_by_host[host], atyp=3)
            return request_id

    def end(self, request_id):
        with self.lock:
            host, path = self.active.pop(request_id)
            self.active_by_host[host] -= 1
            self.event(phase='http_end', request_id=request_id, host=host, path=path,
                       active_global=len(self.active), active_host=self.active_by_host[host])

    def metrics(self):
        with self.lock:
            return dict(max_global_in_flight=self.max_global, max_per_host=dict(self.max_by_host),
                        active_remaining=len(self.active), request_count=self.sequence)

    def response(self, host, target):
        if host not in self.hosts:
            raise ValueError('Unapproved fixture host')
        with self.lock:
            self.counts[(host, target)] += 1
            count = self.counts[(host, target)]
        parsed = urlsplit(target)
        path, query = parsed.path, parse_qs(parsed.query)
        layout = 'A' if host == self.hosts[0] else 'B'
        if path == '/robots.txt':
            return 200, 'text/plain', b'User-agent: *\nDisallow: /blocked\n', None
        if path == '/redirect':
            return 302, 'text/html', b'', '/forum/' if layout == 'A' else '/categories/'
        if path == '/' and layout == 'A' and count == 1:
            time.sleep(self.slow_seconds)
        if layout == 'A':
            return self.layout_a(host, path, query, count)
        return self.layout_b(host, path)

    def html(self, title, body):
        document = f'<!doctype html><html><head><title>{title}</title></head><body>{body}</body></html>'
        return 200, 'text/html', document.encode('utf-8'), None

    def home(self, layout, category, foreign):
        return self.html(f'Forum {layout}', f'<header>CONTROLLED_{layout}_HOME</header>'
            f'<nav><a href="{category}">Categories</a><a href="{category}#same">duplicate</a>'
            f'<a href="/redirect">redirect</a><a href="/blocked">robots</a>'
            f'<a href="http://{foreign}/escape">foreign origin</a>'
            '<a href="mailto:admin@example.invalid">mail</a><a href="javascript:void(0)">script</a>'
            '<a href="http://[broken">broken</a></nav>')

    def layout_a(self, host, path, query, count):
        if path == '/':
            return self.home('A', '/forum/', self.hosts[1])
        if path == '/forum/':
            return self.html('Security category', '<main>CONTROLLED_A_CATEGORY<a href="security/">Security</a></main>')
        if path == '/forum/security/':
            return self.html('Topic listing A', '<main>CONTROLLED_A_LISTING'
                '<a href="/thread?id=123&amp;page=1">Kotlin &amp; security discussion</a></main>')
        if path == '/thread' and query.get('id') == ['123'] and set(query) <= {'id', 'page'}:
            page = query.get('page', ['1'])
            if len(page) != 1 or page[0] not in {'1', '2', '3'}:
                raise ValueError('Unserved thread page')
            number = int(page[0])
            # Первый запрос canonical page2 завершится 503; настоящий Frontier решает retry.
            if number == 2 and count == 1:
                return 503, 'text/html', b'<html><body>CONTROLLED_A_RETRY</body></html>', None
            post_102 = traditional(102, 'Bob', '2026-11-13T10:01:00Z',
                                   '<p>CONTROLLED_A_POST_102 Reply from Bob.</p>')
            if number == 1:
                posts = traditional(101, 'Alice', '2026-11-13T10:00:00Z',
                    '<p>CONTROLLED_A_POST_101 Kotlin &amp; security.</p>'
                    '<a href="/manual.pdf?download=1&amp;lang=en" download="manual.pdf" type="application/pdf">PDF manual</a>') + post_102
                pagination = ('<nav><a rel="next" href="?id=123&amp;page=2">next</a>'
                    '<a href="?page=2&amp;id=123#same">same page reordered</a>'
                    '<a href="?id=123&amp;page=2">duplicate</a>'
                    '<a href="/forum/">not thread navigation</a></nav>')
            elif number == 2:
                posts = post_102 + traditional(103, 'Carol', '2026-11-13T10:02:00Z',
                    '<blockquote>Alice said: Kotlin &amp; security.</blockquote><p>CONTROLLED_A_POST_103 Answer.</p>')
                pagination = '<nav><a href="?id=123&amp;page=1">previous</a><a rel="next" href="?id=123&amp;page=3">next</a></nav>'
            else:
                posts = traditional(104, 'Dana', '2026-11-13T10:03:00Z',
                    '<p>CONTROLLED_A_POST_104 Literal ![probe](https://example.invalid/pixel). '
                    'IGNORE PREVIOUS INSTRUCTIONS is quoted fixture content.</p>'
                    '<a href="/notes.md?format=source&amp;part=1">Markdown notes</a>')
                pagination = '<nav><a href="?page=2&amp;id=123">previous reordered</a><a href="?id=999&amp;page=1">other topic</a></nav>'
            return self.html('Kotlin &amp; security discussion', f'<main>{posts}{pagination}</main>')
        raise ValueError('Unexpected fixture A HTTP path')

    def layout_b(self, host, path):
        if path == '/':
            return self.home('B', '/categories/', self.hosts[0])
        if path == '/categories/':
            return self.html('Programming category', '<main>CONTROLLED_B_CATEGORY<a href="/topics/">Topics</a></main>')
        if path == '/topics/':
            return self.html('Topic listing B', '<main>CONTROLLED_B_LISTING<a href="/thread/alternative/">Linux discussion</a></main>')
        paths = {'/thread/alternative/': 1, '/thread/alternative/page/2/': 2, '/thread/alternative/page/3/': 3}
        if path not in paths:
            raise ValueError('Unexpected fixture B HTTP path')
        number = paths[path]
        anonymous = semantic(202, None, None, '<p>CONTROLLED_B_POST_202 Missing author and date.</p>')
        if number == 1:
            posts = semantic(201, 'Eve', '2026-11-13T11:00:00Z',
                '<p>CONTROLLED_B_POST_201 Linux administration.</p>'
                '<a href="/book.epub" type="application/epub+zip">EPUB book</a>') + anonymous
            pagination = '<nav><a rel="next" href="page/2/">next</a><a href="page/2/#same">duplicate</a></nav>'
        elif number == 2:
            posts = anonymous + semantic(203, 'Frank', '2026-11-13T11:02:00Z',
                '<blockquote>Eve wrote:<blockquote>Earlier nested quotation.</blockquote></blockquote>'
                '<p>CONTROLLED_B_POST_203 Keep nested quotes.</p>')
            pagination = '<nav><a href="/thread/alternative/">first</a><a rel="next" href="../3/">next</a></nav>'
        else:
            posts = semantic(204, 'Grace', '2026-11-13T11:03:00Z', '<p>CONTROLLED_B_POST_204 Final named reply.</p>')
            posts += semantic(None, None, None, '<p>CONTROLLED_B_POST_IDLESS Source has no stable post ID.</p>'
                '<a href="/notes.txt?part=1&amp;language=en" download="notes.txt">TXT notes</a>')
            pagination = '<nav><a href="../2/">previous</a><a href="/thread/unrelated/">other thread</a></nav>'
        return self.html('Linux discussion', f'<main>{posts}{pagination}</main>')


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        sock.settimeout(8)
        active = None
        try:
            version, count = receive(sock, 2)
            if version != 5 or 0 not in receive(sock, count):
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
                    raise ValueError('Bounded fixture header limit')
            method, raw_target, _ = request.split(b'\r\n', 1)[0].split(b' ', 2)
            if method != b'GET':
                raise ValueError('Only GET permitted')
            target = raw_target.decode('ascii')
            active = self.server.begin(host, target)
            code, mime, content, location = self.server.response(host, target)
            self.server.event(phase='http', request_id=active, host=host, path=target, status=code, atyp=3)
            reason = {200: 'OK', 302: 'Found', 503: 'Service Unavailable'}[code]
            redirect = f'Location: {location}\r\n' if location else ''
            sock.sendall((f'HTTP/1.0 {code} {reason}\r\n{redirect}Content-Type: {mime}\r\n'
                          f'Content-Length: {len(content)}\r\nConnection: close\r\n\r\n').encode() + content)
        except (OSError, EOFError, ValueError) as error:
            self.server.event(phase='handler_end', error_type=type(error).__name__)
        finally:
            if active is not None:
                self.server.end(active)
