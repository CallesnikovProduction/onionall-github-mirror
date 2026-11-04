# ADR 0005 - Job state and evidence-based completion

Status: M5 owner-approved UNKNOWN policy; pure predicate implemented, CLI wiring follows.
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
