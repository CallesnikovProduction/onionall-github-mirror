# M2 hardening and portability audit — 2026-10-31

Historical audit records accompany bridge integration. The 2026-11-02 closure
request authorizes committing/pushing independently verified harness fixes,
without claiming failed acceptance gates have passed.

## Implementation pass — 2026-11-02

Afterc99064f, Nimbus source and installed bytecode confirm two independent
lifecycle races. LocalFsBlobStore publishes key/meta before data commit, while
cleanup reads it outside the existing submission lock. The first backport
serializes doCleanup on submitLock: fresh M193e27294af36 passes, but repeat
fcab856c3399 catches a separate shutdown interruption in cleanupTimer.close.
Revision2 publishes a stop flag, drains active cleanup on the same lock, then
closes the timer outside the lock before disconnecting dependencies. Real Nimbus
lock and real StormTimer shutdown probes fail baseline and pass the corrected
code; late cleanup performs no dependency I/O. No ERROR handling/timers/assertions
are disabled. See docker/storm-lifecycle/README.md and build-storm-lifecycle.sh.
Both original images and cleanup1 remain available; cleanup2 is explicit via
M1_STORM_IMAGE/M2_STORM_IMAGE, with actual image IDs recorded by harnesses.

M2 now has opt-in bounded redacted TOR_TRACE (256 events/client, JVM/request IDs,
UTC/thread, hashed host, connect/TLS/acquire/cancel/close). Native SOCKS subphases
are not inferred from EventListener. Standalone verification uses a different
container/client address from the next crawler; Tor's default IsolateClientAddr
means it cannot establish that crawler's circuit readiness. The targeted variant
therefore runs one finite verification series inside the actual crawler container
before Storm, without changing isolation or retry/network deadlines. This remains
an experiment until actual resumed crawler/pending assertions pass; defaults and
standalone variant are preserved. Outer command budgets include the added finite
series time, rather than changing the20s network deadline.

Revision2 M1 repeatability:25da2046fecb andd927d1d8835e PASS (strict original
verifier, zero ERROR/FATAL, six records, pending recovery, cleanup and retained
volumes). Commitb8ee0da pushed to Entire. No default image pin changed; explicitly
select onionall-storm:3.1.0-jre25-cleanup2 using M1_STORM_IMAGE/M2_STORM_IMAGE.

New targeted19aa14dfd670 FAIL before the same-container variant could execute:
initial owned health succeeds9.835s; keys unchanged; post-service-restart SOCKS
CONNECT times out30.018s; one gateway restart restores Python HTTP health7.086s.
The first standalone Java robots call emits socks_connect_start, then watchdog
cancellation at20004ms and call_failed at20006ms, with no acquired connection or
HTTP event. Controller closes gateway, never resumes the consumer, and cleanup
retains all three volumes. Same-container readiness remains UNVERIFIED, not an
established fix. No identical retry or dependent full M2 was launched. M2
repeatability requirement of three passing runs remains unsatisfied.

Live tracing exposed an instrumentation privacy defect: sanitized/truncated
OkHttp thread names still include partial URL-derived content. The deliverable
uses only thread_id plus fixed thread_kind categories; regression checks schema.
Previously captured traces stay ignored as potentially sensitive diagnostics.
No URL, path, query, auth or raw thread names are exported by the corrected trace.
This is opt-in diagnostic code, not proof of DNS isolation or a transport fix.

Final checks: Java25 Maven82, Python M282/M131+1skip/M32, CLI/PTY15 pass.
All seven new volumes retained; no remaining containers/networks for these runs.
Target before/after protected host state matches, but the final cross-session
comparison detects changed routes/resolver (rules unchanged). No host mutation
commands were executed; origin of the later change is not established. Do not
restore host settings; capture fresh read-only preflight before any new Tor run.
Final evidence summary: .runtime/recovery-implementation-20261102/summary.json.

ADR0003 is a concrete PROPOSED additive Frontier service/storage contract, not
an activated admission implementation. CLI admission remains disabled. Full M3
requires server-source/storage work and real-onion verification after M2 passes.

Tor delivery recheck: official InRelease and signing key downloaded over ordinary
HTTPS and signature/fingerprint verified. Packages connection timed out5s; no
retry, package download, candidate build, default pin or host network change.
Exact manual bundle procedure/result: ignored recovery-implementation-20261102/security.
Tor stays0.4.9.11; production-ready remains false.

## Recovery closure attempt — 2026-11-02

Branch migration/java25-storm31, starting checkpoint0998050. Existing M1/M2/M3
variants and acceptance assertions are preserved; CLI, parser, TorProtocol,
transport settings and dependency pins are unchanged. No new recovery layer.

Confirmed defects fixed:

- M2 used an mtime scan to find M1's successful result even after a failed
  child command. Latest prior4f6890b7bf1c failed Docker Hub resolution before
  result creation, then misleadingly reported missing evidence. Parent now
  selects an isolated child project/path before launch, saves partial evidence
  and exit first, and reports image resolution separately. Existing evidence
  paths and arbitrary project names are rejected. No timestamp inference.
- Explicit `--cached-images` now propagates to the M1 child. Both Bash and
  PowerShell support M1_CACHED_IMAGES=1, inspect/record the unchanged pinned
  images and select pull_policy=never for every M1 service and dependency.
  Default online behavior remains; no substitutes or host network changes.
- The recovery controller required a nested HTTP object even when a failed
  network_probe correctly emitted a flat pre-HTTP phase/error record. Such
  CONNECT failure is now reported with phase/type/elapsed evidence; quarantine
  and gateway stop remain mandatory. Array/null/malformed success stay rejected.

Fresh run8b64cfc89746, Tor0.4.9.11 (actual network-disabled --version):

1. Isolated preflight: live bootstrap100, SOCKS ATYP3, HTTPS200 IsTor=true,
   bridge process present, temporary container removed.
2. Controlled initial /health succeeds. Owned onion is stopped/restarted;
   hostname and key hashes remain identical. A fresh socket gets through SOCKS
   greeting, then CONNECT reply times out30.024s.
3. The existing one-cycle controller quarantines a consumer and restarts the
   same gateway container. Python HTTP canary succeeds in11.853s; real JVM
   TorProtocol completes robots/health three times plus final robots parsing.
4. Consumer resumes; pending frontier snapshot is unchanged. A real Storm
   crawler then fails its next robots fetch: ROBOTS_UNAVAILABLE -> REQUEST_TIMEOUT
   -> SocketException in JDK SocksSocketImpl.readSocksReply/connect. This is
   before HTTP; the Java stack does not identify greeting versus CONNECT reply.
   No pending page request reaches the fixture. Its URL remains durably present
   with fetch.error.count=1 and a future retry/backoff, rather than being lost.
5. Targeted acceptance remains FAIL (`Pending content did not resume`);
   m2_verified=false. Containers/networks removed, all three volumes retained,
   protected VPN/default routes/rules/DNS unchanged.

Independent cached M1 child regressionac0f49b9578d-m1 also FAILS in this fresh
run: both crawler exits0, six records, before==after and pending fetched, but
unchanged strict verifier detects Nimbus cleanupTimer ERROR/BlobStoreUtils:
FileNotFoundException for stormcode.ser during topology submission. This occurs before the first fetch, during overlapping submission/cleanup;
a Storm3.1 blob lifecycle race is a supported hypothesis, not a source-level
proven cause. It is not a pull failure or observed frontier loss. No log suppression, ERROR whitelist, cleanup-timer workaround or
acceptance change was made. Parent correctly records exit1 and the exact partial
path instead of reporting a missing-evidence race. Prior M1 accepted evidence
remains intact; this new regression must not be called green.

A successful canary or finite protocol series does not establish future stream
availability. The exclusive Tor/path cause of the subsequent negotiation stall
is still unproven. Existing one-shot recovery cannot promise stable runtime
resume; it remains fixture-coupled M2 tooling. No timeout increase, additional
recovery loop, DIRECT fallback, manual circuit reset or security relaxation was
used. Full M2 is BLOCKED behind the failed targeted prerequisite, not reported
as an executed PASS/FAIL. Do not rerun identical full gates searching for luck.

A suspected verifier mismatch was rejected after reading fixture.py: /health is
intentionally absent from requests.jsonl, so four logged recovery robots requests
are correct. The exploratory synthetic change was removed entirely; original
verifier/test_harness assertions remain. Its failed synthetic output is retained
as rejected-verifier-hypothesis.log, not evidence of a product defect.

M3 has no existing full real-onion E2E runner: prepare.py emits Tor configuration,
while local_integration.py deliberately uses isolated stock HTTP. Keep that
variant as a local diagnostic. Durable global admission still requires ADR0002's
additive server/storage/API contract (atomic unique reservation + quota + enqueue,
restart and ambiguous-ACK tests); local planner ADMITTED is not a durable ACK.
Therefore M3 cannot be declared complete even if an M2 network retry succeeds.
No new queue, supervisor or inferred exactly-once guarantee is introduced.

