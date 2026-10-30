"""Local checks for the fixture and verifier. These are NOT a Storm crawl."""
import copy
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

import fixture
import prepare
from verify import BASE, FIRST, SUCCESS, verify


class FixtureTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("M1_HTTP_SMOKE") == "1", "Opt-in localhost HTTP smoke; blocked in restricted sessions")
    def test_http_routes_robots_and_no_filesystem_serving(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "requests.jsonl"
            server = fixture.make_server(("127.0.0.1", 0), log)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            try:
                with urllib.request.urlopen(base + "/robots.txt") as response:
                    self.assertIn(b"Disallow: /blocked", response.read())
                with urllib.request.urlopen(base + "/") as response:
                    self.assertIn(b"M1_ROOT_MARKER", response.read())
                with self.assertRaises(urllib.error.HTTPError) as raised:
                    urllib.request.urlopen(base + "/../../pom.xml")
                self.assertEqual(404, raised.exception.code)
                raised.exception.close()
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=3)
            requests = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(["/robots.txt", "/", "/../../pom.xml"], [row["path"] for row in requests])

    def test_fixture_responses_are_synthetic_and_bounded(self):
        self.assertIn(b"Disallow: /blocked", fixture.response("/robots.txt")[2])
        self.assertIn(b"M1_ROOT_MARKER", fixture.response("/")[2])
        self.assertEqual(200, fixture.response("/blocked")[0])  # robots, not HTTP auth, must block the crawler.
        self.assertEqual(404, fixture.response("/../../pom.xml")[0])
        self.assertEqual(404, fixture.response("/a?unbounded=1")[0])
        self.assertLess(max(len(fixture.response(path)[2]) for path in fixture.PAGES), 65536)

    def test_topology_derivation_preserves_graph(self):
        root = Path(__file__).resolve().parents[2]
        original = (root / "crawler.flux").read_text()
        derived = prepare.topology(original)
        self.assertEqual(original.split("spouts:\n")[1], derived.split("spouts:\n")[1])
        self.assertIn('file: "test/m1/crawler-conf.yaml"', derived)
        with self.assertRaises(ValueError):
            prepare.topology("unexpected topology")


class VerifierTest(unittest.TestCase):
    def setUp(self):
        def known(url):
            metadata = {} if url in SUCCESS else {"error.cause": ["synthetic test error"]}
            return {"url": url, "crawl": "m1", "key": "fixture", "kind": "known",
                    "refetch": 86400 if url in SUCCESS else 0, "metadata": metadata}
        initial = [known(url) for url in sorted(FIRST)]
        pending = {"url": BASE + "/pending", "crawl": "m1", "key": "fixture", "kind": "discovered",
                   "refetch": None, "metadata": {}}
        self.crawled = {"entries": initial}
        self.before = {"entries": copy.deepcopy(initial) + [pending]}
        self.after = copy.deepcopy(self.before)
        self.final = copy.deepcopy(self.before)
        self.final["entries"][-1].update(kind="known", refetch=86400)
        self.requests = []
        for index, path in enumerate(("/robots.txt", "/", "/a", "/b", "/missing", "/robots.txt", "/pending")):
            self.requests.append({"path": path, "status": 404 if path == "/missing" else 200,
                                  "started_ns": index * 3_000_000_000, "finished_ns": index * 3_000_000_000 + 1,
                                  "user_agent": "OnionallBrawlerMOneTest/0.1"})
        tail = "\n INFO o.a.s.m.LoggingMetricsConsumer - synthetic counters\nDone shutting down in process zookeeper\n"
        self.first_log = "\n".join("content: M1_" + marker + "_MARKER" for marker in ("ROOT", "A", "B")) + tail
        self.second_log = "content: M1_PENDING_MARKER" + tail

    def check(self):
        return verify(self.crawled, self.before, self.after, self.final,
                      self.requests, self.first_log, self.second_log, now=0)

    def test_complete_synthetic_evidence_is_accepted(self):
        self.assertTrue(self.check()["assertions_passed"])

    def test_no_false_pass_for_lost_pending_work(self):
        self.after["entries"].pop()
        with self.assertRaisesRegex(ValueError, "after recreation"):
            self.check()

    def test_no_false_pass_for_changed_schedule(self):
        self.after["entries"][0]["refetch"] = 1
        with self.assertRaisesRegex(ValueError, "after recreation"):
            self.check()

    def test_no_false_pass_for_duplicate_fetch(self):
        self.requests.append(copy.deepcopy(self.requests[2]))
        with self.assertRaisesRegex(ValueError, "duplicate fetch"):
            self.check()

    def test_no_false_pass_for_robots_violation(self):
        self.requests.append({**self.requests[2], "path": "/blocked"})
        with self.assertRaisesRegex(ValueError, "HTTP requests"):
            self.check()

    def test_no_false_pass_for_missing_parsed_content(self):
        self.first_log = self.first_log.replace("content: M1_A_MARKER", "")
        with self.assertRaisesRegex(ValueError, "stdout document"):
            self.check()

    def test_no_false_pass_for_short_delay(self):
        self.requests[2]["started_ns"] = self.requests[1]["finished_ns"] + 1
        with self.assertRaisesRegex(ValueError, "Observed delay"):
            self.check()

    def test_no_false_pass_for_runtime_error(self):
        self.first_log += "\n ERROR example failure\n"
        with self.assertRaisesRegex(ValueError, "Error-level"):
            self.check()


if __name__ == "__main__":
    unittest.main()
