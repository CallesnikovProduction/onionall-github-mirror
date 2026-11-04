# ADR 0007 - Worker crash ownership: approved fail-closed implementation; Storm qualification OPEN

Date:2026-11-04. Owner selected the versioned archive-level fail-closed marker design; implementation follows.
Local implementation below follows that decision. No native OS component or process-controller replacement.

## Reproduced fact (not a successful recovery gate)

WorkerCrashCharacterizationTest launches actual CrawlWorker with an owned idle
JVM child. The child briefly opens/closes its fixture catalog then retains only
an explicit127.0.0.1 listener. After destroyForcibly on the wrapper, child PID/start
identity remains live and the listener is reachable; the archive ownership lock
is released. WorkerObservation correctly reports owner=false/child=true/alive=true.
At pre-fix checkpoint1955ef7, CrawlWorker.requireAvailable nevertheless accepts. A real CrawlExecution
start with valid fixture runtime reaches an admission RPC tripwire, which rejects
before mutation/second worker launch. All resources are owned fixtures and the
surviving child is terminated only after its start identity is rechecked.

This is CONFIRMED for the actual JVM wrapper/guard on Windows, not proof of a full
Storm orphan/port scenario or a remote authorization exploit. Underlying mechanism:
forced process termination bypasses the Java shutdown hook and releases the
wrapper's OS lock without atomically terminating descendants. A file lock proves
only its own process lifetime. H2 may be free between callbacks, so catalog
contention does not substitute for a process guard.

Product impact HIGH conditional availability/integrity: a subsequent permitted
start can overlap an old child, contend on the single-process catalog and race
legitimate status updates. Admission idempotency/quota remain protected. Security
risk LOW/conditional MEDIUM: requires wrapper crash/forced kill plus an authorized
restart and compatible runtime/capability. No missing-capability bypass is claimed;
S1-01 remains intact. Source symbols: CrawlWorker.main/requireAvailable,
CrawlExecution.start. Existing graceful/deadline tests do not cover this condition.

## Owner-approved minimum containment

Use versioned archive-level process ownership metadata, owned by CrawlWorker,
alongside/inside the existing ownership-lock protocol, not only an execution.json
under a selectable work-directory. Record LAUNCHING intent before child creation,
then pinned owner/child identities and local scope, finally a proved exit/cleanup
observation. Check it before any config/resource rewrite, admission or worker
launch. A live child or ambiguous/corrupt/foreign ownership must block new start.
Never equate missing lock/PID/worker exit code with known safe descendants.

Legacy empty/stale files need a compatibility rule: migrate only with explicit
proved stopped ownership; otherwise UNKNOWN requires inspection rather than an
automatic delete/force-resume. Changing work_directory/plan must not bypass the
archive-wide guard. No H2 scheduling queue, new authorization system, automatic
operator cancellation or host network/security configuration change.

This contains unsafe restart; it cannot make Java execute a hook after SIGKILL.
Automatic whole-tree termination under arbitrary owner death requires separately
reviewed OS containment or an external runtime-owner contract. The alternate owner
choice is to leave REC-C BLOCKED while that design is reviewed. No such component
is installed or implemented without the required decision.

## Acceptance before claiming REC-C closed

- Force-kill CLI, wrapper and child separately; pin all identities and retain logs.
- Wrapper death with live child blocks actual start before any RPC/resource rewrite.
- LAUNCHING crash gap, malformed marker, unknown scope and changed work-directory
  cannot bypass the guard; no second crawler or reservation/quota consumption.
- Normal exit, timeout and graceful stop preserve legitimate safe resume.
- Legacy stopped/unknown cases and rollback are explicit; no marker/data deletion
  is used as a recovery shortcut. Test Windows and Linux independently.
- Full generated Storm/SOCKS fixture must qualify the actual process tree; JVM
  stand-in PASS alone is not that gate.

The pre-fix characterization proved the defect. The same fixture now asserts
restart rejection before all RPC/resource writes, including different work/plan,
and continued rejection after the test stops its own orphan. verify.py still
reports worker-crash-acceptance NOT RUN for the full generated Storm tree;
passing JVM regressions do not close that acceptance gate.