Safe next boundary: deliver/test the signed Tor security candidate independently
(default remains0.4.9.11), then instrument the resumed JVM SOCKS negotiation phase
for one discriminating comparison with the already successful series, using the
same owned fixture. Do not change VPN/DNS/routes/firewall. Stable resumed Storm
processing must pass before full M2; real-onion M3 variant and ADR0002 server work
remain separate additive tasks, preserving current variants and acceptance.

Executed checks: offline Java25 Maven verify/build80 PASS; Python M2 76 PASS,
M1 31 PASS plus one optional skip, M3 2 PASS; CLI/PTY15 PASS. Installed offline
discover exits0. Bash syntax and M1/M2 Compose config pass. Native PowerShell is
not installed: Windows runtime is NOT executed. Fresh local M3b62983d078 PASS:
five frontier records, provenance, cyclic dedup, temporary503 retry, robots,
container/network cleanup and retained volume; m3_verified=false as designed.
Final independent inspection verifies five retained volumes across target/M1/M3,
no remaining containers/networks for those projects, unchanged protected host
routes/rules/resolver hash. No existing volumes or keys were deleted. Entire
doctor hooks/approval records pass. Diff excludes runtime/secret artifacts.

Evidence root: `.runtime/m2/closure-20261102/acceptance-summary.json` and referenced
logs. Target: `.runtime/m2/onionall-brawler-m2-8b64cfc89746/`.

## Final integration review — 2026-11-01

Checkpoint is1180512086bdd7b3f48c37fbe2d7dbcca17bffee. The cumulative uncommitted
patch adds one local batch supervisor and one shared recovery controller (not
multiple competing watchdogs), gateway readiness/series probes, fail-fast harness
stages, stronger acceptance evidence checks, a signed offline Tor candidate
path, terminal/cancellable TorProtocol lifecycle, scheduler/filter regressions,
and operational documentation. No default dependency pin or TorPolicy allowlist
expansion, DIRECT path, auto-redirect/retry or volume deletion was introduced.

Independent second review inspected source and installed dependency contracts.
Corrections in this integration pass:

1. Supervisor matched category words in arbitrary fetched content, not just
   exceptions. It now recognizes anchored IOException records; regression proves
   a normal content line containing all category words does not trigger recovery.
   This remains heuristic diagnostic log parsing, not an authenticated event bus;
   adversarial log-line injection and service-wide failure attribution are not
   solved by this small correction. Do not promote it to a general production
   watchdog without a structured signal/ownership contract.
2. Target used a previously packaged JAR while claiming no JVM changes. It now
   compares packaged Tor classes with nonempty target/classes, records artifact
   SHA256 and rejects a stale JAR. Maven verify precedes runtime verification.
3. Confirmed robots path bypass: installed crawler-commons1.6 allows original
   `/safe/%2e%2e/blocked` and `/safe/../blocked`; OkHttp sends `/blocked`.
   SimpleFetcherBolt checks rules against the original URL. URI.normalize also
   collapses duplicate separators, changing `//blocked` to `/blocked` after that
   check. A residual encoded-dot-only fix was independently reviewed and found
   incomplete, then strengthened. Policy now rejects paths changed by URI
   normalization or containing encoded dot segments, before robots/transport.
   Normal encoded filename characters, query escaping, root slash and fragments
   retain their prior semantics. This is intentionally stricter for ambiguous
   paths, not a replacement robots grammar. Scope/wire regressions prove no socket
   opens for rejected forms. Differential reproduction is retained under ignored
   .runtime/m2/final-integration/PathContract.java.

Available unit checks: final offline Java17 Maven verify38pass and rebuilt JAR;
Python M2 66pass, security verifier4pass, M1 31pass/1optional skip. Fresh JDK17
resolver guard runs32 protocol/policy tests: positive control true, zero onion
InetAddress lookups. Scope is this fresh JVM with loopback SOCKS fixtures,
not system-wide DNS leak absence. An initial filter-test compilation error was
corrected before running successful tests; no failed setup is counted as PASS.

Target5c07d50d5d36 passes: post-service-restart CONNECT times out30s, one same-
container gateway restart, authenticated readiness plus real robots/health
series, paused consumer resumes, pending state is unchanged then consumed, and
separate actual Storm crawl yields ROOT/A/B and eight frontier records. Keys,
three volumes and host VPN routes/rules/DNS are unchanged; cleanup passes.
The target copied the intermediate encoded-dot-rejection artifact; the later
strict literal-dot correction changes only rejected URL forms unused by the
canonical owned fixture. Full dc2e99288d55 uses the final Maven-packaged policy;
its results are required for final acceptance, never inferred from this target.

Full-run infrastructure note: dc2e99288d55 passed real bridge/SOCKS/HTTPS
preflight and Maven verify, then failed BuildKit metadata HEAD for
registry-1.docker.io/debian:12.12-slim with network-unreachable. Stack startup,
scenarios and M1 were not executed; its verifier remains false. A single
justified rerun ba732bdd136c uses new explicit --cached-images: inspect all four
required local image tags/IDs before startup, record cached-images.json and
image-policy.json, skip M2 build/pull and start with --no-build --pull never.
This tests the existing approved artifacts, not remote rebuild reproducibility.
It does not alter host routes/DNS/VPN or silently upgrade pins. M1's existing
runner retains its own image preparation policy. Default M2 build mode remains.
Both failed evidence directories are preserved; results follow.

Cached full-run result: ba732bdd136c reaches the real crawl and graceful frontier
recreation with eight records. After the owned onion restart, original readiness
fails; the controller restarts the same gateway and Python health succeeds.
The first actual Java robots request then times out20s, series completed=[];
stack: REQUEST_TIMEOUT -> InterruptedIOException(timeout) -> SocketException
(Socket closed) in SocksSocketImpl.readSocksReply/connect. This is before HTTP
and before a Storm worker. The stack does not distinguish greeting versus
CONNECT-reply read. This independently excludes Storm topology lifecycle as a
necessary cause for this particular failure; Tor client/path/isolation behavior
remains unproven. No additional circuit/descriptor experiment was launched.
Recovery state=blocked, gateway stopped, no resume; subsequent matrix cases and
outage pipeline not executed. scenario_exit=1, pipeline_exit=1. Mandatory gate
is failed; successful target/canary/unit tests cannot substitute for it. The
independent M1 regression abb26c8d207b passes exit0, all three evidence flags true,
six records and minimum gap2.003326975s. Final verifier m2_verified=false,
fail_closed_verified=false; real_tor_transport/remote_onion_resolution/
no_direct_egress/proxy_bypass_blocked/m1_regression_passed are true. The combined
crawl stage also stays false because the mandatory /loop-a probe was skipped
following failed recovery; raw ROOT/A/B/pending markers and eight identical
before/after records do not override that acceptance result.

Final cleanup and independent inspection confirm target, both full attempts and
M1 have no remaining containers/networks. The target and cached full run retain
frontier-data/tor-state/onion-state; M1 retains its frontier. Protected VPN/default
routes, routing rules and resolver hash match the pre-target snapshot. Evidence:
.runtime/m2/final-integration/host-cleanup-final.json. git diff --check and scans
of all changed tracked/untracked paths for private bridge tokens, private keys
and accidentally included .runtime/.secrets/target files pass. Nothing is staged;
HEAD remains1180512086bdd7b3f48c37fbe2d7dbcca17bffee, no commit/push.

Installed dependency assessment: Java17 target/runtime, Kotlin2.3.21,
StormCrawler3.7.0, Storm2.8.9, URLFrontier2.4, crawler-commons1.6, OkHttp5.4.0.
No version upgraded. Tor0.4.9.11 remains vulnerable baseline;0.4.9.15 candidate
is unbuilt. M2 is **not VERIFIED**, and production-ready is **false** independently
because signed security update and independent runtime recovery remain blocked.
Safe continuation: deliver the authenticated package bundle using the documented
approved transfer procedure, build/test it offline in an isolated candidate,
then repeat bounded owned-fixture recovery with stable endpoint and preserved
keys/state. This is a new version-compatibility hypothesis, not a claim that it
will cure connectivity. Do not alter host networking, increase deadlines or
repeat identical current-version full gates to search for a lucky PASS.

### TODO disposition (bounded evidence, not blanket closure)

