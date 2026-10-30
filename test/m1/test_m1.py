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
from verify import BASE, FIRST, SUCCESS, verify, verify_spacing, frontier_outcome, verify_http_404


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
        self.assertEqual(original.split("spouts:\n")[1].replace(
            'className: "org.apache.stormcrawler.bolt.FetcherBolt"',
            'className: "org.apache.stormcrawler.bolt.SimpleFetcherBolt"'), derived.split("spouts:\n")[1])
        self.assertIn('file: "test/m1/crawler-conf.yaml"', derived)
        with self.assertRaises(ValueError):
            prepare.topology("unexpected topology")


class VerifierTest(unittest.TestCase):
    def setUp(self):
        def known(url):
            metadata = {}
            refetch = 86400 if url in SUCCESS else 0
            if url == BASE + "/missing":
                metadata = {"fetch.error.count": ["1"], "lastprocesseddate": ["1970-01-01T00:01:30Z"]}
                refetch = 7290  # FETCH_ERROR: processed at epoch 90 + 120 minutes.
            return {"url": url, "crawl": "m1", "key": "fixture", "kind": "known",
                    "refetch": refetch, "metadata": metadata}
        initial = [known(url) for url in sorted(FIRST)]
        # RocksDB ListURLs normalizes inserted discovered seeds to known/due records.
        pending = {"url": BASE + "/pending", "crawl": "m1", "key": "fixture", "kind": "known",
                   "refetch": 105, "metadata": {}}
        self.crawled = {"entries": initial, "captured_at_epoch": 100}
        self.before = {"entries": copy.deepcopy(initial) + [pending], "captured_at_epoch": 110}
        self.after = copy.deepcopy(self.before)
        self.final = copy.deepcopy(self.before)
        self.final["entries"][-1].update(kind="known", refetch=86400)
        self.final["captured_at_epoch"] = 200
        self.requests = []
        for index, path in enumerate(("/robots.txt", "/", "/a", "/b", "/missing", "/robots.txt", "/pending")):
            self.requests.append({"path": path, "status": 404 if path == "/missing" else 200,
                                  "started_ns": index * 3_000_000_000, "finished_ns": index * 3_000_000_000 + 1,
                                  "user_agent": "OnionallBrawlerMOneTest/0.1"})
        tail = "\n INFO o.a.s.m.LoggingMetricsConsumer - synthetic counters\nDone shutting down in process zookeeper\n"
        self.first_log = (" INFO o.a.s.b.FetcherBolt - [Fetcher #4] Fetched http://fixture:8080/missing with status 404 in msec 3\n"
                          " INFO o.a.s.b.FetcherBolt - Denied by robots.txt: http://fixture:8080/blocked\n")
        self.first_log += "\n".join("content\tM1_" + marker + "_MARKER" for marker in ("ROOT", "A", "B")) + tail
        self.second_log = "content: M1_PENDING_MARKER" + tail

    def check(self):
        return verify(self.crawled, self.before, self.after, self.final,
                      self.requests, self.first_log, self.second_log, now=0,
                      runtime={"first_exit_code": 0, "second_exit_code": 0})

    def test_complete_synthetic_evidence_is_accepted(self):
        self.assertTrue(self.check()["assertions_passed"])

    def test_http_404_without_error_cause_is_accepted(self):
        missing = next(row for row in self.crawled["entries"] if row["url"] == BASE + "/missing")
        self.assertNotIn("error.cause", missing["metadata"])
        self.assertTrue(self.check()["assertions_passed"])

    def test_http_404_does_not_depend_on_info_log_format(self):
        self.first_log = self.first_log.replace("with status 404 in msec 3", "with status 404 in 3 after waiting 0")
        self.assertTrue(self.check()["assertions_passed"])

    def test_no_false_pass_for_404_without_persisted_error_count(self):
        next(row for row in self.crawled["entries"] if row["url"] == BASE + "/missing")["metadata"].clear()
        with self.assertRaisesRegex(ValueError, "HTTP 404 fetch.error.count"):
            self.check()

    def test_no_false_pass_for_404_with_success_schedule(self):
        next(row for row in self.crawled["entries"] if row["url"] == BASE + "/missing")["refetch"] = 86490
        with self.assertRaisesRegex(ValueError, "HTTP 404 retry schedule"):
            self.check()

    def test_no_false_pass_for_404_without_processing_date(self):
        next(row for row in self.crawled["entries"] if row["url"] == BASE + "/missing")["metadata"].pop("lastprocesseddate")
        with self.assertRaisesRegex(ValueError, "HTTP 404 processing date"):
            self.check()

    def test_no_false_pass_for_404_not_served_by_fixture(self):
        self.requests[4]["status"] = 200
        with self.assertRaisesRegex(ValueError, "HTTP 404 fixture response"):
            self.check()

    def test_robots_enforcement_does_not_require_info_message(self):
        self.first_log = self.first_log.replace("Denied by robots.txt:", "Ignored:")
        self.assertTrue(self.check()["assertions_passed"])

    def test_no_info_messages_needed_for_correct_crawl(self):
        self.first_log = "\n".join(line for line in self.first_log.splitlines() if " INFO " not in line)
        self.second_log = "content\tM1_PENDING_MARKER"
        self.assertTrue(self.check()["assertions_passed"])

    def test_404_terminal_error_is_accepted_and_survives_restart(self):
        for document in (self.crawled, self.before, self.after, self.final):
            next(row for row in document["entries"] if row["url"] == BASE + "/missing").update(metadata={}, refetch=0)
        self.assertEqual("ERROR", self.check()["http_404_outcome"])

    def test_http_200_must_not_have_persisted_fetch_error(self):
        self.crawled["entries"][0]["metadata"]["fetch.error.count"] = ["1"]
        with self.assertRaisesRegex(ValueError, "Successful URL has wrong persisted outcome"):
            self.check()

    def test_transport_failure_cannot_substitute_for_http_404(self):
        row = next(row for row in self.crawled["entries"] if row["url"] == BASE + "/missing")
        row["metadata"]["fetch.exception"] = ["Connection refused"]
        self.assertEqual("FETCH_ERROR", frontier_outcome(row, 100))
        with self.assertRaisesRegex(ValueError, "HTTP 404 fixture response"):
            verify_http_404(row, [], 100)

    def test_http_response_and_persisted_transport_error_are_not_conflated(self):
        row = next(row for row in self.crawled["entries"] if row["url"] == BASE + "/missing")
        row["metadata"]["fetch.exception"] = ["Read timeout"]
        with self.assertRaisesRegex(ValueError, "transport exception"):
            self.check()

    def test_wrong_persisted_http_code_is_rejected(self):
        row = next(row for row in self.crawled["entries"] if row["url"] == BASE + "/missing")
        row["metadata"]["fetch.statuscode"] = ["200"]
        with self.assertRaisesRegex(ValueError, "Persisted HTTP status"):
            self.check()

    def test_runtime_exit_codes_are_required_for_full_acceptance(self):
        args = (self.crawled, self.before, self.after, self.final, self.requests, self.first_log, self.second_log)
        result = verify(*args, now=0)
        self.assertTrue(result["crawl_evidence_passed"])
        self.assertFalse(result["assertions_passed"])
        with self.assertRaisesRegex(ValueError, "Crawler process failed"):
            verify(*args, now=0, runtime={"first_exit_code": 0, "second_exit_code": 1})

    def test_no_false_pass_for_robots_retry(self):
        next(row for row in self.crawled["entries"] if row["url"] == BASE + "/blocked")["refetch"] = 7200
        with self.assertRaisesRegex(ValueError, "Robots-denied URL"):
            self.check()

    def test_no_false_pass_for_consumed_pending_seed(self):
        self.before["entries"][-1]["metadata"] = {"lastprocesseddate": ["1970-01-01T00:01:45Z"]}
        with self.assertRaisesRegex(ValueError, "Pending seed is not unprocessed"):
            self.check()

    def test_no_false_pass_for_pending_seed_without_schedule(self):
        self.before["entries"][-1]["refetch"] = 0
        with self.assertRaisesRegex(ValueError, "Pending seed is not unprocessed"):
            self.check()

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
        self.first_log = self.first_log.replace("content\tM1_A_MARKER", "")
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


