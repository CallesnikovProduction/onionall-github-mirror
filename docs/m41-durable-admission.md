# M4.1 durable bounded crawl

The configured CLI path is plan → approve → start → status → pages/search-local.
Stock Frontier remains unsupported for admission: the command fails closed rather
than count-before-put. Use the versioned owning server in `frontier-server/`.

## Runtime and CLI

Build the application with JDK25 (`ONIONALL_OFFLINE=1 bash scripts/cli/build-cli.sh`)
and build the server with `bash frontier-server/build.sh --test`; see its README
for the pinned baseline JAR. Deploy the candidate on a fresh isolated volume and
internal crawler network without publishing ports. Set a private32..256 character
ONIONALL_ADMISSION_TOKEN on the server. Store the matching operator/pipeline
capability in a private file, never an argument, fetched metadata or generated
JSON. Existing user volumes must not be migrated by the acceptance runner.

An operator-owned runtime JSON describes addresses visible inside the execution
namespace and an installed Storm runtime:

```json
{
  "frontier_host": "frontier", "frontier_port": 7071,
  "socks_host": "tor", "socks_port": 9050,
  "storm_executable": "/apache-storm/bin/storm",
  "crawler_jar": "/workspace/onionall-brawler.jar",
  "work_directory": "/workspace/execution",
  "seconds": 120, "http_timeout_ms": 20000, "retry_minutes": 120
}
```

The controlled acceptance substitutes an internal SOCKS fixture for connectivity;
it retains real TorProtocol, StormCrawler and authoritative RocksDB admission.
Production onion traffic still requires official Tor. Runtime descriptors are
trusted deployment inputs, never supplied by remote content. The admission RPC
is internal plaintext capability traffic and must not cross an untrusted network.

```sh
onionall --json crawl plan --seed APPROVED_ONION_URL --depth 2 --max-pages 20
onionall --json crawl approve PLAN_ID
onionall --json crawl start --plan PLAN_ID --runtime runtime.json --admission-token-file private-token
onionall --json crawl status PLAN_ID --runtime runtime.json --admission-token-file private-token
onionall --json pages --job PLAN_ID
onionall --json search-local keyword --job PLAN_ID
```

Start is foreground and bounded1..600s. HTTP calls remain finite (at most30s).
The command verifies committed seed reservations before launching Storm. A worker
exit0 means WORKER_EXITED, never automatic full-crawl completion. Retry dates and
leases remain authoritative in Frontier; a later start resumes the same plan and
budget. Failures and snapshots remain visible in the catalog. Runtime artifacts
and logs are retained in the operator's work directory; no automatic cleanup.

The child-owned archive-wide process lock/deadline outlive a killed initiating
CLI and prevent two local workers owning the embedded H2 archive. Cross-host
execution ownership is outside the single-user local runtime contract. A live
callback can temporarily make embedded H2 busy; status must report that honestly.

## Storage and reconciliation

Admission quota includes seeds and robots-denied URLs. Unique reservations never
release slots when fetches succeed/fail. Canonical identity and source-derived
depth are checked in the server. Duplicate replay never increments count or
re-enqueues a completed URL. Raw out-of-scope HTTP links are graph observations
only. The parser clears legacy outlinks after explicit durable reservation.

RocksDB commit and H2 catalog update are not a distributed transaction. A lost
catalog observation is repaired by querying the identical authoritative session;
status readback does not reserve or enqueue. Child reservations can commit before
the parent archive write; an archive failure does not undo those reservations.
Replayed processing remains idempotent. Terminal failures are recorded by
ArchiveStatusUpdaterBolt before the normal Frontier status write; stock terminal
Frontier metadata loss still exists, so preserve the associated catalog.

Graph schema2 retains first observed link time with an explicit clock basis,
source/snapshot/job provenance, source/target hosts and dispositions. Target page
IDs are resolved dynamically within the same job without recrawling. Legacy
missing rejected URLs cannot be reconstructed. Old archive binaries reject V2;
retain a backup before deployment. RocksDB's fourth CF separately requires the
admission-aware compatible rollback described in frontier-server/README.md.

## Verification

Run `python3 -B test/admission/local_integration.py` after both builds, with JDK25
and explicit cleanup2 Storm image. It uses only fresh isolated volumes and no
external Tor. It checks CLI approval, actual pipeline,503 retry, worker/server
recreation, quota/replay, real snapshots/search and graph provenance. Primitive
crash/concurrency/migration/auth tests run in the server build. Real-Tor M2/M3
qualification remains separate and is not promoted by these local checks.
Exact executed results and failures: engineering/m41-execution-ledger.md.

Verified packaged acceptance: a7c09e2448f4,4 durable reservations,3 archived pages,
8 directed edges; restart/retry/replay and cleanup all pass. See the ledger for
exact artifact hashes and raw evidence paths. This does not qualify real Tor.
