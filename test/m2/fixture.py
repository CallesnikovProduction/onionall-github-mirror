"""Deterministic onion-only M2 fixture; never serves files or contacts remote hosts."""
import argparse
import json
import socket
import struct
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
import time

PAGES = {
    '/': '<article>M2_ROOT_MARKER <a href="/a">A</a><a href="/a#duplicate">Again</a>'
         '<a href="/blocked">Denied</a><a href="/missing">Missing</a>'
         '<a href="/redirect">Redirect</a><a href="/escape">Escape</a></article>',
    '/a': '<article>M2_A_MARKER <a href="/b">B</a><a href="/">Cycle</a></article>',
    '/b': '<article>M2_B_MARKER <a href="/a">Cycle</a></article>',
    '/pending': '<article>M2_PENDING_MARKER</article>',
    '/blocked': '<article>M2_FORBIDDEN_MARKER</article>',
}
ROBOTS = 'User-agent: *\nAllow: /\nDisallow: /blocked\nCrawl-delay: 2\n'
REDIRECTS = {'/redirect': '/b', '/escape': 'http://outside.invalid/',
             '/loop-a': '/loop-b', '/loop-b': '/loop-a'}


def response(path):
    if path == '/health':
        return 200, 'text/plain', b'ready', None
    if path == '/robots.txt':
        return 200, 'text/plain', ROBOTS.encode(), None
    if path in REDIRECTS:
        return 302, 'text/plain', b'Controlled redirect', REDIRECTS[path]
    if path in PAGES:
        body = '<!doctype html><html><head><title>M2 fixture</title></head><body>' + PAGES[path] + '</body></html>'
        return 200, 'text/html; charset=utf-8', body.encode(), None
    if path in ('/slow', '/outage'):
        time.sleep(15)
        if path == '/outage':
            return 200, 'text/html; charset=utf-8', b'<html><body><article>M2_OUTAGE_MARKER</article></body></html>', None
        return 200, 'text/plain', b'M2_SLOW_MARKER', None
    return 404, 'text/plain', b'Synthetic missing page', None


def make_server(address, log_path):
    log_path = Path(log_path)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            started = time.monotonic_ns()
            if self.path in ('/slow', '/outage'):
                # Publish before sleeping so the outage test stops Tor only after
                # an actual onion request has entered this controlled handler.
                marker = log_path.parent / ('slow-started' if self.path == '/slow' else 'outage-started')
                marker.write_text(json.dumps({'path': self.path, 'started_ns': started}), encoding='utf-8')
            if self.path == '/reset':
                # Abort the TCP connection (RST), rather than a graceful EOF.
                self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
                self.connection.close()
                status, body = 0, b''
            else:
                status, mime, body, location = response(self.path)
                self.send_response(status)
                self.send_header('Content-Type', mime)
                self.send_header('Content-Length', str(len(body)))
                if location:
                    self.send_header('Location', location)
                self.end_headers()
                try:
                    self.wfile.write(body)
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    pass  # An intentionally timed-out request still gets an evidence row.
            if self.path != '/health':
                record = dict(path=self.path, status=status, started_ns=started,
                              finished_ns=time.monotonic_ns(), bytes=len(body),
                              user_agent=self.headers.get('User-Agent', ''),
                              method=self.command, client_ip=self.client_address[0])
                with lock, log_path.open('a', encoding='utf-8') as out:
                    out.write(json.dumps(record, sort_keys=True) + '\n')

        def log_message(self, *args):
            pass

    return ThreadingHTTPServer(address, Handler)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--log', type=Path, required=True)
    args = parser.parse_args()
    with make_server(('0.0.0.0', 8080), args.log) as server:
        print('M2 controlled onion fixture ready', flush=True)
        server.serve_forever()
