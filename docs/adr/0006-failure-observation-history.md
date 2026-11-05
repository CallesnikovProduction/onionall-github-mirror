# ADR 0006 - Append-only failure observations without fabricated attempts

Status: additive catalog extension implemented; previous-reader and closed restore
proof performed on disposable fixtures before and after final DDL. Date:2026-11-04.

## Evidence and decision

The existing failures UNIQUE(job_id,url,category) table intentionally collapses
repeated failures. It cannot represent chronology or distinct attempts. Keep its
identity/first-observed semantics and old API outputs. Add failure_observations_v1,
an independently versioned observation extension with a monotonically generated
sequence, foreign keys to existing job/aggregate and bounded structured fields.
No legacy aggregate is backfilled into invented events or counted as attempts.

Core schema_version remains2: no existing table/column contract is changed. The
new table/index explicitly identify extension version1. This is an additive
persistent schema extension, not a claim that all schema is unchanged. Actual
ArchiveStore source from Security RC0a645fdd compiled as a previous reader reads
the upgraded catalog, preserves new observations and still reads the restored
closed backup. Existing HTML hashes remain identical. Old writers can run after
rollback but will not create new extension observations; that gap is UNKNOWN.

## What a row proves

STORM_STATUS_INTENT means an installed StatusUpdater store callback selected
status/nextFetch before the Frontier write/ACK. A repeated callback can be replay,
so each row is a distinct observation, not proof of a distinct network request.
applied_to_frontier and fetch_attempt_count are null; current terminal/success
counts stay UNKNOWN. The extension never controls admission, quota or nextFetch.
CATALOG_FAILURE_CALL marks legacy callers without finer evidence.

Store plan/URL/time/category, phase/source, HTTP status when available, retryable/
terminal intent and selected next_due_epoch. Never store raw exception messages.
Installed SimpleFetcherBolt3.7.0 bytecode clears fetch.exception but can retain
old HTTP/protocol metadata before a failed request or robots denial. Such frames
therefore get null HTTP and no stale HTTP transport diagnosis; exact installed
Socket timeout fetching maps to REQUEST_TIMEOUT. Lost precise transport cause,
archive-commit origin or actual attempt identity remains UNKNOWN. No protocol or
Tor policy change to invent missing evidence.

The legacy aggregate and new observation commit together under catalog_guard,
before normal Frontier storage/ACK. An event insertion failure rolls back the
aggregate and propagates the existing tuple-fail path. Per-job1000-observation and
128MiB catalog write bounds fail explicitly, never prune old rows automatically.
This new write bound can stop status ACK on exhausted history capacity; operators
must inspect/export data, not expect silent overwrite or an auto-cleaner.

## Read contract

crawl errors <plan> --limit1..100 --cursor <opaque> is an offline versioned view.
The first page fixes through_sequence; subsequent keyset pages remain stable
under appends and clock changes. Sequence gaps from rolled-back inserts are valid.
Rows and observation_count refer to that bounded traversal; new appends appear in
a new traversal. next_cursor/truncated are explicit, cursor binds job/watermark.
legacy_aggregate_count is separate and legacy_aggregates_are_attempts is false.
Status exposes latest_failure_observation/history hint and count, not a fabricated
current failed-job state. Historical failure is not current terminal proof.

## Rollback and qualification

Revert application binaries; old coreV2 readers ignore the extension without
rewriting/deleting it. No destructive downgrade is needed. Before filesystem
backup/restore, stop all catalog owners and copy the complete catalog plus objects
into a new destination; never copy a live H2 file as a consistency guarantee.
The exercised fixtures retained both originals and backup/restore copies.

Tests cover repeated503 observations, current-field nullability, pagination with
append, reopen, SQL event-insert failure/aggregate rollback, old aggregates without
fabricated events, real installed scheduler terminal callback and offline CLI.
Full generated Storm archive-failure/ACK qualification remains a separate E2E gate.
