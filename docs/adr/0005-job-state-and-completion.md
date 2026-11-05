# ADR 0005 - Job state and evidence-based completion

Status: M5 owner-approved UNKNOWN policy; v1 CLI status/start view and bounded audit implemented.
Date:2026-11-04. Refines ADR0004 without changing Frontier scheduling.

Job state and completion are separate. A live owned process plus server observation
is RUNNING; stopped execution with pending/held/future scheduled rows is PAUSED and
INCOMPLETE for exit0, timeout124 and other exit codes alike. Exit code never proves
crawl completion or unrecoverable job failure. Unknown liveness cannot prove PAUSED.
Frontier unavailable is UNKNOWN even if an old cached worker result says passed.
Catalog approval alone is DRAFT/APPROVED, not admission.

UNSCHEDULED without outcome provenance is UNKNOWN completion, not successful done.
COMPLETE requires stopped process, no scheduled/held/unknown rows, and explicit
authoritative success for every reservation with zero terminal failures. PARTIAL
requires equally explicit terminal outcome proof and exhausted scheduled work.
Pure predicate fixtures verify those proof conditions; the current real v1 client
intentionally supplies null success/done/terminal, so cannot emit either outcome
from UNSCHEDULED alone. Historical H2 pages/failures are not substituted for proof.

FAILED requires demonstrated unrecoverable job failure, ABANDONED an explicit
operator mutation; neither is inferred by this predicate. No cancel/abandon API,
background supervisor, H2 pending queue, terminal schema or automatic retry-all.

JobStatus is a pure domain seam, not a second scheduler. CLI/service integration
will expose a versioned job_state/completion plus observation basis, while keeping
legacy execution-state fields distinct. Current v1 status cannot promise COMPLETE
for an all-unscheduled job; that limitation is intentional owner-approved truth.

## Versioned application view

crawl status and crawl start expose job_status_version1, job_state, completion,
state_basis, worker, scheduler, counts and next_attempt_at/resume_hint. Existing
state/passed/worker_exit_code keep their execution meaning; state_semantics and
legacy_state explicitly identify that compatibility surface. Root and nested
job_status carry the same current view. Catalog plan admission now says UNOBSERVED
with CATALOG_PLAN_ONLY basis, replacing the obsolete permanent ADR blocker.

Unavailable/malformed/unsupported Frontier readback yields UNKNOWN with explicit
available/reachable/basis and null current scheduler counts. last_authoritative_counts
and cached_readback_at are separate stale observations, never completion inputs.
Catalog snapshot counts are observations, not scheduler-derived successful counts.
Failure-record list truncation and32-entry transition-audit truncation are explicit.

Worker identity requires PID plus matching start instant and local process scope.
Linux scope hashes boot/PID-namespace identity; Windows scope hashes the computer
name, and matching process start instants protect PID reuse. This is an observational
locality check, not a new authentication mechanism. Unknown/legacy/malformed/foreign
markers and unidentified held locks never prove running or stopped. A root/child
process still alive is reported; a stale lock file alone is not a process. The
status probe never terminates processes or writes lock contents. Existing workers
without new identity metadata may report UNKNOWN until a new run produces evidence.

Audit lives as bounded JSON observations in existing schemaV2, serializes on the
existing catalog guard, survives new execution observations and preserves the
admission observation clock during status-only updates. Prior history isn't
invented. No H2 scheduler, new table, AUTO_SERVER mode or persistent migration.
H2 remains single-process: contention produces an explicit catalog error; it is
not interpreted as no work or COMPLETE. No second JVM read-only connection bypass.

Rollback: prior binaries can read schemaV2 and ignore additive JSON keys. Legacy
execution/admission guards remain; scripts parsing the obsolete BLOCKED_ADR_0002
plan value must use UNOBSERVED/admission_basis or the new versioned job view.
This scope does not qualify full Storm/SOCKS E2E or durable terminal provenance.

REC-B view adds resume_policy=BOUNDED_MANUAL and a PAUSED-only resume_note:
URLs execute only with a running worker and when due; starting a bounded run
before next_attempt_at may do no work. UNKNOWN does not retain a cached resume
hint/note. No retry interval, due time, quota or automatic supervisor change.