| Area / TODO | Classification | Evidence / remaining scope |
| --- | --- | --- |
| Filter configure misuse, bad config, null metadata | VERIFIED | New local tests: fail-fast or target scope still enforced; no network |
| Filter source | DEFERRED | Source is not authorization; target allowlist remains required. Parent-domain restrictions need an explicit product requirement |
| Filter normalize / IOException refusals | VERIFIED | Negative URL/metadata matrix returns null; initialization defects still escape |
| TP-01/03 ambiguous path | FIX_REQUIRED → fixed/VERIFIED cases | Literal/encoded dots and duplicate separators rejected; encoded filename/query cases retained. Not universal URI equivalence proof |
| TP-02 independent published onion vectors | DEFERRED | Existing generator/checksum tests do not replace a separately published vector; no algorithm rewrite justified |
| TP-04 metadata consumers | VERIFIED critical boundary / DEFERRED future consumers | Fixed SOCKS/headers and protocol.md.prefix regressions; conservative key false positives not changed |
| TPR-01 SOCKS/DNS | VERIFIED local / TEST_REQUIRED real final gate | Actual DOMAIN SOCKS and fresh JDK guard; full namespace result below |
| TPR-02 cache TTL/concurrency | DEFERRED | Current single-host acceptance; cross-host load/TTL eviction performance contract is not established |
| TPR-03/11 configure/cleanup | VERIFIED local / TEST_REQUIRED live ownership | Terminal/idempotent cleanup, blocked call/robots cancellation and new instance tests; active Storm pause still unproved |
| TPR-04 unsafe config | VERIFIED tested settings | Redirect/trust/robots bypass settings rejected; no universal guarantee for arbitrary future components |
| TPR-05 gateway DNS | VERIFIED current scope | Proxy resolved once; same-container restart. External replacement needs fresh JVM |
| TPR-06 Location | VERIFIED local | Exactly one canonical target, relative resolution, duplicate-order rejection,304 preserved |
| TPR-07 taxonomy | VERIFIED mappings / DEFERRED richer persisted taxonomy | Cancellation is PROTOCOL_CLOSED; robots cause retained; stock fetcher metadata loses category details |
| TPR-08 metadata prefix | VERIFIED | Real setting protocol.md.prefix and control-key collision tests |
| TPR-08 header size | DEFERRED | No proven new defect; explicit header/load resource contract still needed before broad production claims |
| TPR-09/10 robots errors | VERIFIED tested cases / DEFERRED encoding policy | EOF never caches ALLOW_ALL; empty/wildcard/unmatched/HTML/status/size covered; decoding remains replacement-based |
| TPR-12 connection reuse | VERIFIED zero idle streams / DEFERRED circuit isolation | HTTP1.0 regressions; fresh TCP is not fresh Tor circuit |
| TPR-13 timeout tuning | DEFERRED | Existing bounded deadlines preserved; no data justifies raising them |
| TPR-14 followups | VERIFIED redirects / DEFERRED TLS/auth/HTTP2 breadth | No automatic redirects/retry; not every optional protocol variant exercised |
| TPR-15 UA parser | VERIFIED tested semantics | Exact agent, wildcard, unmatched groups retained |

Remaining production boundary is a blocker: supervisor/controller still require
M2 fixture, network_probe.py and a probe-equipped JAR; there is no independent
production deployment integration. Host operator owns local tooling; no Docker
socket is exposed to crawler. One journal cycle prevents repeated recovery, but
is not a crash-transactional/resumable controller or shared interprocess launch
lock. The minimal future design is reuse this one owner with a shared launch/
recovery lock and a deployment-owned structured signal/canary contract. Do not
add another supervisor or force-reset arbitrary Tor circuits.

Security blocker remains separate: actual baseline Tor0.4.9.11, candidate0.4.9.15
not delivered/built/installed, signed-bundle real verification and candidate
bootstrap not executed. Do not redownload through another route or promote a
hash-only/unsigned package. The prior manual signed-bundle procedure remains.

Latest Storm/frontier reliability review: see [m2-reliability.md](m2-reliability.md)
for installed scheduler/ACK contracts, cancellation taxonomy fix, local tests and
limits of source isolation/crash durability. No full gate was run.

## Gateway/runtime lifecycle review — 2026-11-01

No Tor/container/network experiment or full M2 gate in this pass. Existing
74a877a0af27 remains the latest failed recovery prerequisite; m2_verified=false.

Runtime boundary: TorPolicy/TorProtocol/TorScopeFilter are application transport
components used by the actual Storm fetcher. OkHttp has an explicit SOCKS proxy,
no automatic retries/redirects and no idle pooled connections. Proxy address is
resolved once at configure. Restart of the same gateway container preserves that
address; force-recreation requires a new protocol/JVM. This is process lifecycle
compatibility, not proof that Tor circuits/streams recover after restart.

The host-side recovery controller detects from test/m2/network_probe.py and
requires the owned hostname, probe Compose service and RecoverySeriesProbe in
crawler-m2.jar. Its consumers are paused, gateway restarted once, HTTP and real
protocol series verified before resume. Its exclusive journal refuses another
cycle, including after failure; it is a one-shot budget/lock, not a resumable or
crash-transactional state machine. Supervisor owns at most two bounded local
crawler batches and invokes that same controller; exit0/log classification is
not content success. Neither script is wired into an independent production
service; both remain local M2 tooling dependent on test artifacts. They do not
modify frontier URLs, retries, RocksDB or onion keys. Existing persisted retry
semantics remain authoritative.

Confirmed fixes:

- OkHttp synchronous execute is not prohibited by shutting down its dispatcher
  executor. TorProtocol cleanup previously allowed new network calls afterwards.
  Added terminal state, serialized configure/cleanup and rejection of new fetch
  and robots calls with IOException(PROTOCOL_CLOSED). Reconfigure after cleanup
  is rejected; restart creates a new instance.
- Dispatcher cancelAll alone has an admission race between newCall and execute.
  Calls are now tracked under the lifecycle lock before execute; cleanup cancels
  those calls directly too. Network I/O is outside the lock. Finally removes
  completed calls. Robots cache locking remains separate; cleanup cancels first,
  then clears cache, avoiding waiting on a blocked robots fetch before cancellation.
- Malformed JSON array/null health/series used to escape via AttributeError,
  bypassing the recovery exception/stop path. Shape validation now turns these
  cases into a recorded blocked result with gateway stop and no unpause.
  Malformed supervisor quarantine state is likewise rejected.
- First SIGTERM now disables another SIGTERM before raising KeyboardInterrupt,
  allowing bounded cleanup rather than interrupting it with repeated termination.
  This remains cooperative shutdown: SIGKILL/host crash cannot run cleanup.

Checks executed: offline JDK17 TorProtocolTest24pass (three new tests), Python
M2 65pass (malformed evidence and interrupted supervisor regressions included),
git diff --check. JVM tests use local SOCKS sockets only: terminal/idempotent
cleanup, cleanup-before-configure, fresh-instance reuse, and blocked SOCKS call
cancellation within2s versus configured120s timeout. No real Tor or DNS leak
claim follows. The admission-window fix is established by lock/Call ownership;
the test exercises cancellation after a socket is accepted, not every possible
thread schedule. Raw targeted Maven output: .runtime/m2/lifecycle-targeted.log.

Remaining design limits: no shared interprocess launch lock between manual
controller and supervisor; an operator must retain one owner and prohibit
concurrent launchers. Pause cannot reset JVM robots state or a pinned endpoint;
recreate cannot safely resume an old JVM. Do not add another supervisor. Before
independent production deployment, minimally separate the verified canary/probe
contract from fixture-specific M2 packaging and make the existing owner serialize
launch/recovery using one shared lock. Tradeoff: restart a consumer to discard
IP/cache state versus pause to retain in-memory work; persisted frontier must
remain authoritative in either case. This design is recorded, not implemented as
a new orchestration layer. Persisted-journal crash recovery and live Storm
shutdown ownership remain open TPR-03/11 integration questions. No broad TODO
closure, commit or push. Production recovery remains BLOCKED.

## Tor security update only: signed offline bundle — 2026-11-01

Latest security-only pass: installed image reports **Tor0.4.9.11** via an actual
network-disabled `tor --version`. Candidate0.4.9.15 is **NOT built/installed**.
One ordinary HTTPS request for Bookworm InRelease failed with curl28,
`Resolving timed out after5000 milliseconds`. No further download attempts,
resolver/proxy/VPN changes or alternate network path were used. Browser-accessible
metadata is reference evidence, not a substitute package delivery channel.