Owner resolution: use archive-level versioned ownership, deny live-child and
UNKNOWN restart, migrate legacy only with stopped proof, no OS components.
Checkpoint1955ef7 preserves pre-fix evidence. Current implementation:

- crawl-worker.lock.owner-v1.json is an additive archive-local v1 sidecar; existing
  OS lock remains the live mutual-exclusion primitive. No H2/RocksDB schema change.
- Preparation owns that lock and publishes PREPARING before resource preparation
  or any admission. Normal preparation failure proves no child was created and
  publishes STOPPED. Process death leaves the intent and blocks restart.
- CLI publishes LAUNCHING and hands a run UUID to its exact local child wrapper.
  The wrapper checks parent PID plus start instant, scope, path and ticket while
  holding the same OS lock. It pins its own identity before ProcessBuilder, then
  child identity before readiness. The ticket is removed from the child environment.
- Scope, startInstant, PID and run UUID bind observations; PID reuse never grants
  mutation or a signal. Local files are ownership observations, not an auth system.
- Publication: same-directory forced temp file, ATOMIC_MOVE/REPLACE, optional
  directory force. No non-atomic fallback. Process crash is covered; arbitrary
  filesystem/power-loss durability is not claimed on platforms without directory fsync.
- Managed normal/deadline cleanup records STOPPED only after its own Process exits
  and all observed descendants are absent/pinned identities gone. Unknown identities
  never authorize a signal; a still-present unpinned/reused PID conservatively blocks.
  Missing initial child start identity leaves UNKNOWN even for a fast child exit.
- Windows Process.destroy in the actual fixture leaves the child running and does
  not publish hook cleanup: explicit platform regression requires BLOCKED, then
  test-only cleanup of the pinned owned child. Linux hook behavior remains a distinct
  test, not an inferred Windows PASS. Normal/deadline execution uses the owned wrapper.
- Legacy lock or catalog execution evidence without a v1 marker is BLOCKED and
  preserved, even if the lock file is missing. No auto-migration, deletion, force
  resume or killing from metadata. A retrospective legacy whole-tree stop cannot
  be established by Java PID lookup; approved stopped-proof migration remains
  unavailable until such proof is supplied under a separate operational review.
- Work-directory/plan changes do not bypass the archive guard. Status prefers the
  canonical marker; legacy disappeared roots alone now remain UNKNOWN.

Compatibility/rollback: additive local file, no RPC/protobuf/CLI argument or catalog
schema migration. Older binaries ignore this sidecar and restore the unsafe guard;
rolling back requires proved stopped work and preserves marker/catalog evidence.
Never roll back a live owner or delete a marker to restart. Shared/multi-host filesystems,
unobserved detached grandchildren, OS containment, portable graceful external stop,
power-loss recovery and the full generated Storm crash tree are not qualified.
The marker contains unsafe restart; it is NOT OS process containment. Future forced
whole-tree supervision requires a separate design review. S1-01/full audit NOT CLOSED.

Current Linux CI41886030820 (REC-C commit4c2afb9): Maven PASS, including
WorkerOwnershipTest native Linux destroy path;6 Frontier drivers PASS. Overall
NOT RUN for missing CLI/product/Storm gates. This qualifies the JVM fixture on
both platforms, not the generated Storm tree or OS containment.


REC-C CLI cleanup follow-up: CrawlExecution captures wrapper startInstant once
at ProcessBuilder return and keeps that launch identity in shutdown/timeout paths
and execution observations. Cleanup rejects missing/mismatching identity and an
already exited wrapper before querying descendants. Each observed descendant is
signaled only on its same handle with matching startInstant; unidentified processes
are skipped. Readiness carries the child's already pinned clock instead of a late
PID lookup after readiness. CLI cleanup never publishes STOPPED or deletes proof;
Windows interrupted/unsupported cleanup may still leave UNKNOWN/BLOCKED. This
adds no supervisor/OS containment, external PID adoption or retrospective migration.
