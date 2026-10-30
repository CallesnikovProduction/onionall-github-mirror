"""Fail on missing crawl, policy, politeness, deduplication or recovery evidence."""
import argparse
from collections import Counter
from datetime import datetime
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


def frontier_outcome(row, captured_at):
    """Infer the stored scheduling outcome; URLFrontier does not store Storm's enum."""
    require(row["kind"] == "known", "Frontier outcome is not known")
    if row["refetch"] == 0:
        return "ERROR"  # Completed marker; meaningful only with the fixture scenario.
    require(isinstance(row["refetch"], int) and row["refetch"] > captured_at, "Frontier retry/fetch schedule is not future")
    return "FETCH_ERROR" if row["metadata"].get("fetch.error.count") else "FETCHED"


def verify_http_404(row, requests, captured_at):
    """Correlate the fixture response with persisted state, independent of logging."""
    hits = [request for request in requests if request["path"] == "/missing"]
    require(len(hits) == 1 and hits[0]["status"] == 404 and hits[0].get("method", "GET") == "GET",
            "Missing/duplicate HTTP 404 fixture response")
    outcome = frontier_outcome(row, captured_at)
    if outcome == "ERROR":
        return outcome  # Terminal completion plus actual GET 404 is a correct outcome.
    metadata = row["metadata"]
    require(not metadata.get("fetch.exception"), "HTTP 404 was recorded as a transport exception")
    if "fetch.statuscode" in metadata:  # Older captures did not persist this field.
        require(metadata["fetch.statuscode"] == ["404"], "Persisted HTTP status is not 404")
    require(row["kind"] == "known" and metadata.get("fetch.error.count") == ["1"],
            "HTTP 404 fetch.error.count must persist the first FETCH_ERROR")
    dates = metadata.get("lastprocesseddate", [])
    try:
        processed = datetime.fromisoformat(dates[0].replace("Z", "+00:00")) if len(dates) == 1 else None
        require(processed is not None and processed.tzinfo is not None, "HTTP 404 processing date missing/invalid")
        processed_at = processed.timestamp()
    except (ValueError, TypeError, IndexError) as error:
        raise ValueError("HTTP 404 processing date missing/invalid") from error
    require(processed_at <= captured_at, "HTTP 404 processing date is after snapshot")
    # M1 fetchInterval.fetch.error=120 minutes; epoch-second schedule rounding.
    require(isinstance(row["refetch"], int) and abs(row["refetch"] - processed_at - 7200) <= 2,
            "HTTP 404 retry schedule must reflect the configured 120-minute FETCH_ERROR interval")
    require(row["refetch"] > captured_at, "HTTP 404 retry schedule is already due")
    return outcome


def verify_spacing(requests):
    """Two processes, each identified by its initial robots fetch; documents only."""
    phase = 0
    documents = {1: [], 2: []}
    for row in sorted(requests, key=lambda row: row["started_ns"]):
        require(row.get("method", "GET") == "GET", "Unexpected HTTP method")
        require(row["finished_ns"] >= row["started_ns"], "Invalid request timing")
        if row["path"] == "/robots.txt":
            phase += 1
            require(phase <= 2, "Unexpected robots phase")
            continue  # Protocol robots bootstrap/cache is outside document-fetch pacing.
        require(phase in documents, "Document request before robots bootstrap")
        allowed = {"/", "/a", "/b", "/missing"} if phase == 1 else {"/pending"}
        require(row["path"] in allowed, f"Unexpected document in crawl phase {phase}: {row['path']}")
        documents[phase].append(row)
    require(phase == 2 and all(documents.values()), "Missing crawl phase requests")
    details = []
    for phase, work in documents.items():
        for previous, current in zip(work, work[1:]):
            gap = (current["started_ns"] - previous["finished_ns"]) / 1e9
            detail = {"phase": phase, "from_path": previous["path"], "to_path": current["path"],
                      "previous_finished_ns": previous["finished_ns"], "next_started_ns": current["started_ns"],
                      "gap_seconds": gap, "minimum_seconds": 1.8,
                      "policy": "same-host document completion-to-next-start; configured 2s, tolerance 0.2s"}
            details.append(detail)
            require(gap >= 1.8, "Observed delay violation: " + json.dumps(detail, sort_keys=True))
    return details