class SpacingTest(unittest.TestCase):
    def rows(self):
        # Sanitized real db1edcb7fb0c timestamps; includes both crawl processes.
        return [{"path": path, "started_ns": start, "finished_ns": end, "method": "GET"}
                for path, start, end in (
                    ("/robots.txt", 11939101438502, 11939101534583),
                    ("/", 11939124496377, 11939124632634),
                    ("/a", 11940131781050, 11940132058849),
                    ("/b", 11942138408701, 11942138517041),
                    ("/missing", 11944145153759, 11944145268395),
                    ("/robots.txt", 12011981105717, 12011981201436),
                    ("/pending", 12012006172530, 12012006314204))]

    def test_actual_short_gap_is_rejected_with_paths_times_and_policy(self):
        with self.assertRaises(ValueError) as raised:
            verify_spacing(self.rows())
        detail = json.loads(str(raised.exception).split(": ", 1)[1])
        self.assertEqual(("/", "/a", 1), (detail["from_path"], detail["to_path"], detail["phase"]))
        self.assertEqual(11939124632634, detail["previous_finished_ns"])
        self.assertEqual(11940131781050, detail["next_started_ns"])
        self.assertAlmostEqual(1.007148416, detail["gap_seconds"])
        self.assertEqual(1.8, detail["minimum_seconds"])

    def test_robots_bootstrap_and_process_boundary_are_not_document_pairs(self):
        rows = self.rows()
        # Repair only the synthetic first gap; preserve a second process boundary
        # shorter than 2s to prove it is classified independently, not masked by 68s.
        for row in rows[:2]:
            row["started_ns"] -= 1_000_000_000
            row["finished_ns"] -= 1_000_000_000
        end = rows[4]["finished_ns"]
        rows[5].update(started_ns=end + 1, finished_ns=end + 2)
        rows[6].update(started_ns=end + 3, finished_ns=end + 4)
        details = verify_spacing(rows)
        self.assertEqual(3, len(details))
        self.assertTrue(all(row["phase"] == 1 for row in details))
        self.assertTrue(all(row["gap_seconds"] >= 1.8 for row in details))

    def test_unknown_traffic_is_not_silently_excluded(self):
        rows = self.rows()
        rows[1]["path"] = "/health"
        with self.assertRaisesRegex(ValueError, "Unexpected document"):
            verify_spacing(rows)

    def test_wrong_method_is_rejected(self):
        rows = self.rows()
        rows[1]["method"] = "POST"
        with self.assertRaisesRegex(ValueError, "Unexpected HTTP method"):
            verify_spacing(rows)


if __name__ == "__main__":
    unittest.main()
