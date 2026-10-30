"""Small synthetic site. No filesystem serving or outbound HTTP requests."""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PAGES = {
    "/": '<article>M1_ROOT_MARKER <a href="/a">A</a> <a href="/a#duplicate">A again</a>'
         ' <a href="/blocked">robots denied</a> <a href="/missing">404</a>'
         ' <a href="http://frontier:7071/">reject other service</a>'
         ' <a href="http://outside.invalid/">reject other host</a></article>',
    "/a": '<article>M1_A_MARKER <a href="/b">B</a> <a href="/">cycle</a></article>',
    "/b": '<article>M1_B_MARKER <a href="/a">cycle</a></article>',
    "/pending": '<article>M1_PENDING_MARKER</article>',
    "/blocked": '<article>M1_FORBIDDEN_MARKER</article>',
}
ROBOTS = "User-agent: *\nAllow: /\nDisallow: /blocked\nCrawl-delay: 2\n"


def response(path):
    if path == "/health":
        return 200, "text/plain", b"ready"
    if path == "/robots.txt":
        return 200, "text/plain", ROBOTS.encode()
    if path in PAGES:
        body = f"<!doctype html><html><head><title>M1 fixture</title></head><body>{PAGES[path]}</body></html>"
        return 200, "text/html; charset=utf-8", body.encode()
    return 404, "text/plain", b"Synthetic missing page"


def make_server(address, log_path):
    lock = threading.Lock()
    log_path = Path(log_path)
    log_path.parent.mkdir(parents=True, exist_ok=True)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            started = time.monotonic_ns()
            status, mime, body = response(self.path)
            self.send_response(status)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
            if self.path != "/health":
                record = {"path": self.path, "status": status, "started_ns": started,
                          "finished_ns": time.monotonic_ns(), "bytes": len(body),
                          "user_agent": self.headers.get("User-Agent", "")}
                with lock, log_path.open("a", encoding="utf-8") as out:
                    out.write(json.dumps(record, sort_keys=True) + "\n")

        def log_message(self, *args):
            pass

    return ThreadingHTTPServer(address, Handler)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", type=Path, required=True)
    args = parser.parse_args()
    with make_server(("0.0.0.0", 8080), args.log) as server:
        print("M1 synthetic fixture listening on :8080", flush=True)
        server.serve_forever()
