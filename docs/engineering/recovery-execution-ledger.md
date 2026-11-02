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

## Checkpoint3: bounded evidence, no acceptance substitution

- Commit `6fc4597` contains the verified production robots-cache repair and
  implementation review. Ordinary Entire origin push succeeded (`39257ef..6fc4597`);
  diagnostic checkpoint `c63044d` is included. No force or user-doc staging.
- Cold-option incident `52b269ed5fb1`: **PASS**; all four initial controls pass,
  service-return fails all four, normal gateway restart restores all four and
  actual same-container Storm robots/pending reschedules successfully. Cold
  replacement **NOT EXERCISED**. Three volumes retained, cleanup/host checks pass.
- New opt-in `--compare-fresh-context` tests an A/B/A identity hypothesis without
  changing default acceptance. Original recovered namespace A is a prerequisite
  for creating B; B failure triggers at most one A recheck, never a retry loop.
  An A-only recovery cannot report overall PASS when B fails. Four mocked branch
  regressions pass, taking the M2 Python suite to **89 PASS**.
- Real namespace diagnostic `8f2036152884`: **FAIL at prerequisite A**. All four
  initial clients pass, service restart fails all four, normal gateway restart
  still fails all four within20s/client before HTTP. B/A-recheck/crawler not
  launched. Thus a fresh namespace is not necessary for failure; its causal
  influence remains **UNVERIFIED**, not confirmed. Containers/networks removed,
  three volumes retained, protected host baseline unchanged. Driver:
  `namespace-comparison-20261103.log`. No more identical Tor retries this pass.
- Frontier-only diagnostic `1a7cb4b6f722`: **PASS**, one URL. A consumer JVM exits
  without Known update, an unexpired lease prevents immediate redelivery, server
  SIGKILL/recreation retains the row and makes it requestable, lease expiry
  redelivers, ACKed Known result/future refetch survives another recreation.
  Internal gRPC only; no Tor/HTTP service or claim of sync power-loss/ACK-loss
  durability. Cleanup/volume/host checks pass. Driver:
  `frontier-lifecycle-20261103.log`. Implementation:
  `test/m2/frontier_lifecycle.py`, `FrontierLeaseProbe.java`.
- M1 strict fresh `36bf86224d74`: **PASS**, all three flags true, six records,
  minimum gap2.002960580s, pending resume/retained RocksDB, no forbidden log errors,
  containers/network removed and frontier volume retained. Used current90-test
  artifact, cached images and explicit cleanup2. Driver: `m1-regression-20261103.log`.
- Python M1 opt-in HTTP suite: **32 PASS, no skips** (`M1_HTTP_SMOKE=1`); M3 unit
  helpers: **2 PASS**. These are distinct from Docker acceptance.
- Independent pinned frontier source feasibility: **14 service files compile
  offline, nine unchanged upstream storage tests PASS** on JDK25/release11 with
  exact cached image JAR. Fourth-CF rollback refusal is reproduced; original
  three-CF binary is not a safe rollback after migration. No live DB migrated.
  [Implementation/build boundary](frontier-admission-implementation-plan.md).
  Maven's missing plugin is no longer a source-compilation blocker; new RPC
  generators, atomic storage/auth/migration conformance remain unimplemented.

Strict full M2 remains **NOT EXECUTED / BLOCKED by failed original targeted**;
`m2_verified=false`. Real-onion M3 and durable admission remain BLOCKED. Security
delivery remains separately blocked (`security/latest-result.json`: official
host DNS curl6, no candidate build/install/default change); no bypass/retry.
M3 local HTTP regression and final frontier timing-guard check follow serially,
then exact cleanup/diff/secrets review and a cohesive checkpoint.

### Commands for this checkpoint

Select `JAVA_HOME=$PWD/.runtime/platform-migration/jdk25/jdk-25.0.5+8` and put its
bin first on PATH. Existing approved images/private bridge file are prerequisites
only for the real incident; no bridge material is committed.