def verify(crawled, before, after, final, requests, first_log, second_log, now=None, runtime=None):
    now = now if now is not None else time.time()
    first_time = crawled.get("captured_at_epoch", now)
    final_time = final.get("captured_at_epoch", now)
    initial, pre, post, end = map(entries, (crawled, before, after, final))
    require(set(initial) == FIRST, "First crawl did not discover exactly the expected five URLs")
    require(all(row["kind"] == "known" for row in initial.values()), "Unprocessed first-phase URLs")
    for url in SUCCESS:
        require(frontier_outcome(initial[url], first_time) == "FETCHED", f"Successful URL has wrong persisted outcome: {url}")
        require(initial[url]["refetch"] > first_time + 3600, f"Successful URL not scheduled in future: {url}")
        require(not initial[url]["metadata"].get("error.cause"), f"Successful URL has error: {url}")
    http_404_outcome = verify_http_404(initial[BASE + "/missing"], requests, first_time)
    # URLFrontier 2.4 RocksDB keeps a completed URL marker, not its metadata.
    require(initial[BASE + "/blocked"]["refetch"] == 0, "Robots-denied URL must be completed without retry")
    pending = BASE + "/pending"
    require(set(pre) == FIRST | {pending}, "Pending seed not persisted before restart")
    # ListURLs exposes stored discovered seeds as known URLs with an immediately due date.
    require(pre[pending]["kind"] == "known" and pre[pending]["metadata"] == {}
            and isinstance(pre[pending]["refetch"], int)
            and 0 < pre[pending]["refetch"] <= before.get("captured_at_epoch", now),
            "Pending seed is not unprocessed and due before restart")
    require(all(pre[url] == initial[url] for url in FIRST), "Completed state changed before restart")
    require(pre == post, "Frontier URL kind/metadata/scheduling changed after recreation")
    require(set(end) == set(pre), "Unexpected corpus after second crawl")
    require(end[pending]["kind"] == "known" and end[pending]["refetch"] > final_time + 3600,
            "Recovered pending URL was not processed and scheduled")
    require(all(end[url] == pre[url] for url in FIRST), "Previously processed records changed during second crawl")
    require(not end[pending]["metadata"].get("error.cause"), "Pending URL finished with an error")
    require(frontier_outcome(end[pending], final_time) == "FETCHED", "Pending URL has wrong persisted outcome")

    counts = Counter(row["path"] for row in requests)
    require(set(counts) == {"/robots.txt", "/", "/a", "/b", "/missing", "/pending"},
            f"Unexpected/missing HTTP requests: {dict(counts)}")
    require(counts["/robots.txt"] == 2, "Expected a robots request in each crawler process")
    require(all(counts[path] == 1 for path in ("/", "/a", "/b", "/missing", "/pending")),
            "Missing request, duplicate fetch or premature revisit")
    for row in requests:
        require(row["user_agent"].lower().startswith("onionallbrawlermonetest"), "Unexpected HTTP client reached fixture")
        require(row["status"] == (404 if row["path"] == "/missing" else 200), "Unexpected HTTP outcome")
    spacing = verify_spacing(requests)
    for text, markers in ((first_log, ("ROOT", "A", "B")), (second_log, ("PENDING",))):
        require(not re.search(r"\s(?:ERROR|FATAL)\s", text), "Error-level crawler log record; inspect run")
        for marker in markers:
            require(re.search(r"(?m)^content(?:\t|:\s*)[^\n]*M1_" + marker + "_MARKER", text) is not None,
                    f"No stdout document content for {marker}")
        require("M1_FORBIDDEN_MARKER" not in text, "Robots-denied content was processed")
    runtime_verified = runtime == {"first_exit_code": 0, "second_exit_code": 0}
    require(runtime is None or runtime_verified, "Crawler process failed or runtime evidence is incomplete")
    return {"assertions_passed": runtime_verified, "crawl_evidence_passed": True,
            "runtime_verified": runtime_verified, "http_404_outcome": http_404_outcome,
            "frontier_records": len(end), "http_counts": dict(counts),
            "minimum_observed_gap_seconds": min(row["gap_seconds"] for row in spacing),
            "document_spacing": spacing,
            "verified": ["controlled HTTP and HTML extraction", "robots denial", "HTTP 404 outcome",
                         "duplicate/cycle handling", "future scheduling", "persisted metadata",
                         "named-volume container recreation", "pending work resumed", "request spacing"],
            "not_verified": (["crawler process exit codes"] if not runtime_verified else [])
                            + ["crash recovery", "Tor", "search index", "distributed Storm", "Tika document parsing"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    args = parser.parse_args()
    def load(name):
        return json.loads((args.run_dir / name).read_text(encoding="utf-8-sig"))
    result = verify(load("crawled.json"), load("before.json"), load("after.json"), load("final.json"),
                    [json.loads(line) for line in (args.run_dir / "requests.jsonl").read_text().splitlines() if line],
                    (args.run_dir / "crawl-first.log").read_text(encoding="utf-8-sig"),
                    (args.run_dir / "crawl-second.log").read_text(encoding="utf-8-sig"),
                    runtime=load("crawl-runtime.json") if (args.run_dir / "crawl-runtime.json").exists() else None)
    if not result["runtime_verified"]:
        print(json.dumps(result, indent=2))
        raise SystemExit("Crawl evidence checks passed, but crawl-runtime.json is absent; full host rerun required")
    (args.run_dir / "verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