Official [Bookworm amd64 index](https://deb.torproject.org/torproject.org/dists/bookworm/main/binary-amd64/Packages)
confirms version0.4.9.15-1~d12.bookworm+1 and SHA256
`eca04d5ab2c6285601526419b3d9ca1a25bec477abb25360f4c97da92d79c88e`.
The [official repository instructions](https://support.torproject.org/little-t-tor/getting-started/installing/)
publish repository key fingerprint `A3C4F0F979CAA22CDBA8F512EE8CBC9E886DDD89`.
Previous checksum-only remote ADD did not authenticate the APT metadata chain.
It is superseded by `scripts/verify_tor_candidate.py` and a local COPY candidate
Dockerfile. Production Compose/default version remains unchanged.

### Manual transfer, without changing this host's network policy

On a workstation **already authorized** to reach the official repository, use
ordinary verified HTTPS. Do not bypass a restriction on this host or workstation.
If that access is unavailable, stop and retain the blocker. Download these four
files (commands require Bash/curl and do not install anything):

```sh
set -Eeuo pipefail
mkdir -p tor-candidate-bundle
cd tor-candidate-bundle
curl --fail --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 5 --max-time 60 -o tor-signing-key.asc https://deb.torproject.org/torproject.org/A3C4F0F979CAA22CDBA8F512EE8CBC9E886DDD89.asc
curl --fail --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 5 --max-time 60 -o InRelease https://deb.torproject.org/torproject.org/dists/bookworm/InRelease
curl --fail --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 5 --max-time 60 -o Packages https://deb.torproject.org/torproject.org/dists/bookworm/main/binary-amd64/Packages
curl --fail --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 5 --max-time 60 -o 'tor_0.4.9.15-1~d12.bookworm+1_amd64.deb' 'https://deb.torproject.org/torproject.org/pool/main/t/tor/tor_0.4.9.15-1~d12.bookworm+1_amd64.deb'
```

Transfer exactly these four files via an approved file-transfer channel into
`.runtime/m2/tor-security/incoming/` on this host. Do not transfer private keys,
bridges or Tor state. From the repository root:

```sh
python3 -B scripts/verify_tor_candidate.py .runtime/m2/tor-security/incoming .runtime/m2/tor-security/verified-build
docker build --network=none --pull=false -t onionall-brawler-tor:0.4.9.15-compat .runtime/m2/tor-security/verified-build
docker run --rm --network none --cap-drop ALL --security-opt no-new-privileges --read-only --entrypoint /usr/bin/tor onionall-brawler-tor:0.4.9.15-compat --version
```

Stop on **any** failure. The verifier accepts only a new ignored destination,
an isolated temporary GPG home and the expected primary fingerprint. It requires
a valid signed InRelease for bookworm, Date within7days (and honors Valid-Until),
the exact signed hash/size of Packages, and the pinned package/version/path/size/
SHA256. No keyserver or network access is used by the verifier. A stale index or
retired package requires a fresh approved bundle/review, not disabling checks.
Debian .deb authentication here is through signed APT metadata, not a claimed
embedded per-package signature. Signature verification has **not** executed
against a real upstream bundle in this pass because the bundle is unavailable.

Build inputs now pin the baseline image's observed RepoDigest
`sha256:88f3dce6e6b581317bce2ee3c5614297a0757b9d374b874c34f1bfdd97bba11e`
and package content. dpkg resolves no network dependencies; missing dependencies
fail the offline build. This is locked input/provenance reproduction, not a claim
of bit-identical Docker output or a fresh full Debian security baseline. The
baseline digest must already be available locally (confirmed on this host).

After successful build/version check, a separate temporary candidate-only
preflight can use the unchanged existing helper with a process-local image
selection; it does not promote the production pin:

```sh
PYTHONPATH=test/m2 python3 -B -c 'import json,sys; from pathlib import Path; import tor_preflight as p; p.IMAGE="onionall-brawler-tor:0.4.9.15-compat"; r=p.run(Path(".runtime/m2/tor-security/candidate-preflight"), seconds=90); print(json.dumps(r,indent=2)); sys.exit(0 if r.get("ready") is True else 1)'
```

Requires the existing private bridge file; never paste it into the bundle or
build context. This preflight has its existing90s bound, authenticated bootstrap
observation and SOCKS/HTTPS test, temporary state and cleanup; no persistent
onion keys/volumes are mounted. It was **not run** without the candidate image.
Full M2 compatibility/acceptance would still be a subsequent authorized task.

Executed checks: four offline negative verifier tests (unexpected signing key,
invalid signature, corrupt index, unsafe destination), script help and git diff
--check. Mocks validate rejection paths, not upstream signature authenticity.
No Maven, Kotlin, Storm/frontier/recovery edits or integration gates in this pass.
No commit/push. DNS timeout is the exact current blocker.

## Latest bounded hardening iteration — 2026-11-01

**M2 remains BLOCKED.** No commit/push or host networking change.

Confirmed orchestration/lifecycle defect: `TorProtocol.configure()` creates a
resolved InetSocketAddress once; an existing JVM does not rediscover a changed
Docker gateway IP after force-recreation. Pause/unpause cannot repair that.
Recovery now restarts the existing Tor container and uses --no-recreate while
waiting for bootstrap. It checks container identity before/after, retains the
same endpoint and all disk state. This fixes a real recovery compatibility risk,
but does **not** explain the previous failure of a newly launched Storm process.

Resume additionally requires `RecoverySeriesProbe`: three sequential real
TorProtocol robots/health pairs with the same protocol instance and a subsequent
getRobotRules parse. Each call keeps the existing20s bound, no automatic retry;
parser/body/status checks must pass. Four successful logged robots GETs are
required by the verifier; missing/extra/malformed traffic fails. Recovery-agent
traffic is checked separately from the unchanged main-crawl counts and security
scenario requests. Raw failed detection is never rewritten as success.

`scripts/m2_supervise.py` is an opt-in actual local Compose process owner, not
just a test result generator: one bounded crawler batch; detect persisted log
categories REQUEST_TIMEOUT/SOCKS_FAILURE/ONION_UNREACHABLE/ROBOTS_UNAVAILABLE;
one recovery and one new JVM at most. It refuses existing quarantine, never
reseeds or rewrites frontier scheduling, and stops its named consumer on
SIGTERM/timeout/failure. Unit tests prove no second batch after failed recovery
and no restart loop after recurring failure. A normal process completion does
not prove content fetched. Prerequisites are an already prepared isolated M2
project, evidence JAR containing the probes, and the owned canary fixture:

```sh
python3 -B scripts/m2_supervise.py onionall-brawler-m2-<12-hex-id> .runtime/m2/onionall-brawler-m2-<12-hex-id>
```

This is **not yet independently deployable general production runtime**: it
still depends on M2 fixture/probes and its live recovery integration has not
passed. No Docker socket is mounted into crawler; controller authority remains
on the operator host and is restricted in code to the exact named M2 project.
Do not enable another concurrent crawler launcher. Distributed Storm recovery
and continuous monitoring remain outside this bounded local mechanism.

Bounded experiment results:

- 22364bb42dda and e3e2790e81d7 stopped before restart at new-probe compilation
  errors (Storm provided classpath/API). These are failed setup runs, not Tor
  recovery evidence. Corrected probe then compiled locally with JDK17.
- 74a877a0af27: baseline HTTP20018.375s; stop-own-onion CONNECT timeout10.010s;
  same hostname/key hashes after return; post-return CONNECT timeout30.030s.
  One IP-preserving gateway restart; live greeting passes but CONNECT again
  times out30.030s. This is a fresh Python socket, before Storm lifecycle or
  the new Java series. Controller stays blocked, does not unpause, closes the
  gateway (exit0). Series and post-recovery crawler fetches were **not executed**.
- This independently bounds the failure before HTTP and before a new Storm JVM.
  Tor hidden-service/network state remains unresolved; the experiment does not
  distinguish exact Tor internals from path availability with the active VPN.
  No blind timeout increase, extra recovery cycle or full gate retry followed.
- Target cleanup originally left the paused one-off consumer and its network:
  Compose down did not remove it. Exact observed helper was explicitly removed;
  independent-cleanup.json confirms containers/networks gone and all3 volumes
  retained. Target cleanup now force-removes only its named test helper before
  down. Original failed cleanup/result JSON remains preserved.

Security candidate: a fresh build of the **tracked** Dockerfile.security-candidate
again fails DNS for deb.torproject.org before download. Package pin/SHA256 remain
unchanged from the official source; no candidate installed. Tor before/after
remains0.4.9.11. This is an independent security blocker, not proof of the cause
of the connectivity failures. No resolver/proxy/VPN/firewall/daemon workaround.

Verification: offline JDK17 Maven verify26pass; Python M2 63pass; Python M1
31pass/1optional skip. First Maven attempt could not write Kotlin's per-user
marker in the restricted session; the authorized normal-user execution passed
with the existing cache and offline resolution. No JVM production changes.
Independent Docker M1 regression aada9657f24a passed exit0: assertions,
crawl evidence and runtime flags true; six records, minimum gap2.000966576s,
container/network cleanup passed and its volume retained. Final read-only
host check confirms protected VPN/default routes, policy rules and resolver
hash unchanged; all three new M2 projects have no containers/networks left.
Evidence: .runtime/m2/hardening-iteration-host-check.json and ignored M1 directory.
Final git diff --check and private-bridge/key/runtime-file scans pass. Changes
remain uncommitted; HEAD is still1180512086bdd7b3f48c37fbe2d7dbcca17bffee.

Remaining work is gated, not silently closed: acquire/build and assess the
trusted fixed Tor package under the existing network policy, run the strengthened
target, exercise supervisor live with several successful actual crawler fetches,
then full M2/outage/M1. No remaining Kotlin TODO is closed while the mandatory
recovery prerequisite is red. No claim of production readiness or universal DNS
leak absence follows from unit tests or a single successful stream.

## Managed recovery and security candidate — 2026-11-01

The Tor Project Bookworm amd64 index was rechecked: the actual candidate is
`0.4.9.15-1~d12.bookworm+1`, not an invented Debian-security version. Its package
URL and SHA256 are pinned in `docker/m2/Dockerfile.security-candidate`. This
isolated candidate inherits the existing Debian12/obfs4 image and replaces only
Tor. `dpkg` checks dependencies, architecture and installed version. The package
is reproducible by hash; the inherited base tag and its other packages are not
a fully locked security baseline. Primary sources: [Tor package index](https://deb.torproject.org/torproject.org/dists/bookworm/main/binary-amd64/Packages),
[Debian tracker](https://security-tracker.debian.org/tracker/source-package/tor).
The latter still lists open vulnerabilities in the installed Bookworm0.4.9.11.

One renewed isolated build attempt using the preceding ignored candidate
Dockerfile with the identical package URL/checksum failed before download with
`lookup deb.torproject.org: no such host`. No candidate image was built or
selected by Compose. No DNS/VPN/route/proxy/daemon workaround was attempted.
Reproduce later, with the same host protections intact:

```sh
docker build --pull=false -t onionall-brawler-tor:0.4.9.15-compat -f docker/m2/Dockerfile.security-candidate .
```

Candidate compatibility requires at least installed-version/config checks,
bridge bootstrap, controlled onion/recovery and full M2/M1; none is claimed for
0.4.9.15 yet. Actual runtime remains0.4.9.11. Security remediation is BLOCKED.

### Single-cycle recovery contract

`scripts/m2_recovery.py` is a local Compose operator/controller, not a production
watchdog or a fix to Tor's internal cache. It accepts only an isolated M2 project
and its exact local evidence directory. Run it only while the owning harness
or operator has exclusive control of crawler launches for that project:

```sh
python3 -B scripts/m2_recovery.py onionall-brawler-m2-<12-hex-id> .runtime/m2/onionall-brawler-m2-<12-hex-id>
```

1. Detect a failed own-fixture SOCKS CONNECT after successful greeting. A fresh
   standalone check makes one30s attempt; scenarios retain their original90s
   failure document. HTTP/robots/policy errors do not trigger replacement.
2. Pause all running crawler containers selected by this exact Compose project's
   labels, including one-off consumers. Frontier remains running and unchanged.
   The journal is created exclusively; another recovery in this run is refused.
3. Stop the configured Tor gateway and recreate it once, retaining tor-state,
   the same service endpoint/networks/config/private bridges and all onion keys.
   No image pull/build, arbitrary proxy, controller HSFETCH or circuit reset.
   Bootstrap is bounded90s; it only gates the next real-health probe.
4. Make one30s `/health` request to the owned fixture via fixed `tor:9050`,
   requiring ATYP3, HTTP200 and body `ready`. Only then unpause the same consumers.
5. After quarantine, on any failure leave consumers paused and stop the gateway. Record failures
   closing it as well; never claim successful quarantine if cleanup is unknown.
   Manual diagnosis is required; no restart loop or timeout escalation.

This confines recovery to gateway process state; disk state is never deleted.
The controller cannot distinguish a stale gateway from a service/network outage
using a single canary. Its bound means at most one attempted replacement, not a
guaranteed Tor recovery time. It does not authorize bypassing host VPN protection.
Pausing is scoped to the current local-cluster M2 deployment; multi-host Storm
heartbeats and distributed recovery are outside this mechanism's tested contract.
External/manual concurrent crawler launches are not covered by its local lock.

Scenarios invoke the controller only after failed post-restart controlled health.
The failed detection evidence stays failed; recovered availability is recorded
separately. The original unreachable negative probe and all failure-matrix
assertions remain mandatory. The verifier separately checks recovery state
transitions and actual health, rather than trusting an aggregate passed flag.
The full harness skips outage crawling after failed scenarios; the outage driver
also refuses an existing blocked quarantine. No reseed/retry-date rewrite.

### Targeted result

`JAVA_HOME=/usr/lib/jvm/java-17-openjdk python3 -B test/m2/restart_recovery.py`
passed in `.runtime/m2/onionall-brawler-m2-ad6b9cf93c0d/`:

- Baseline health2006.120s; service-down CONNECT timed out10.011s.
- After service restart, same hostname/key hashes, greeting succeeded but
  CONNECT timed out30.029s. This is retained as failed detection.
- One gateway replacement: quarantine08:02:01.547Z, verified health2005.365s,
  resume08:02:13.345Z. No increase in probe timeouts or retry loop.
- One idle local consumer paused/resumed; this demonstrates the mechanism,
  not active Storm in-flight pause behavior. Subsequently the real Storm crawler
  processed the retained pending URL without reseeding; frontier snapshots
  before/after replacement were identical and the successful refetch was stored.
- All three volumes retained; containers/networks removed. VPN/default routes,
  routing rules and resolver hash unchanged. No host network setting changed.

The target's `m2_verified=false` is deliberate: it is not the complete acceptance
gate. Python57 tests pass, including failed health/replacement/pause handling,
single-cycle refusal, no policy-error-triggered replacement, quarantine bypass
denial and negative verification of incomplete recovery evidence.
Full M2/M1 gate launched only after this targeted pass. In
`.runtime/m2/onionall-brawler-m2-21def5f8ab61/`, all13 security scenarios passed,
including the new managed recovery. The original post-restart readiness remains
false, replacement health passes8.098s; no negative probe was overwritten.
Eight frontier records survive recreation identically and pending work resumes.
Offline Maven verify completes26 tests with JDK17; Python M1:31pass/1optional skip.

The subsequent actual Storm outage pipeline fails: robots fetch returns
ROBOTS_UNAVAILABLE caused by REQUEST_TIMEOUT. The JDK cause stack is in
SocksSocketImpl.readSocksReply/connect (socket closed at deadline), before HTTP;
there are no outage-user-agent fixture requests and /outage never starts.
The stack alone does not distinguish greeting from CONNECT reply in this
particular request. The earlier health and Java recovery probes passed, so
bootstrap and one successful stream are insufficient to guarantee the next
Storm stream's availability. Different isolation/circuit state and transient
network availability remain hypotheses, not a proven new Kotlin defect.
scenario_exit_code=0/pipeline_exit_code=1; full gate stays failed. Do not increase
timeouts, retry the whole gate identically, bypass VPN or call this PASS.
Final verification reports `m2_verified=false`, `fail_closed_verified=false`;
controlled_onion_fetch, robots_verified, crawl_delay_verified,
frontier_persistence_verified, real_tor_transport, remote_onion_resolution,
no_direct_egress and proxy_bypass_blocked are true. Minimum main-crawl gap is
3.504578008s with eight frontier records. The single outage exit failure remains
an acceptance blocker despite these successful independent stages.

M1 regression `.runtime/m1/onionall-brawler-m1-09a6ef35c065/` passes exit0,
assertions/crawl/runtime flags all true, six frontier records, minimum document
gap2.001555577s. Cleanup retains frontier-data/tor-state/onion-state and removes
all M2 containers/networks; independent inspection also confirms target and M1
projects removed. The protected VPN/default routes, policy rules and resolver
hash match. For the full run, host-before.json reuses the immediately preceding
target cleanup observation (provenance recorded in host-before-source.json);
host-after.json is a fresh post-full-run read-only measurement. This is a
snapshot comparison, not a continuous packet/VPN monitor.

Execution: JDK17, MAVEN_ARGS=-o, M2_MAVEN_REPOSITORY set to the existing writable
`.runtime/maven-linux-repository`, `bash scripts/m2.sh`. Maven verify26pass;
Python M2:57pass; Python M1:31pass/1optional skip. git diff --check passes.
No host/VPN settings, Kotlin source, dependency defaults, volumes or keys changed.
No commit/push after checkpoint1180512. No identical full retry was launched.
TODO work remains deferred per the requested gate order: the P1 new-stream
reliability problem and P0 security-upgrade/DNS blocker remain open. Existing
TPR-06/08/10 hardening is retained; no unrelated P2 is declared fixed.
No production Kotlin/TorPolicy/ScopeFilter changes or speculative TODO closures.

## Checkpoint and discriminating recovery experiments — 2026-11-01

Explicitly requested checkpoint1180512086bdd7b3f48c37fbe2d7dbcca17bffee
was created on main and pushed normally to the configured Entire origin.
Entire status/doctor confirmed hooks and approvals. Twelve staged source/test/
documentation files were reviewed; runtime/private-key/bridge-token/credential
checks found no accidental material. The checkpoint worktree was clean.
No gate ran during checkpoint; subsequent recovery changes are not committed.

The next experiment kept one persistent crawler-side client namespace and
compared warm and never-used cold Tor gateways concurrently. Both used the
same isolated networks, pinned image and bridge configuration. No host settings
changed, no host ports were exposed and cold Tor state used private tmpfs.

| Evidence suffix under .runtime/m2/onionall-brawler-m2-* | Discriminating result |
| --- | --- |
| 7bbcf3d1085b | Warm CONNECT times out30s while cold health succeeds5.660s. Baseline rendezvous12 was already destroyed; new16 waits for rendezvous. Closing16 creates20 but another30s CONNECT fails. Old joined-circuit reuse alone does not explain the failure. |
| 6e177d8ebf1f | Both fail30s after first publication ACK. Cold fetched the same revision as the warm baseline; RECEIVED is not proof of a newer revision. Raw public descriptor contents were not saved. |
| a842915a910f | Service-side whitelisted INFO proves revision counters increase across restart. Cold still obtained the previous published revision. Saved consensus validity covers the test time, so stale-consensus/clock speculation is unsupported. |
| d3e5527174b0 | After ACKs for all current/next uploads, cold fetches new revision2782947095 and passes6.846s; warm retains2781841741 and times out30s. A single HSFETCH replaces warm's revision with2782947095, but warm still times out30s while cold passes1.639s. Descriptor refresh alone is insufficient. |
| 235cd59d81a7 | Planned combined descriptor/circuit reset experiment aborted before baseline: warm gateway and service were healthy, but the extra cold observer did not become healthy within120s. The reset was NOT executed, so it is not evidence of successful assisted recovery. |

This materially narrows the failure to Tor hidden-service descriptor/
introduction/rendezvous state. Warm/cold path differences remain a confound;
the exact internal Tor defect and a guaranteed unassisted recovery bound have
not been proven. A different descriptor hash alone does not establish freshness:
the revision counter must be evaluated in its blinded-key/time-period context.
Tor's [revision-counter specification](https://spec.torproject.org/rend-spec/revision-counter-mgt.html)
requires monotonic revisions for each blinded public key. Current0.5.0-alpha
source documentation was consulted only for background; it is not proof about
the pinned0.4.9.11 implementation. Attempts to access the exact upstream tag
through the browser were unavailable; no version-specific code claim is made.

Production dependency blocker: Debian's
[Tor security tracker](https://security-tracker.debian.org/tracker/source-package/tor)
lists the installed bookworm0.4.9.11-0+deb12u1 with open vulnerabilities.
The [official Bookworm package index](https://deb.torproject.org/torproject.org/dists/bookworm/main/binary-amd64/Packages)
offers0.4.9.15-1~d12.bookworm+1 with compatible Debian12 dependency families.
A candidate image retained the existing Debian12/obfs4 base and pinned that .deb
by the index SHA256, without changing repository production pins. BuildKit
failed resolving deb.torproject.org before downloading or installing it.
Dockerfile retained under .runtime/m2/tor-0.4.9.15-compat/. Runtime compatibility
and security-upgrade acceptance are unverified. Do not bypass the VPN/DNS policy
or claim that an unbuilt candidate remediates anything.

Safe manual reproduction with the VPN unchanged, from the repository root:

```sh
docker build --pull=false -t onionall-brawler-tor:0.4.9.15-compat -f .runtime/m2/tor-0.4.9.15-compat/Dockerfile .runtime/m2/tor-0.4.9.15-compat
```

This builds only a candidate image; it does not switch production pins or change
host DNS/network configuration. If DNS resolution still fails, retain the blocker
instead of changing resolvers, routes, VPN, proxy settings or networking modes.

Repository changes after checkpoint are limited to selecting another isolated
SOCKS gateway in the diagnostic remote_fetch helper (acceptance default remains
tor), its DOMAIN-request regression and documentation. Python47 tests pass;
no new Maven build is needed for unchanged JVM source. No new full M2/M1 run
is counted until targeted autonomous recovery is proven. Production readiness
remains blocked, independently of any assisted diagnostic result.

Remaining TODO triage (no speculative behavior changes or cosmetic rewrites):

- P0: pinned Tor security baseline; compatibility build blocked by DNS above.
- P1: autonomous recovery; TPR-01/04/12/14 integration/isolation/follow-up scope
  remains open because the mandatory failure gate is red. TCP freshness is not
  circuit isolation; no per-request Tor-circuit guarantee is claimed.
- P2: TPR-02/03/05/07/11 cache/lifecycle/gateway-resolution/error contracts;
  remaining TPR-08 header limits and TPR-10 malformed-input policy require
  focused contract tests or an explicit policy decision, not inferred fixes.
- P2: TP-01/03 URI equivalence, TP-02 independent v3 vectors, TP-04 metadata
  consumers and remaining TorScopeFilter lifecycle/null/source questions stay
  open. Existing negative tests are evidence for their cases, not universal
  closure. TPR-06 and the completed portions of TPR-08/10 remain fixed from the
  earlier hardening; no additional production TODO was declared closed here.

## Restart diagnosis with active host VPN — 2026-11-01

Read-only preflight confirmed tun0 UP, policy routing through table2022 and an
active VPN default route; host SOCKS ports9050/9150/19050 refused connections.
Existing Docker networks were inventoried before any test container was created.
No host/VPN route, DNS, firewall, kill switch, global proxy setting or daemon was
changed. All live actions were restricted to unique M2 Compose projects, with
existing bridge configuration, no host networking and no privileged containers.
Each project saved host-before.json/host-after.json: VPN/default routes, policy
rules and resolver-file SHA256 matched. All test containers/networks were removed;
Tor/onion volumes and key material were retained. Existing volumes were untouched.

Previous failed run fa44d93cba6b, approximate file-write timeline in Istanbul
(UTC+03; mtimes are not exact event timestamps):

| Time | Observation |
| --- | --- |
| 01:23:46.593 | Own onion stopped; gateway SOCKS greeting succeeds |
| 01:23:57.516 | Offline protocol probe ends REQUEST_TIMEOUT after10.132s |
| 01:24:04.130 | Service bootstrap healthcheck succeeds |
| 01:25:34.667 | Actual health fails after90s, two bounded timeouts |
| 01:26:53.275 | Active probe already timed out, handler marker absent |
| 01:27:01.477 | Gateway restart completes |
| 01:27:08.280 | Same onion health succeeds in6.326s |

The old evidence could not locate the timeout: it discarded the failing phase.
New network_probe telemetry separates proxy_tcp_connect, socks_greeting,
socks_connect_reply and http_response and records SOCKS reply codes/phase times.
onion_ready records UTC start times without changing existing timeout/retry bounds.

Focused diagnostic directories under .runtime/m2/:

- onionall-brawler-m2-d327e55b84bc: baseline200, offline CONNECT timeout10s;
  after restart CONNECT returns reply1 after26.537s, before HTTP was sent.
  The service publishes descriptors while this connection waits. An initial
  diagnostic HSFETCH used the wrong address form and returned513; it proves
  nothing about descriptor refresh. Original results/source are preserved.
- onionall-brawler-m2-77b26fe4c188: baseline200; after restart a fresh SOCKS
  socket reuses gateway circuit16 in HS_CLIENT_REND/HSCR_JOINED. Stream75 remains
  SENTCONNECT, then circuit16 is DESTROYED at01:52:37.294; stream fails and SOCKS
  reply1 arrives after17.434s. Correct HSFETCH (bare56-character identity) is
  accepted351ms later; HS_DESC RECEIVED follows, a different circuit19 is given
  rendezvous purpose and health succeeds in6.283s. Circuit19 previously existed
  as HS_VANGUARDS; it was not built from scratch. Destruction/time/descriptor
  fetch changed together: HSFETCH alone is not a proven fix.
- onionall-brawler-m2-f05fbc6abf98: old rendezvous was already destroyed before
  recovery; descriptor receipt after HSFETCH still led to CONNECT timeout10s.
  The new service had descriptor CREATED and one established introduction
  circuit, but no UPLOADED acknowledgement was observed before cleanup.
  No rendezvous circuit was forcibly closed. This does not support a universal
  "clear the cached circuit" or "fetch the descriptor" correction.
- onionall-brawler-m2-6c4c71b345ee: passive test aborted before restart because
  its initial health CONNECT returned reply4 after6.293s despite bootstrap100.
  It is not evidence of a passing or failing restart transition.

In every completed restart experiment, hostname and SHA256 of both
hs_ed25519_* files matched before/after. Keys were hashed inside the service;
private bytes were never output. TCP SOCKS connections were new each time;
Kotlin/OkHttp connection pooling is not involved in the Python reproducer.
Control snapshots hs/client/desc returned552 even during successful baseline:
their descriptor_present=false field must NOT be interpreted as an absent
descriptor. Descriptor byte-level freshness is unconfirmed. Sanitized HS_DESC,
STREAM, CIRC and CIRC_MINOR JSONL events retain status/purpose/reason/IDs, excluding
relay paths, bridge addresses, cookies and descriptor contents.

Supported diagnosis: the failed readiness is inside Tor's hidden-service CONNECT
path, before HTTP. A stale joined rendezvous and service introduction/descriptor
publication timing are observed mechanisms; one exclusive root cause and VPN
causality are not established. Bootstrap readiness does not prove publication,
client descriptor freshness, introduction/rendezvous success or HTTP availability.
No evidence-backed torrc change is proposed; timeouts were not increased and
no descriptor refresh, circuit reset or extra retry was added to acceptance.

Confirmed harness defects fixed:

- scenario-tor-return.log no longer overwrites the runner's tor-return.log;
- scenario commands record UTC start/end/exit in scenario-timeline.jsonl;
- failed own-service recovery immediately blocks dependent network scenarios,
  rather than producing a cascade of misleading redirect/reset/timeout failures;
- phase/UTC telemetry and negative unit coverage distinguish CONNECT from HTTP.

Verification:46 Python M2 tests pass, including both timeout phases and fail-fast
on failed recovery; git diff --check passes. JVM source was unchanged, so Maven
was not rebuilt/rerun; the earlier26 Maven results are historical. Targeted
recovery remains unverified/red and no new full M2 gate was launched after these
diagnostic fixes. Last complete gate remains fa44d93cba6b with m2_verified=false.
The user permits isolated M1 regression for a future full run, with the same
host/VPN protections. No commit/push, volume removal or network bypass occurred.

## Tor-only runtime verification — 2026-11-01

Host SOCKS ports19050/9050/9150 refused connections. The host reference torrc
still selects loopback19050 and bridges; host firewall/DNS/daemon configuration
was not changed. Docker29.9.2 was available with authorized elevated access.
Both checks used only the Compose-owned controlled onion fixture. The existing
full-run preflight additionally used its fixed HTTPS Tor Project endpoint.

Bounded diagnostic evidence:
`.runtime/m2/onionall-brawler-m2-cf110581a7e7/`.

- `tor-only-result.json`: bounded_tor_only_passed=true, cleanup_passed=true;
  m2_verified=false because this diagnostic does not run the full acceptance gate.
- `network.json`: actual crawler service attached only to its internal network;
  direct internet, fixture and public DNS TCP/UDP were inaccessible. Local onion
  resolution failed; actual SOCKS ATYP3 /health returned200/ready in4.549s.
- `real-dns-guard.json`: real TorProtocol /health succeeded while a fresh JDK17
  process intercepted InetAddress name-service calls. Positive control was
  detected; application target-onion lookup attempts were zero. This is not
  packet-level observation or proof for the entire system or every runtime path.
- Proxy outage: actual network-outage.json and scenario-tor_stopped.json pass;
  no response is substituted for failed transport. All three persistent volumes
  were retained and containers/networks were removed.

`test/m2/TorDnsGuardProbe.java` is test-process-only and requires JDK17 plus
`--add-opens java.base/java.net=ALL-UNNAMED`. Default mode runs the local protocol
and policy JUnit suites. Optional `controlled-health` mode runs only the existing
TorScenarioProbe /health against the hostname in the mounted evidence directory.
Neither mode changes production DNS settings. The new initial-out-of-scope test
asserts SCOPE_REJECTED with no SOCKS or direct-target connection.

Fresh full-run evidence:
`.runtime/m2/onionall-brawler-m2-fa44d93cba6b/`.
The host ran the existing Bash runner with JAVA_HOME selecting OpenJDK17,
M2_MAVEN_REPOSITORY selecting the writable local cache and
`MAVEN_ARGS='-o -Dkotlin.compiler.daemon=false'`; an outer1200s limit bounded
the attempt. Offline Maven package/verify passed26 tests. Python43 tests pass.
The separate resolver-guard rerun also passed all26 tests with zero onion lookup
attempts (`dns-guard-local.json`). No production dependency versions changed.

Actual controlled HTML/robots/404/redirect/discovery completed. Eight frontier
records were identical across graceful container recreation; pending work was
then processed. The Storm outage pipeline passed: the failed fetch persisted and
its scheduled retry recovered without reseed (`pipeline_exit_code=0`).

The full security matrix failed (`scenario_exit_code=1`). Its unreachable-onion
case now stops our own onion daemon and checks a live gateway SOCKS greeting,
avoiding random generated onion targets. After service restart, actual /health
timed out twice over90s despite bootstrap readiness. Dependent redirect/timeout/
reset/active-crawl scenarios consequently failed their required assertions.
After gateway restart, the same controlled onion returned200/ready in6.326s.
This correlates with gateway lifecycle; descriptor/circuit state or external
network behavior is not established as the cause. No assertions were weakened,
failed evidence was not overwritten and the full test was not repeatedly rerun.
The blocker is a complete passing failure matrix with reliable controlled onion
availability after lifecycle transitions. M2 remains BLOCKED.

Final gate: m2_verified=false, real_tor_transport=true,
remote_onion_resolution=true, no_direct_egress=true, proxy_bypass_blocked=true,
m1_regression_passed=true, fail_closed_verified=false. Its combined crawl stage
also keeps controlled_onion_fetch, robots_verified, crawl_delay_verified and
frontier_persistence_verified false because the required /loop-a probe never
reached the fixture after the outage. Raw successful crawl/snapshot observations
above do not override the gate. M1 regression807ed6ad51e2 passed with exit0.
Cleanup removed all containers/networks and retained frontier-data, tor-state
and onion-state; no persistent volume or key was deleted.

The original verifier misleadingly described zero /loop-a requests as a repeated
request. Its assertion remains exactly one request; only the diagnostic text
was corrected. A negative regression covers both zero and duplicate requests.
Original verification.json is preserved; verification-diagnostic.json records
the same failing gate with the corrected message. No fresh Tor run was launched
for that message-only change. No commit or push was performed by this task.

## Contract security patch — 2026-11-01

- TPR-06: OkHttp reads the last Location while StormCrawler reads the first.
  TorProtocol now removes raw Location headers, requires exactly one nonblank
  target for 3xx except304, resolves relative targets and exports only the result
  of TorPolicy.normalize. Rejections remain SCOPE_REJECTED; automatic redirects
  stay disabled. A 304 requires no Location and exports none.
- TPR-08: the actual StormCrawler setting is `protocol.md.prefix`, not
  `protocol.metadata.prefix`. TorProtocol requires a nonblank String; generated
  normal/outage M2 configurations pin `protocol.`. SimpleFetcherBolt adds the
  prefix when merging; TorProtocol does not. The persistence selector now uses
  `protocol.tor.category`. Regression tests exercise Metadata.putAll with hostile
  service-key headers and verify that existing control values survive.
- TPR-10: clearly HTML MIME types or HTML document prefixes (including BOM,
  comments and XML prolog) fail with ROBOTS_UNAVAILABLE before parsing/caching.
  Empty bodies, exact/wildcard agents and unmatched groups keep crawler-commons
  1.6 semantics. No replacement robots grammar or strict UTF-8 decoder was added.
  Tests confirm UTF-8/UTF-16 BOM handling and acceptance of replacement-decoded
  malformed UTF-8 in a comment. Other malformed-content policy remains open.
- TorPolicy/TorScopeFilter: expanded negative URI tests assert IOException;
  filter tests confirm fail-fast missing configuration, rejection with null
  metadata and propagation of pre-configure initialization errors. No additional
  proven runtime defect required changes to normalization, Base32 or lifecycle.
  The stale Base32 extraction TODO was corrected; independent vectors remain open.

Verification actually executed on OpenJDK17:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk mvn -o -Dmaven.repo.local=.runtime/maven-linux-repository -Dkotlin.compiler.daemon=false test
python3 -m unittest discover -s test/m2 -p 'test_*.py'
git diff --check
```

Results: Maven BUILD SUCCESS, 25 tests, zero failures/errors/skips; Python42
tests pass. Both standalone M2 Java probes compile with javac --release17
against the current classes/dependencies; they were not executed. Before the
fix, the focused suite reproduced five failures across redirect, prefix and
HTML contracts. Tests use loopback SOCKS fixtures, not Tor. Sandbox denied local
sockets; the successful runs used authorized loopback socket access. Kotlin
daemon was disabled because its user-level marker directory is read-only here.
Existing compiler warnings (missing src/main/java and an unnecessary safe call)
remain. No external requests, Docker, Tor or dependency upgrades were performed.
Full M2 remains BLOCKED; these tests do not prove absence of DNS leaks. No commit
or push was made.

## Machine-specific Codex notification command

The tracked .codex/config.toml contained an absolute Windows runtime executable
under one user's home. It is invalid on Linux and nonportable on Windows.
The shared file now contains explanatory comments only; it neither overrides nor
disables notifications. Existing user-level configuration is left untouched.
Put a working OS-specific notify command in ~/.codex/config.toml on that machine.
Do not copy a Windows executable path into a Linux user configuration.
Current official documentation places notify in user-level configuration and
ignores it in project config:
https://learn.chatgpt.com/docs/config-file/config-advanced
Actual desktop notification delivery was not exercised by this audit.

## Entire hooks

.codex/hooks.json remains unchanged. All hook commands pass sh syntax checks;
installed Entire recognizes the codex post-tool-use handler. entire doctor
confirms installed/discovered hooks and approval records on this Linux host.
The hooks require sh and entire on PATH. Windows requires a working compatible
shell such as Git for Windows sh on the hook process PATH. Native Windows
execution was not available; Linux checks are not claimed as Windows proof.
Do not disable hooks or remove approval records to work around setup issues.

## Toolchain

Maven test completed successfully: 15 tests, no failures/skips, with the existing
writable dependency cache and no clean rebuild. The same 15 compiled policy and
actual loopback SOCKS tests passed under the Storm image's real JRE17 with no
external network, dropped capabilities, read-only root filesystem and /tmp tmpfs.
JVM sources were not changed for this audit. Windows javac/jar/Maven execution
must still be verified on Windows; Java17-compatible bytecode alone does not
prove every platform path. M0/M1 entrypoints and their contracts are preserved.

## Evidence boundaries

bootstrap_evidence.py now explicitly records bootstrap_100_observed and
observation_kind=log_history. It sets current_connectivity_verified=false:
historical logs cannot establish live Tor connectivity. A live control-socket
bootstrap healthcheck is a process-readiness check, not an onion-service probe.
The separate SOCKS/Tor HTTP preflight and controlled onion HTTP/crawler tests
remain necessary. Focused regressions reject treating historical100 as live
connectivity, including when failure appears later in the same log.

## Persistent-volume lifecycle

Ordinary cleanup must remove containers/networks only and retain frontier-data,
tor-state and onion-state. Missing any required retained volume is an acceptance
failure, even when containers were successfully removed. Exit-code records for
both the negative scenario driver and actual Storm outage pipeline must be
present and zero; a partial successful JSON cannot override a failed command.

Old volumes may hold valuable frontier state and onion private keys. Do not prune
or delete them automatically. Inventory via docker volume ls with the exact
com.docker.compose.project label, identify the owning run, preserve valuable
evidence and backups, and obtain explicit user approval before any deletion.
This audit performs no persistent-volume deletion and adds no deletion helper.

## Version policy

Preserve the assessed Tor/JVM baseline; obfs4 is an explicit bridge capability,
not a broad dependency upgrade. Tor and obfs4 package versions are pinned in the
client Dockerfile. The Debian version tag and other apt packages are not
immutable digest/snapshot locks; bit-identical rebuilds are not guaranteed.
Any future version change needs package compatibility assessment, image build,
private-config negative tests, real bridge/SOCKS preflight, M1 regression and
full M2 security/onion verification. Do not silently switch to latest.

Raw logs/reports are ignored under .runtime/m2/bridge-integration/. Private
bridge configuration lives separately under ignored .secrets/, excluded from
Git and Docker build context. No real bridge line belongs in this document.


## Confirmed HTTP/1.0 compatibility defect found during integration

Actual first bridge crawl retrieved robots200, then root failed with unexpected
EOF before it reached the fixture. A focused loopback SOCKS reproducer proved
that the pinned client's idle connection reuse retained an implicitly closed
HTTP/1.0 stream. TorProtocol now uses zero idle pooled connections; automatic
retry remains disabled, TLS/header/scope/timeout policies are unchanged.
Both implicit- and explicit-close robots→root sequences now pass through exactly
two SOCKS streams. Full Maven verify passes17 tests. The fixture was not changed
to hide the client defect. Cost: more Tor streams/connections per sequential
request; production throughput was not assessed. Initial failed run d42486ff0e09
was stopped diagnostically, all three persistent volumes retained, and remains
failed evidence. A fresh run is required for current M2 acceptance.

## Read-only crawler result

Actual Storm dry-run initially failed loading RocksDB JNI from noexec tmpfs.
Only crawler /tmp now uses rw,exec,nosuid,nodev; the root filesystem stays
read-only and all capabilities remain dropped. The same Flux dry-run then
completed successfully. Evidence: .runtime/m2/readonly-proof/. This is narrower
than restoring a writable root filesystem and does not relax network isolation.

## Focused gate tests

The producer stores raw retained-volumes.json and cleanup.retained_volumes for
all three named volumes. The verifier rejects missing/nonzero driver exits,
boolean false masquerading as zero, frontier-only legacy retention and missing
or false retention for each volume. Log summaries explicitly say observations
rather than live connectivity. The current Python suite passes39 tests.

## Real timeout-scenario correction

The first fixed-client run reached the real onion fixture and persisted/reopened
its corpus. Its deliberately delayed HTTP scenario nevertheless failed the
acceptance gate: a1s deadline expired with REQUEST_TIMEOUT before the fixture
handler marker existed. This proved a timeout during onion-stream setup, not
the intended delayed-response case. The scenario now retains the probe's normal
10s bounded request budget while the fixture delays15s. The mandatory real-handler
marker and exact REQUEST_TIMEOUT checks remain unchanged; connection failures
alone still cannot pass. Existing failed evidence is preserved, not rewritten.


## Bootstrap versus onion availability: runtime finding

Run f269f7dd28b8 returned Tor to healthy bootstrap100 but immediately launching
the recovery crawler resulted in ROBOTS_UNAVAILABLE/REQUEST_TIMEOUT. The second
/outage never reached the fixture. This is distinct from the HTTP/1.0 defect:
all idle streams were already disabled. The runner now separately waits for an
actual controlled onion /health response via SOCKS after Tor returns, before
launching the scheduled retry. The90s readiness budget covers socket reads and
preserves every failed/successful attempt in a dedicated JSON/log. It never
turns bootstrap progress into an onion availability assertion. Robots still
fail closed, and the stored frontier retry date is still respected without reseed.

Focused readiness tests reject errors as success and enforce the overall bound.
Combined Python suite:39pass. The failed full run remains m2_verified=false,
scenario_exit_code=1,pipeline_exit_code=1, with cleanup confirming all three
volumes retained. Its M1 regression passed in85fd45140cd4. A fresh full run
04a0afbf1294 is required for the final result.


## Final audit result

Fixed confirmed gates and portability defects remain uncommitted for review.
41Python M2 tests,17Maven tests,17actualJRE17 tests, all three Compose configs,
Bash syntax, TOML parse, Entire doctor/hook syntax and git diff checks pass.
M1 standalone Python tests:31pass,1optional socket test skipped. Full M1 Docker
regression85fd45140cd4 passed during the bridge run.

Full M2 is not accepted. Run04a0afbf1294 correctly failed the initial onion
availability probe after a successful real bridge/SOCKS/public HTTPS preflight:
SOCKS reachable, direct internet/fixture/DNS paths denied, controlled onion
health timed out. Initial availability now uses the same bounded90s real-health
probe as recovery and retains all attempts. The final readiness change has
focused positive/negative tests; another full integration was NOT run after it.
Old failed evidence is not overwritten. Both f269f7dd28b8 and04a0afbf1294 cleanup
confirm containers/networks removed and all three persistent volumes retained.

Remaining blockers: reliable controlled onion availability and a complete fresh
M2 fail-closed/outage/retry gate. Native Windows execution and actual desktop
notification delivery remain unexecuted. Hooks/notification user settings were
preserved; no volume deletion was performed or automated. No new audit commit
or push was created after the user's explicit no-commit instruction.

## JVM migration boundary — 2026-11-01

The migration branch now uses Java25/Storm3.1, with StormCrawler3.7 unchanged.
The historical JDK17 private-resolver guard described above is superseded by
a separate test-only InetAddressResolverProvider service JAR. Positive controls
include plain and uppercase/trailing-dot onion hosts;35 tests pass with zero
application onion resolver attempts. All M2 helper Java sources compile for25.
Probes include Storm's lib-common directory. Python M2 67 tests pass.
M2 runtime/recovery/full gate were not rerun: local SOCKS19050 refused and Tor
security/recovery blockers persist. m2_verified=false. Detailed migration
inputs, evidence and limitations: [migration report](jvm25-storm31-migration.md).
