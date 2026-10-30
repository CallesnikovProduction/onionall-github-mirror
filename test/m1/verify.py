"""Fail on missing crawl, policy, politeness, deduplication or recovery evidence."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import time

BASE = "http://fixture:8080"
FIRST = {BASE + path for path in ("/", "/a", "/b", "/blocked", "/missing")}
SUCCESS = {BASE + path for path in ("/", "/a", "/b")}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def entries(document):
    rows = document["entries"]
    require(len(rows) == len({row["url"] for row in rows}), "Duplicate frontier records")
    require(all(row["crawl"] == "m1" and row["key"] == "fixture" for row in rows), "Wrong crawl or queue key")
    return {row["url"]: row for row in rows}


def verify(crawled, before, after, final, requests, first_log, second_log, now=None):
    now = now if now is not None else time.time()
    first_time = crawled.get("captured_at_epoch", now)
    final_time = final.get("captured_at_epoch", now)
    initial, pre, post, end = map(entries, (crawled, before, after, final))
    require(set(initial) == FIRST, "First crawl did not discover exactly the expected five URLs")
    require(all(row["kind"] == "known" for row in initial.values()), "Unprocessed first-phase URLs")
    for url in SUCCESS:
        require(initial[url]["refetch"] > first_time + 3600, f"Successful URL not scheduled in future: {url}")
        require(not initial[url]["metadata"].get("error.cause"), f"Successful URL has error: {url}")
    for path in ("/missing", "/blocked"):
        require(bool(initial[BASE + path]["metadata"].get("error.cause")), f"Missing persisted error metadata for {path}")
    pending = BASE + "/pending"
    require(set(pre) == FIRST | {pending}, "Pending seed not persisted before restart")
    require(pre[pending]["kind"] == "discovered", "Pending seed was already consumed")
    require(all(pre[url] == initial[url] for url in FIRST), "Completed state changed before restart")
    require(pre == post, "Frontier URL kind/metadata/scheduling changed after recreation")
    require(set(end) == set(pre), "Unexpected corpus after second crawl")
    require(end[pending]["kind"] == "known" and end[pending]["refetch"] > final_time + 3600,
            "Recovered pending URL was not processed and scheduled")
    require(all(end[url] == pre[url] for url in FIRST), "Previously processed records changed during second crawl")
    require(not end[pending]["metadata"].get("error.cause"), "Pending URL finished with an error")

    counts = Counter(row["path"] for row in requests)
    require(set(counts) == {"/robots.txt", "/", "/a", "/b", "/missing", "/pending"},
            f"Unexpected/missing HTTP requests: {dict(counts)}")
    require(counts["/robots.txt"] == 2, "Expected a robots request in each crawler process")
    require(all(counts[path] == 1 for path in ("/", "/a", "/b", "/missing", "/pending")),
            "Missing request, duplicate fetch or premature revisit")
    for row in requests:
        require(row["user_agent"].lower().startswith("onionallbrawlermonetest"), "Unexpected HTTP client reached fixture")
        require(row["status"] == (404 if row["path"] == "/missing" else 200), "Unexpected HTTP outcome")
    work = sorted((row for row in requests if row["path"] != "/robots.txt"), key=lambda row: row["started_ns"])
    gaps = [(b["started_ns"] - a["finished_ns"]) / 1e9 for a, b in zip(work, work[1:])]
    require(all(gap >= 1.8 for gap in gaps), f"Observed delay below 2s configuration (0.2s tolerance): {gaps}")
    require(all(row["finished_ns"] >= row["started_ns"] for row in requests), "Invalid request timing")
    for text, markers in ((first_log, ("ROOT", "A", "B")), (second_log, ("PENDING",))):
        require(not re.search(r"\s(?:ERROR|FATAL)\s", text), "Error-level crawler log record; inspect run")
        for marker in markers:
            require(re.search(r"(?m)^.*?\bcontent:\s*[^\n]*M1_" + marker + "_MARKER", text) is not None,
                    f"No stdout document content for {marker}")
        require("M1_FORBIDDEN_MARKER" not in text, "Robots-denied content was processed")
        require("Done shutting down in process zookeeper" in text, "Incomplete LocalCluster shutdown")
        require(re.search(r"(?m)^.*?\sINFO\s+\S*LoggingMetricsConsumer\s+-", text) is not None,
                "No crawler metrics logged")
    return {"assertions_passed": True, "frontier_records": len(end), "http_counts": dict(counts),
            "minimum_observed_gap_seconds": min(gaps),
            "verified": ["controlled HTTP and HTML extraction", "robots denial", "HTTP 404 outcome",
                         "duplicate/cycle handling", "future scheduling", "persisted metadata",
                         "named-volume container recreation", "pending work resumed", "request spacing"],
            "not_verified": ["crash recovery", "Tor", "search index", "distributed Storm", "Tika document parsing"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    args = parser.parse_args()
    def load(name):
        return json.loads((args.run_dir / name).read_text(encoding="utf-8-sig"))
    result = verify(load("crawled.json"), load("before.json"), load("after.json"), load("final.json"),
                    [json.loads(line) for line in (args.run_dir / "requests.jsonl").read_text().splitlines() if line],
                    (args.run_dir / "crawl-first.log").read_text(encoding="utf-8-sig"),
                    (args.run_dir / "crawl-second.log").read_text(encoding="utf-8-sig"))
    (args.run_dir / "verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
