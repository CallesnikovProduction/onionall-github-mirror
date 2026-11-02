# Recovery execution ledger

## 2026-11-02: baseline39257ef

Working tree initially clean. Numeric proxy endpoint bug fixed, independently
from Tor stream availability. Last same-namespace incident a3f5c6720038 passed
actual Storm pending/robots; repeat b66bf31c112f failed across Python0/2, native
JDK and TorProtocol. Storm is not a necessary cause. Strict M1 cleanup2 has two
prior fresh passing runs; no framework/CLI changes needed for this baseline.

Current experiment: preserve original incident variant; optional one cold gateway
replacement after the original restart fails, with a new client directory inside
the existing retained tor-state volume. Old data and onion keys remain untouched.
Replacement changes gateway IP, so ONLY newly configured clients participate;
no live configured crawler resumes. Actual Storm runs only after all four controls
pass, in the same namespace. No host networking, isolation or timeout change.
This diagnostic does not claim production recovery or m2_verified=true.

Next command (after fresh offline Maven verify):

```sh
JAVA_HOME="$PWD/.runtime/platform-migration/jdk25/jdk-25.0.5+8" \
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 \
python3 -B test/m2/socks_incident.py --cold-gateway-on-failure
```

Evidence root: .runtime/autonomous-recovery/; each incident keeps its unique
.runtime/m2 project. Security delivery is separately blocked: official host DNS
resolution curl6; existing negative verifier4 tests pass. No retry/bypass.

## Checkpoint1: controlled incident c8d4cc4db8e8

Command above executed against fresh Maven84-test artifact: incident PASS,
actual Storm robots+pending marker and durable HTTP200 future-refetch confirmed,
no ERROR records. All four initial controls pass; after service restart all fail;
normal gateway restart restores all four. Cold fallback NOT EXERCISED. This
positive does not erase previous failed repeats or set m2_verified=true.
Own containers/networks removed, three volumes retained, protected host baseline
unchanged within the run. No larger timeouts or isolation changes.

Next: finish regression for confirmed cross-origin robots head-of-line blocking,
then fresh Maven verify and run original targeted restart recovery. Stop on first
failure and retain evidence; full M2 remains conditional on targeted success.

## Checkpoint2: robots isolation, resumed 2026-11-03

Preserved uncommitted TorProtocol/cache regression work from the interrupted
session. Offline `ONIONALL_OFFLINE=1 bash scripts/build-cli.sh` finished successfully
in saved `maven-cache-fix.log`: **90 tests, zero failures/errors/skips**. The former
global-monitor HOL regression fails before the fix (`robots-cache/before.log`);
the six cache cases plus 35 surrounding protocol cases pass (`after.log`).
Python `-B -m unittest discover -s test/m2`: **85 PASS**; CLI/PTY suite: **16 PASS**.
Logs: `.runtime/autonomous-recovery/{python-m2,cli}-20261103.log`.

Original `test/m2/restart_recovery.py` launched once on the packaged artifact,
explicit cleanup2 image, JDK25 and cached repository; no timeout/assertion changes.
Driver: `.runtime/autonomous-recovery/targeted-resume-20261103.log`.
Project: `onionall-brawler-m2-9560fd480748`. **FAIL**, exit1. Initial health and
identity persistence pass; after service restart the one allowed gateway restart
also fails at SOCKS CONNECT reply (30.006s), before HTTP. Real JVM series/crawler
not launched. Quarantine retained, gateway stopped, containers/networks removed,
all three volumes retained, host routes/rules/resolver unchanged. Source of truth:
`restart-recovery.json`, `gateway-recovery.json`, `recovery-health.json` in that
project. No full M2 launched: prerequisite failed. `m2_verified=false`.
This runner takes read-only host-route/rules/resolver snapshots around its own
isolated Compose project and retains all persistent volumes.

User documentation consolidation is separate work: tracked Markdown deletions,
new summary Markdown, `docs/m2-docs.zip` and `scripts/AGENTS.md` appeared while
paused. They are preserved, excluded from recovery commits, and not restored or
removed. New scripts instructions have been read; public entrypoints retained.

Next distinguishing experiment (one run only): existing
`socks_incident.py --cold-gateway-on-failure`, same JDK25/cleanup2 artifact. The
earlier c8 run never exercised cold state. Compare four clients before/after
service restart, then normal restart, and only if still blocked use a new Tor
client directory in the retained volume. Driver:
`.runtime/autonomous-recovery/cold-comparison-20261103.log`. Do not infer a
persistent-state cause unless the corresponding comparison actually executes;
even cold success cannot exclude time-varying external-network effects.
