# ADR 0007 - Worker crash ownership: design approved, implementation OPEN

Date:2026-11-04. Owner selected the versioned archive-level fail-closed marker design; implementation follows.
No native OS component, process-controller replacement or production fix yet.

## Reproduced fact (not a successful recovery gate)

WorkerCrashCharacterizationTest launches actual CrawlWorker with an owned idle
JVM child. The child briefly opens/closes its fixture catalog then retains only
an explicit127.0.0.1 listener. After destroyForcibly on the wrapper, child PID/start
identity remains live and the listener is reachable; the archive ownership lock
is released. WorkerObservation correctly reports owner=false/child=true/alive=true.
Existing CrawlWorker.requireAvailable nevertheless accepts. A real CrawlExecution
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

Characterization PASS currently means defect reproduced. verify.py explicitly
reports worker-crash-acceptance NOT RUN, so it cannot give a full green result
even after core tests or product E2E pass. The gate must become a real accepted
regression after the chosen design is implemented and exercised.

Owner resolution: use archive-level versioned ownership, deny live-child and
UNKNOWN restart, migrate legacy only with stopped proof, no OS components.
The characterization checkpoint below precedes implementation; it is not a fix.