```sh
M2_MAVEN_REPOSITORY="$PWD/.runtime/maven-linux-repository" \
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 \
python3 -B test/m2/restart_recovery.py
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 \
python3 -B test/m2/socks_incident.py --cold-gateway-on-failure
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 \
python3 -B test/m2/socks_incident.py --compare-fresh-context
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 \
python3 -B test/m2/frontier_lifecycle.py
M1_CACHED_IMAGES=1 M1_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 bash scripts/m1.sh
python3 -B -m unittest discover -s test/m2
M1_HTTP_SMOKE=1 python3 -B -m unittest discover -s test/m1
python3 -B -m unittest discover -s test/m3
```

All drivers and unit outputs above stay under `.runtime/autonomous-recovery/`.
Do not rerun the failed real network commands without a distinguishing hypothesis
or verified transport/security candidate change.

## Checkpoint4: final independent regressions and preservation

- `M3_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 python3 -B
  test/m3/local_integration.py` executed with JDK25 first on PATH. Fresh
  `f90ef8e542` **PASS**: five records, A/B/C once each, cycle/dedup, provenance,
  depth, robots denial, metadata-only binary link and one temporary-error retry.
  No ERROR/FATAL records in crawler/services logs. This is stock-HTTP internal
  fixture transport; `m3_verified=false` remains correct. Volume retained and
  containers/networks removed. Driver: `m3-local-20261103.log`.
- Final `frontier_lifecycle.py` with the timing guard applied **after** measured
  redelivery passes again in `e4b9595217a2`. This confirms redelivery precedes the
  original120s hold expiry, distinguishing server-restart loss of transient hold
  from ordinary expiry. One row retained throughout; result/lease checks and
  cleanup pass. Driver: `frontier-lifecycle-final-20261103.log`.
- Commit `f62385d` preserves the executed lease/recreation test and pinned
  storage build/migration investigation. It changes no production server/image.
- Final read-only cleanup audit covers the seven completed integration projects:
  no remaining containers/networks; **13/13 expected volumes retained**. The
  protected route/rule/resolver snapshot matches the pre-M1/M3 baseline. No host
  VPN/DNS/routes/firewall/global proxy/daemon mutation command was used.
- Machine-readable results: `.runtime/autonomous-recovery/final-summary-20261103.json`.
  Raw evidence/private configuration stays ignored. `git diff --check` and staged
  secret/path checks pass; user documentation consolidation is excluded.

### Unmet definition of done / next executable work

1. REC-01: exact Tor/path cause remains unknown; original targeted fails at
   CONNECT after one gateway restart. Full M2 is BLOCKED, not skipped-success.
   No blind timeout increase, repeated full run or new circuit-reset experiment.
2. SEC-01: obtain the authenticated candidate bundle via the existing approved
   transfer procedure, validate/build it in isolation, then qualify actual Tor
   version/bootstrap/owned-service lifecycle before changing defaults. No host
   network bypass. Candidate remains unbuilt, installed Tor0.4.9.11 unchanged.
3. ADM-01: next network-independent implementation is the disabled, source-pinned
   server batch participant plus concurrent quota/duplicate/write-failure tests
   from the implementation plan. First resolve compatible RPC generation,
   policy/runtime reuse, auth/legacy/admin guards and rollback build; do not
   migrate a live DB or expose a client before conformance. This is unfinished
   development, not falsely attributed solely to the external Tor blocker.
4. REC-02: the existing local owner remains fixture/probe-dependent; there is no
   independently qualified production/distributed runtime recovery. Do not close
   this through naming changes or add another supervisor. Real-worker lifecycle
   and durable admission remain prerequisites alongside repeatable transport.
5. Then original targeted → full M2 → controlled real-onion M3 with durable
   admission. All original assertions remain required; CLI discover stays offline.

M1 PASS and M3 local PASS are independent positives. **M2 VERIFIED=false,
M3 VERIFIED=false, production-ready=false.** Only TPR-02 is newly closed in the
production TODO inventory; outstanding contracts remain in the debt register.
