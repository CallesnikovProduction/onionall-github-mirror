# Onionall Brawler security review

## 1. Executive summary

Checkpoint: **2026-11-04, SECURITY RC READY for owner review (local checkpoint)**.
**SECURITY FULLY VERIFIED: NO. RELEASE READY: NO.** S1-01 remains **FIX IMPLEMENTED /
PARTIALLY VERIFIED, NOT CLOSED** pending controlled full Storm/SOCKS qualification
and independent review. Section15 contains RC verification, roadmap and handoff;
sections13/14 retain the investigation/implementation history.
Security assurance: **PARTIALLY VERIFIED**. Full audit and remediation are not complete.

Repository: `CallesnikovProduction/onionall-github-mirror`.
Branch: `security/comprehensive-hardening`.
Reviewed HEAD: `853934e57a17b827487b023e8e2c295375290b65`.
Both `f4f2544` and `f8beebb` are ancestors. Initial worktree was clean.
Root and scripts AGENTS.md were read; neither was changed.

Source reconstruction confirms separate authoritative Frontier admission/scheduling
and H2 archive/catalog ownership. The generated durable crawl selects TorProtocol
for both schemes; the synthetic M0/M1 configuration intentionally uses direct HTTP.
These execution modes must not be conflated. Existing M2 Compose uses stock
Frontier, not the admission-aware candidate.

Stage1 Python baseline and syntax checks were executed. Stage2/3 recovered verified
portable JDK25/Maven and the exact pinned Frontier JAR. Current remediation passes
full Maven verify:124 tests,0 failures/errors,1 existing Windows skip. All four
Frontier conformance drivers pass, with expanded authorization coverage. Windows
test launches now use Java argument files with original assertions/deadlines.
Container acceptance remains unverified; historical records remain history.
The approved local source delta now authenticates legacy mutations and durable
worker clients; no dependency version, schema or Tor isolation change. No system
installation, external onion crawl, real Tor, commit or push was performed.

**S1-01 was CONFIRMED/HIGH at the base HEAD**: capability-free mutation persisted
after restart. The current local delta rejects those requests and preserves valid
worker lease/status/retry/terminal behavior. Reservation/quota semantics remain
unchanged. See section13 for original evidence and section14 for current validation
and the remaining closure gates.

Execution commands/results and environment recovery instructions are in
[the execution ledger](../engineering/security-execution-ledger.md).

## 2. Architecture and attack surface

Evidence references below are repository-relative `file:line` at the reviewed HEAD.
Java application prefix: `src/main/java/dev/onionall/brawler/` (`APP/` below).
Kotlin prefix: `src/main/kotlin/dev/onionall/brawler/` (`KT/`).
Frontier service prefix:
`frontier-server/src/main/java/crawlercommons/urlfrontier/service/` (`SERVER/`).

```text
Operator / Picocli Onionall
  +-- inspect / discover / search-provider -> offline metadata/manual assistance
  +-- fetch -> bounded TorProtocol -> SOCKS gateway -> remote onion
  +-- crawl plan / approve -> ArchiveStore H2 (approval != admission)
  +-- crawl start -> CrawlExecution
        +-- AdmissionClient -> capability gRPC Command -> AdmissionLedger
        |      -> sync-WAL WriteBatch [reservation + quota + normal enqueue]
        |      -> owning Frontier RocksDB -> queue / leases / retry scheduling
        +-- CrawlWorker [archive-wide lock, deadline] -> Storm/Flux process
               -> Frontier Spout -> partitioner -> SimpleFetcher/TorProtocol
               -> JSoup parser
                   -> DurableDiscoveryFilter -> Admission RPC; clears outlinks
                   -> ArchiveParseFilter -> ArchiveStore
                       -> SHA256 HTML object -> H2 snapshot / graph commit
               -> index/status -> ArchiveStatusUpdaterBolt -> normal Frontier ACK
  +-- pages / page show / search-local / archive export -> local H2 + objects
```

### Modules, entrypoints and state owners

| Component | Actual responsibility and evidence |
| --- | --- |
| Maven root | Single application module, Java25/Kotlin2.3.21, Storm3.1.0, StormCrawler3.7.0, URLFrontier2.4, H2 2.4.240, Picocli4.7.7. `pom.xml:29-39,151-189`. JUnit4.13.2; no Maven wrapper. Shaded main is Flux; thin CLI has its own launcher. |
| CLI | `APP/cli/Onionall.java:17-19,112-178,220`; ArchiveCommands registers plan/show/approve/start/status, pages/page show/search-local, assisted search-provider, archive import/export (`APP/cli/ArchiveCommands.java:46-185`). `tools/OnionSmoke.java:46` is a separate diagnostic main. |
| Plans / observations | H2 schema2: crawl_plans, pages, snapshots, page_links, failures, admission_observations; embedded single-process JDBC, AUTO_SERVER false, WRITE_DELAY0 (`APP/archive/ArchiveStore.java:29-78`). H2 never owns scheduling. |
| Execution | Approval checked before RPC; initiating CLI holds start lock, supervisor owns worker lock/deadline; runtime descriptor and executable/JAR/token-file paths are trusted operator inputs (`APP/admission/CrawlExecution.java:20-41,65-126`). |
| Worker processes | CLI -> same-JDK CrawlWorker -> Storm local / Flux, with local TTL. Readiness includes owner PID. Deadline1..690s, graceful then forced owned descendant termination (`APP/admission/CrawlWorker.java:17-63`; `CrawlExecution.java:122-152`). |
| Admission | Immutable approved session; authoritative parent-derived depth, exact origin, page quota and dedup; reservation + counter + existing scheduler row in sync-WAL batch, then in-memory publication (`SERVER/rocksdb/AdmissionLedger.java:94-130`). |
| Frontier storage | default existence pointers, queues, queueInfos, onionallAdmissionV1 CF. Explicit copy-only migration and compatible readonly rollback; rebuild indexes from rows (`SERVER/rocksdb/RocksDBService.java:113-174`). Global admissionLock also serializes legacy writes (`:353-358,520-524`). |
| Queue / retries | Frontier owns lease and next-fetch scheduling. QueueMetadata's processed/lease map is in memory (`SERVER/rocksdb/QueueMetadata.java:30-76`). Reservation replay is idempotent; leases/fetch are at-least-once. Legacy status writes have ordinary WAL, not the admission sync-WAL guarantee (`RocksDBService.java:439-494`). |
| Discovery | DurableDiscoveryFilter reads authoritative parent/reservation, reserves accepted links, retains rejected graph observations, clears normal parser outlinks (`APP/admission/DurableDiscoveryFilter.java:28-40,50-98`). Offline and local-test planners do not establish durable production quotas. |
| Archive | Parser archive commit precedes parser return/emission (`APP/archive/ArchiveParseFilter.java:26-40,56-83`). Catalog failure propagates before normal Frontier status ACK (`ArchiveStatusUpdaterBolt.java:28-41`). |
| Objects / graph | Bounded SHA256 immutable objects, force file + atomic move before H2 transaction commit, guard row lock; orphan object can survive catalog rollback (`APP/archive/ArchiveStore.java:202-306`). Links resolve target page by same job and never enqueue (`:346-365`). |
| Transport | TorPolicy validates checksummed v3 host, scheme/authority/default port/dot paths (`KT/tor/TorPolicy.kt:145-177,239-255`). TorProtocol pins numeric SOCKS endpoint, rejects local destination-host resolution in OkHttp, disables automatic redirects/retries, bounds I/O, cancels active calls (`KT/tor/TorProtocol.kt:198-277,322-427,551-564`). |
| Robots | Same transport/policy, per-origin single-flight cache, strict decoding, error propagation, no cached allow-all on failure (`KT/tor/TorProtocol.kt:460-533`). |
| Network services | Candidate gRPC default7071, all-interface server builder; optional Prometheus HTTP only with positive -s/--prometheus_server (`SERVER/URLFrontierServer.java:32-59,85-102,173-181`). --hostname is reported identity, not a bind constraint. Storm local also starts runtime services such as ZooKeeper; actual sockets not inventoried without runtime. |
| Other network paths | AdmissionClient plaintext gRPC (`APP/admission/AdmissionClient.java:25-43`); CLI status only localhost TCP (`APP/cli/Onionall.java:173-178`). No second application HTTP client found in main source search. Tests/health scripts have their own diagnostic clients. |

### Topology and deployment distinctions

Generated durable configuration (`APP/admission/CrawlExecution.java:188-226`)
uses TorProtocol for HTTP/HTTPS, ONLY mode, one plan host, single fetch thread,
max pending10, 100 parser outlinks, HTML128KiB, timeout <=30s, 2s spacing and
max fetch failures3. Filters are durable admission then archive (`:87-96`).

`crawler.flux:12-44` instead wires the historical Fetcher/sitemap/feed/JSoup/Tika
topology. `crawler-conf.yaml:72-73` uses stock HttpProtocol for controlled M0/M1.
This direct synthetic transport is not a demonstrated Tor fallback. No production
Tor guarantee is inferred from those modes.

Compose source topology:

| File | Networks, volumes and process boundary |
| --- | --- |
| compose.m0.yaml | Internal baseline; crawler shares Frontier namespace; Frontier RocksDB tmpfs, no durable state, repository read-only bind. |
| compose.m1.yaml | Internal controlled network; stock Frontier + named frontier-data; fixture; tools crawler/probe; network-none root volume initializer. Synthetic direct HTTP only. |
| compose.m2.yaml:1-175 | Internal crawler and fixture networks, separate outbound. Gateway connects crawler+outbound; onion Tor connects fixture+outbound; fixture only fixture; crawler/Frontier only crawler. No host-published ports. Named frontier-data, tor-state, onion-state. Separate read-only bridge-secret mounts only to the Tor processes. Most long-lived components drop caps/set no-new-privileges; crawler/Tor/fixture readonly rootfs. Frontier rootfs is not explicitly readonly. Initializers are root/network-none. |

M2's `frontier` image remains `crawlercommons/url-frontier:2.4` (`compose.m2.yaml:89`).
The admission-aware candidate is built separately from a hash-pinned service JAR;
`test/admission/local_integration.py` supplies a controlled candidate override and
SOCKS fixture. The runtime descriptor selects the actual durable server/endpoints.
No deployment or image identity was observed live in this checkout.

Tor gateway config disables DNS/Trans/Control TCP ports and uses cookie-auth Unix
control socket; SOCKS9050 has OnionTrafficOnly/SafeSocks
(`docker/m2/torrc.gateway:6-14`). Owned onion service maps80->fixture8080 and has no
SOCKS (`docker/m2/torrc.onion:6-15`). Host egress/DNS enforcement remains NOT VERIFIED.

### Trust boundaries

| Boundary / input | Existing guard and remaining verification |
| --- | --- |
| Operator -> CLI / runtime | Picocli bounds; approved stored plan before start; descriptor/JAR/executable/token paths are trusted. Establish local filesystem ACL threat model. |
| CLI -> Admission RPC | Header capability, 10s deadline, JSON32KiB, response64KiB, plaintext. Endpoint authenticity depends on trusted network. |
| RPC -> AdmissionLedger | UTF8/JSON/type/URL/spec checks, inbound gRPC128KiB, shared lock. No worker/operator role separation. |
| Ledger -> RocksDB | One sync-WAL batch reservation/quota/enqueue; index after commit. Current real crash/ACK-loss conformance passes; this does not give ordinary legacy status writes the same sync-WAL guarantee. |
| Storm -> Frontier legacy API | Reservation existence blocks unreserved protected URLs; read/lease/status calls lack capability. Scheduling mutation is priority verification. |
| Frontier -> worker leases | At-least-once; persisted reservations do not imply exactly-once fetching. Reopen rebuild vs active leases needs runtime evidence. |
| Fetcher -> TorProtocol | Both schemes fixed in generated worker; metadata override guards; no fallback found. Real ProtocolFactory partial-failure lifecycle remains unverified. |
| TorProtocol -> SOCKS | Numeric configured endpoint, native JDK SOCKS domain transfer, finite timeouts/cancel. Endpoint and native credential isolation need fixtures. |
| Gateway -> Tor network | Tor-only architecture and internal networks configured; no live routing/DNS proof in this pass. |
| Remote response -> parser | HTML-only identity encoding and bounded body in durable mode; hostile HTML remains untrusted. Header/parser aggregate budgets need review. |
| Parser -> discovery | Canonicalize/authoritative reservation/depth/exact origin; clear ordinary outlinks. Forged parent/source and redirect differentials need JVM baseline. |
| Parser -> archive | Approvals, URL/source/depth/MIME/size bounds; transaction before parser return. No global transaction with Frontier. |
| Archive -> filesystem/H2 | Hash/type/size/symlink checks, atomic move then SQL commit. Directory durability and concurrent filesystem replacement remain open. |
| Status -> Frontier | Catalog observation first, normal Frontier serialization/ACK; catalog failure propagates. Authenticated writer identity absent on legacy API. |
| Containers -> networks/secrets | Segmentation/read-only mounts/drop caps; no CPU/memory/PID caps. Actual deployment identity and endpoints not inspected. |
| CLI -> supervisor -> Storm | Argument vectors, readiness PID, child-owned file lock/deadline. Initiator death differs from supervisor hard kill. |
| Graph / search provider / future UI | Graph read cannot enqueue; assisted provider link/manual import only. UI not implemented; raw HTML must never execute in future primary UI origin. |

## 3. Preliminary threat model

Assets: operator capability and bridge/onion keys; approval/session identity;
reservation/quota/scheduling/leases; archived HTML/hash/provenance; graph/job
isolation; runtime JAR/configuration; process ownership; bounded CPU/RAM/disk/time.

Actors: malicious public onion/HTML/link; untrusted provider result; compromised
worker or same-network container; local user with archive-directory access;
dependency/image compromise; crash, interrupted I/O and power loss.
Assumptions needing explicit Stage2 agreement: trusted local operator, trusted
runtime descriptor/token directory, one isolated crawler deployment, no public RPC.

| STRIDE | Concrete scenario / priority |
| --- | --- |
| Spoofing | Impersonate admission endpoint on plaintext network; alter trusted SOCKS endpoint; capability role confusion. P1. |
| Tampering | Unauthenticated legacy status changes reserved URL schedule; forge metadata/source/depth; swap archive object. P1; reserve/scope guards already exist. |
| Repudiation | Reservation identity vs observed graph/history and scheduler outcome; logs must preserve failure evidence without credential material. P2. |
| Information disclosure | Plaintext capability, broadly readable legacy listings, process/log/config exposure, native SOCKS authentication identity. P1 conditional on attacker reach. |
| Denial of service | Lease theft/status suppression, unbounded stream backlog, parser/header work, no container CPU/RAM/PID cap, supervisor loss. P1/P2. |
| Elevation of privilege | Compromised worker reuses shared capability for operator open/seal or other sessions; local artifact/config replacement. P1 conditional. |

Preliminary attack trees (unproved paths are investigation targets):

- Suppress admitted work -> reach Frontier -> unauthenticated list/read -> terminal
  KnownURLItem via PutURLs -> missing scheduled row (S1-01; reproduced, section13).
- Gain operator authority -> compromise worker/token directory OR intercept
  plaintext metadata -> reuse shared capability -> open/seal another session
  (S1-02/03; no demonstrated compromise).
- Escape scope/transport -> malicious URL/redirect/base/metadata -> parser
  differential -> reserve/legacy guard -> fetch. Exact-origin reserve blocks the
  reviewed cross-scheme hypothesis; test end to end before asserting bypass.
- Corrupt availability/integrity -> power loss after catalog/object publication
  OR local path swap OR supervisor loss -> missing blob/overlap (S1-04/05/08).
- Exhaust resources -> long write stream / hostile headers/HTML -> queued work
  beyond per-message bounds -> CPU/RAM/disk pressure (S1-06/07).
- Compromise supply chain -> substituted JAR/image/dependency -> trusted runtime;
  baseline JAR hash is pinned, full advisory/artifact audit remains NOT RUN.

## 4. Security findings

All findings apply to reviewed HEAD. IDs are stable for later verification.
Severity is provisional impact within the stated preconditions, not CVSS scoring.
No code or acceptance assertions were changed.

### S1-01: legacy scheduling writes lack capability authentication

- Severity: **HIGH, conditional on RPC reachability**. Confidence: **CONFIRMED**
  by real loopback gRPC, actual RocksDB readback and separate-process restarts.
- Component: AdmissionRpc / AdmissionLedger / RocksDBService.
- Evidence: `SERVER/rocksdb/AdmissionRpc.java:37-45` authenticates Command only and
  permits PutURLs/GetURLs/list calls without token. `AdmissionLedger.java:132-138`
  only checks protected-session reservation + exact URL/key identity.
  `RocksDBService.java:353-383,452-494` allows a KnownURLItem nextFetch0 to remove
  scheduling and mark complete after that guard.
- Preconditions/actor: client in reachable internal network knows or lists crawl
  and reserved URL; no admission capability required. Remote onion HTML alone
  is not shown to reach this API. Public reachability is not established.
- Impact supported by source: suppress or forge schedule/status of existing
  reserved URLs; possible lease interference. No quota bypass or creation of new
  protected unreserved URLs is asserted.
- Existing protections: admin mutations disabled, protected-crawl reserve guard,
  isolated-network deployment contract. README explicitly retains trusted legacy
  read/lease/status support; finding concerns compromised internal-client model.
- Related tests: AdmissionRpcConformance covers missing-token Command rejection,
  admin rejection, legacy read; AdmissionConformance covers unreserved legacy
  creation denial. Neither demonstrates refusal of forged reserved-URL status.
- Reproduction completed: `.runtime/security-stage23/rpc-reproduction/result.json`,
  five-phase `.runtime/security-stage23/rpc-process-restart/*.json` and endpoint
  authorization matrix. Fetch-time, scheduling metadata and terminal suppression
  persist; reservation/quota unchanged. Details and limits are in section13.
- Proposed direction: decide worker status/lease authority compatible with stock
  clients, then authenticate/authorize those calls. Design review before protocol
  or deployment-boundary changes. Regression must retain legitimate ACK/retry.
- Compatibility/rollback: tokenless Spout/StatusUpdater clients require a coordinated
  adapter before server enforcement; protobuf/schema need no change. Authentication
  semantics do change; design review was approved for local implementation.
  Status: **CONFIRMED, FIX IMPLEMENTED / PARTIALLY VERIFIED**, section14.

### S1-02: shared operator/worker admission capability

**DESIGN GAP; conditional HIGH impact.** `APP/admission/CrawlExecution.java:98-107`
opens approved sessions; `DurableDiscoveryFilter.java:38,51,82` reads same token;
`SERVER/rocksdb/AdmissionRpc.java:37-39` has no role/session/action restriction.
Compromised token-bearing worker could perform other operator actions. This is
documented single-deployment trust, not unauthenticated approval bypass. Next:
disposable role/session action matrix; agree scoped capability contract before fix.

### S1-03: plaintext capability and no specific listener bind

**DESIGN GAP; MEDIUM, conditionally HIGH.** `AdmissionClient.java:30` uses plaintext;
`SERVER/URLFrontierServer.java:177-181` uses forPort without TLS/specific address.
Trusted isolated network is the current documented protection; --hostname only
reports identity. No interception or public exposure observed. Next: deployment
inventory and endpoint authenticity threat model, including optional metrics
listener (`:93-102`). mTLS/bind changes require compatibility/design review.

### S1-04: supervisor hard-kill ownership scenario untested

**HYPOTHESIS; potential HIGH impact.** Lock/deadline live in CrawlWorker itself
(`APP/admission/CrawlWorker.java:22-36`); forced supervisor death bypasses hooks
and releases lock while descendants may survive. Existing
`CrawlWorkerTest.java:34-67` kills initiator or rejects a second living owner, not
the supervisor. Storm local TTL is an independent bound; unbounded survival is
not proven. Next: fresh owned child fixture, kill supervisor, measure descendants,
TTL/lock release/second-launch overlap. No product process was killed in this pass.

### S1-05: object directory power-loss durability unspecified

**DESIGN GAP; provisional MEDIUM.** `APP/archive/ArchiveStore.java:223-227` forces
blob then renames; H2 commit at303 follows, with no explicit containing-directory
fsync. A process-crash/rollback test does not prove power-loss durability. Actual
loss depends on OS/filesystem guarantees. Next: define supported storage failure
contract and controlled fault-injection expectations. Do not claim missing objects
were reproduced. No schema or persistence change proposed without that evidence.

### S1-06: aggregate streaming write backlog

**HYPOTHESIS; provisional MEDIUM.** `SERVER/AbstractFrontierService.java:139-143`
creates fixed pools and `:807-816` enqueues received PutURLs items without explicit
application queue cap. Per-message128KiB is not total-stream budget. Real gRPC
flow control may constrain impact; bounded disposable measurement required.

### S1-07: container resource budgets absent

**DESIGN GAP; provisional MEDIUM.** compose.m2.yaml has no explicit CPU/memory/PID
limits. Application quota/body/deadline bounds mitigate some inputs; exhaustion
was not reproduced. Next: measure bounded idle/crawl resource use, then propose
compatible limits; no stress test against any deployment.

### S1-08: local filesystem concurrent replacement

**HYPOTHESIS; severity unassigned.** `APP/archive/ArchiveStore.java:49-51,208-225`
checks symlinks before use; lock opens use normal FileChannel CREATE/WRITE
(`CrawlExecution.java:80-84`; `CrawlWorker.java:22,47`). Requires write/rename access
inside trusted directories. No remote-content path to these names found. Next:
establish private ACL/ownership assumptions before disposable TOCTOU testing.

### S1-09: native SOCKS credential identity not asserted

**HYPOTHESIS; severity unassigned.** `TorProtocolTest.java:65-74` covers USER_PASSW
and ATYP3, while fixture at672-673 discards username/password. No per-job identity
or absence of ambient Authenticator/system-user data asserted. Next: bounded
local SOCKS transcript checks with synthetic credentials; no leak proven.

## 5. Severity matrix

| ID | Provisional severity | Confidence | Priority / decisive next evidence |
| --- | --- | --- | --- |
| S1-01 | HIGH conditional | CONFIRMED | P1 local fix implemented; full integration/independent review pending |
| S1-02 | HIGH conditional | DESIGN GAP | P1: operator/worker authority matrix |
| S1-03 | MEDIUM / HIGH conditional | DESIGN GAP | P1 deployment trust; listener/transport inventory |
| S1-04 | HIGH potential | HYPOTHESIS | P1: supervisor loss/overlap fixture |
| S1-05 | MEDIUM provisional | DESIGN GAP | P2: filesystem durability contract |
| S1-06 | MEDIUM provisional | HYPOTHESIS | P2: bounded stream flow-control measurement |
| S1-07 | MEDIUM provisional | DESIGN GAP | P2: resource budget inventory |
| S1-08 | Undetermined | HYPOTHESIS | Establish attacker local write prerequisite |
| S1-09 | Undetermined | HYPOTHESIS | Capture synthetic native SOCKS identity |

## 6. Confirmed vulnerabilities

Stage1 established no reproduced vulnerability. Stage2/3 confirms S1-01 scheduling
authorization weakness on a reachable candidate Frontier, including persisted
tampering after restart. No CRITICAL/P0-severity finding, protected admission bypass
or quota corruption was demonstrated. P0 was the user's verification priority,
not an assigned severity. The current approved local fix is in section14;
full closure remains pending integration and independent review.

## 7. Potential vulnerabilities

S1-04, S1-06, S1-08 and S1-09 still require the decisive tests above.
Cross-scheme same-host transport allowance alone was investigated and does not
establish a durable same-origin bypass: AdmissionLedger.reserve:109 checks exact
origin, permitsLegacy:132-138 requires a reserved exact URL. Retain as regression
scenario, not a finding. Synthetic direct HTTP is not an automatic fallback finding.

## 8. Architectural gaps

S1-02/03 document a trusted local pipeline rather than per-worker authorization;
S1-05 concerns host-power-loss guarantees, S1-07 resource budgets. No second
scheduler, queue or catalog authority is needed. Future UI is NOT IMPLEMENTED;
its absence is not a vulnerability. Real Storm ProtocolFactory partial shutdown,
TLS/HTTP2/header aggregate limits and native Windows behavior remain verification
debt (`KT/tor/TorProtocol.kt:283,320,549`).

## 9. Remediation plan and Stage2/3 gate

1. Approved authorization design is locally implemented; no commit/push or rollout.
2. Full Maven verify and four server conformance checks now pass. Preserve the
   Windows argument-file launcher and existing assertions; do not replace fixture
   evidence with a claim of real-Tor or full container acceptance.
3. S1-01 persisted-state reproduction and initial capability matrix are complete.
   Remaining actor reachability and real-Storm supervisor-loss verification are open.
4. Have independent read-only reviewers consolidate findings and severity. Stage3
   should cover dependency advisories and bounded parser/stream/identity scenarios.
5. Finish independent diff review and controlled deployment acceptance before
   closing S1-01. Additional protocol/schema/isolation changes require separate review.

## 10. Fixed issues

S1-01 authorization guard/client wiring is locally implemented and regressions
pass; full closure remains pending section14 gates. The Windows nested-classpath
test-launch issue is corrected with Java argument files; original process assertions
remain. DeepSeek audit files are unrelated and were not modified.

## 11. Remaining risks and verification limits

- Prior JVM118/packaged acceptance remains historical. Current full Maven verify
  PASS124 with1 Windows skip; current real DB/gRPC conformance all4 pass. Container
  deployment/actual Storm-cluster acceptance and independent review remain open.
- Standalone verified Compose5.7.0 config validation passes all3 files; Docker
  Engine/Linux runtime unavailable. Unsigned static Docker CLI archive was obtained
  from the official endpoint but not executed/installed. No container namespace,
  live deployment, volumes or production metrics inventory was performed.
- Python M2 full command remains FAILED with environmental/test portability errors;
  successful subsets do not replace it. CLI discovery was all skipped.
- No real Tor, external onion crawl, host networking change, user DB access or
  volume migration. M2/M3 full qualification remains BLOCKED as previously recorded.
- Limited full-history signature scan is not Gitleaks/entropy/CVE scanning. Six
  credential-URL matches were synthetic negative test fixtures. No operational
  secret identified by those patterns; no guarantee of complete secret absence.
- Dependency/security scanners were not found. No CVE applicability asserted;
  declared vs resolved vs shaded/image dependency inventory remains outstanding.
- Dockerfile pins Tor0.4.9.11-0+deb12u1 and Debian12.12-slim; current upstream
  applicability/provenance requires Stage3 review, not an automatic upgrade.

## 12. Recommendations for future engineering

Keep Frontier as sole scheduler and reservation/quota authority; keep graph/catalog
read-only with respect to admission. Preserve original provenance and exactly the
same session on reconciliation/retry. Preserve failed run evidence and acceptance
assertions. Establish authenticated operator/worker boundaries before widening
deployment trust. Define durability/backup/restore and supported filesystem contract.

Future SAFE/FREEDOM must both retain Tor-only/no private-network access, finite
budgets, authentication and explicit approval for enlarged scope. Search results
remain untrusted and cannot create jobs directly. File discovery must remain
separate from approved bounded download. Future UI needs localhost/Host validation,
CSRF/auth/session/CORS/WebSocket authorization and isolated sanitized HTML reader
with CSP/no active remote resources; raw stored HTML must not execute in main UI.

Next agent should start with this review and ledger, then source-backed reproduction
evidence in section13, not repeat S1-01 discovery. Do not run external Tor or install
system components to compensate for blocked container acceptance without permission.

## 13. Stage2/3 verified authorization boundary and remediation design

### Persisted-state proof, not just RPC acknowledgement

Unchanged source HEAD remains `853934e57a17b827487b023e8e2c295375290b65`.
Disposable fixture drivers/classes/DBs/logs live only in ignored
`.runtime/security-stage23/`. Repository application sources and existing tests
were not edited. No crawler, real Tor or external target was started.

`PersistedRpcProbe` runs each phase as a new Java process with a fresh server/channel
but the same fixture DB. Exact commands are in the ledger. Every phase checks the
original reservation JSON and reserved_count1:

| Phase / separate PID | Actual assertion / evidence |
| --- | --- |
| reschedule /59072 | Valid capability opens/reserves seed; raw tokenless PutURLs updates next-fetch+metadata, ACK OK. |
| read-rescheduled /44080 | RocksDB reopened in new JVM: nextFetch1793778883 and synthetic forged discovery.source retained; zero due/fetchable rows. |
| terminal /58100 | Raw tokenless KnownURLItem(nextFetch0) acknowledged OK; DB closed. |
| read-terminal /59828 | New JVM reads nextFetch0 and zero fetchable rows; reservation JSON unchanged and quota1. |
| replay /31500 | New JVM: ALREADY_RESERVED, original reservation_id, quota1, terminal unchanged. New protected Known/Discovered URL both FAIL; no-token Command PERMISSION_DENIED. |

Source chain: `AdmissionRpc.guard:37-45` -> legacy
`AbstractFrontierService.putURLs:773-816` -> `RocksDBService.putURLItem:353-358` ->
`AdmissionLedger.permitsLegacy:132-138` -> `putLegacyURLItem:378-494`.
No user DB/schema was migrated. These are graceful process-restart assertions,
not power-loss proof or a new synchronous-WAL guarantee for legacy status updates.

Additional actual observations (`rpc-reproduction/result.json`): raw GetURLs leases
one reserved URL; an immediate worker request receives zero. Raw terminal resurrection
is accepted. A tokenless Discovered item in a crawl without an Admission session
is accepted by intentional legacy compatibility (`SESSION_NOT_FOUND`), but no
fetcher runs. This is an unprotected-crawl enqueue path, not a protected quota bypass.
An existing shared worker capability can open/seal another session (S1-02 behavior
verified; current token is deliberately operator-equivalent).

`endpoint-matrix/result.json`: both missing/wrong tokens deny Admission Command;
legacy status accepts valid AND invalid token. All six other admin mutation methods
(DeleteCrawl, DeleteQueue, SetActive, SetDelay, BlockQueueUntil, SetLogLevel) return
PERMISSION_DENIED with missing and valid token. No additional allowed scheduling
mutator found; GetURLs must count as a mutation because it issues leases.
The equivalent fixture server binds `[::]:ephemeral`; non-TLS loopback calls succeed.
No external-host reachability, production binding or passive interception tested.

### Interpretation and legitimate callers

The current README explicitly permits trusted legacy lease/status clients inside
an isolated deployment. Thus this is a confirmed weakness against a compromised
reachable internal client, not a regression against a documented promise that all
legacy calls already require capability. A remote onion page alone has not been
shown to reach RPC. Product impact is persistent crawl suppression/postponement,
poisoned scheduling metadata and lease interference. Security impact is unauthorized
state control without the operator capability. Immutable admission authority survived.

| Legitimate caller | RPC / existing credentials | Compatibility requirement |
| --- | --- | --- |
| Stock StormCrawler Spout | GetURLs for leasing; private bare channel/stub, no capability metadata | Authenticate initial channel and reconnect/recreation. |
| ArchiveStatusUpdaterBolt -> stock StatusUpdaterBolt | Bidirectional PutURLs for scheduler-selected success/error/retry/terminal updates; no capability metadata | Preserve ACK/fail, backpressure, terminal/retry dates and reconnect behavior. |
| CrawlExecution / DurableDiscoveryFilter / AdmissionProbe | Admission Command with existing x-onionall-admission-token | Existing header/token stays; reserve/quota code unchanged. |
| M1/M2 FrontierProbe, FrontierLeaseProbe and fixture seeders | Legacy PutURLs/GetURLs, normally stock synthetic Frontier | Keep stock fixtures distinct; any probe aimed at hardened candidate must explicitly supply capability. |
| Read-only probes/status | ListURLs/GetURLStatus/list/stats | Policy may remain unchanged for this narrow fix; confidentiality is a separate open question. |

Evidence: `APP/archive/ArchiveStatusUpdaterBolt.java:19-22,35-40`,
`APP/admission/CrawlExecution.java:194-211,214-226`, `test/m1/FrontierProbe.java:25-82`,
`test/m2/FrontierLeaseProbe.java:25-68`, `test/m2/FrontierProbe.java`,
`test/m2/TerminalMetadataProbe.java`, `test/admission/AdmissionProbe.java:15-23`.
Resolved StormCrawler3.7.0 bytecode inspection is saved as
`stormcrawler-frontier-calls-bytecode.log`: stock Spout/StatusUpdater instantiate
ManagedChannelUtil bare channels and stubs; the package-private factory exposes
only host/port or target, no configured interceptor hook. Adding a token config
key alone will not authenticate them. A concrete source-pinned client integration
must be reviewed; do not guess at private fields or replace scheduling components.

### Minimum fail-closed change proposed; implementation STOP

1. Reuse existing token/header and MessageDigest.isEqual at the owning server guard.
   Require valid capability before dispatch of **all PutURLs and GetURLs**, regardless
   of protected/unprotected crawl ID. Preserve Command authentication. Missing/wrong
   token closes RPC with PERMISSION_DENIED before handler/queue/task/lease changes.
2. Keep the existing admin denylist behavior for all six methods even with token;
   retain deny-by-default for unknown methods. Read/list policy is unchanged and
   is not a claim of complete RPC confidentiality or per-session authorization.
3. As a prerequisite, integrate the same existing token-file capability into both
   legitimate stock worker channels, including streaming recreation/reconnect.
   Prefer one small source-pinned client adapter/patch after reviewing the missing
   upstream hook. No reflection hack, anonymous fallback, extra listener, new
   auth service, new queue, or change to reservation/scheduler ownership.
4. Server and compatible clients must be deployed as one reviewed pair. Reject
   absent/invalid client capability at startup rather than silently hanging a crawl.
   No token value in argv/log/evidence; use the existing private token-file input.

This changes the **authentication contract**, though protobuf/message schema and
admission JSON stay unchanged. Old tokenless candidate workers stop functioning.
It therefore requires design review and client compatibility evidence before any
source change. Server-only enforcement would break valid crawling. Shared-token
worker escalation (S1-02) and plaintext transport (S1-03) remain separate residual
risks; reuse of the token does not solve them.

### Regression gates / compatibility / rollback

| Gate | Measurable acceptance |
| --- | --- |
| G1 negative auth | Missing/invalid token PutURLs and GetURLs -> PERMISSION_DENIED; no stream ACK OK, scheduled-row/metadata/terminal/reservation/quota/index/lease changes; repeat with unprotected crawl ID and partially consumed/cancelled stream. |
| G2 persistence | Fresh-process restart after rejected attempts retains exact original nextFetch, metadata, reservation_id/ordinal/count and due URL set. Rejected GetURLs does not lease: authenticated worker immediately receives the due seed. |
| G3 valid client | Existing capability supports real Spout leasing and StatusUpdater success/retry/terminal ACK, channel recreation/reconnect and 503 retry; no duplicate reservation or quota use. Admission remains sole creator for protected crawls. |
| G4 all mutation endpoints | Six admin calls remain denied even with capability; unknown method rejected. Authenticated and unauthenticated protected unreserved legacy creation still fails. |
| G5 restart/replay | Existing four real conformance suites remain PASS (quota64-way concurrency, WAL crash/ACK-loss, copy-only migration/compatible readonly rollback, shutdown drain, RPC). Same-session replay retains reservation/count and does not reseed terminal URLs. |
| G6 integration / routing | Controlled CLI -> candidate Frontier -> real Storm -> SOCKS fixture -> archive retains existing assertions, robots/503/reopen; no direct socket on scope/proxy failure. Docker fixture acceptance required before claiming end-to-end compatibility. |

Prepared negative scratch regression:
`LegacyRpcProbe <fresh-db-root> --assert-secure`. It expects PERMISSION_DENIED and
unchanged scheduling/reservation/quota; current unmodified server exits1 with
SECURITY_REGRESSION_FAILED and persisted readback proves the mutation. This RED
security gate is expected evidence, not a baseline success or a waived assertion.
Future tracked regression should also snapshot after rejected process restart and
add G1/G3 lease/stream cases. Existing tests were not modified in this pass.

Rollout: stop new admissions/worker launches, drain bounded in-flight work, deploy
reviewed client/server pair in the same network, then verify G1..G6 before resuming.
Rollback may only use a schema-compatible admission-aware binary pair retaining
the authorization gate, or leave execution stopped with data preserved. Do not
rollback to anonymous writes, stock3-CF server, remove the admission CF, reseed or
decrement quota. No schema migration is required by this proposal.

### Windows CrawlWorkerTest root cause and scope

Ordinary Maven verify ran118 tests:115 PASS,1 failure,1 error,1 existing Windows
skip. Surefire java.class.path is21,474 characters/168 entries; the worker launch
nests that classpath twice (>42,948 characters before other arguments), exceeding
the Windows command-line limit. `windows-classpath-diagnosis.json` records counts.

The unchanged test Initiator launched with a21,781-character outer command exits1
with CreateProcess206 at `CrawlWorkerTest$Initiator.main:25` before worker readiness
(`windows-initiator-long-cp/initiator.log`). This explains the first test's misleading
readiness timeout. The second test reports206 directly. Neither demonstrates a
worker deadline/termination defect because the owner/child pair was not launched.

The same unchanged test class and exact dependency closure run with a short JAR
manifest Class-Path: **2/2 PASS**,7.732s
(`windows-worker-short-classpath-run2.log`). It verifies initiator hard kill,
child-owned lock/deadline, competing-owner rejection, exit124 and child cleanup.
No timeout/assertion/source/POM was changed. A first scratch manifest attempt lacked
directory trailing slashes and failed class loading; its log is retained.

This is a launch/test-platform diagnosis, not full Maven verify PASS or proof of
supervisor-SIGKILL/real-Storm TTL behavior. S1-04 stays HYPOTHESIS. A future minimal
portable classpath/argfile fix should be a separate approved change with long-path
Windows regression and the unchanged process/deadline assertions.

## 14. Approved S1-01 local remediation — 2026-11-04

User approved the authentication-contract design and local implementation/testing.
Base HEAD/branch unchanged; the approved delta is now locally checkpointed, not
deployed. Current Security RC commit and handoff status is recorded in section15.
**FIX IMPLEMENTED / PARTIALLY VERIFIED; S1-01 NOT CLOSED** until the remaining
full integration and independent-review gates below are satisfied.

### Changes and preserved boundaries

- `SERVER/rocksdb/AdmissionRpc.java`: exact fully qualified Command/PutURLs/GetURLs
  require the same existing capability for every crawl ID. Existing constant-time
  comparison is retained; rejection occurs before handler dispatch. Public reads
  require the actual URLFrontier service prefix. Admin/unknown calls stay denied.
- `APP/admission/FrontierCapability.java`: standard gRPC CallCredentials reads the
  existing bounded regular token file, validates at startup and re-reads on each
  RPC/stream establishment. Missing/malformed/deleted file fails with sanitized
  PERMISSION_DENIED; no token logging, credential cache or anonymous fallback.
- `src/main/java/org/apache/stormcrawler/urlfrontier/CapabilitySpout.java` and
  `CapabilityStatusUpdaterBolt.java`: explicit source-pinned StormCrawler3.7.0
  adapters because upstream has private channels and no credentials hook. Class
  identity, mandatory credential construction and withCallCredentials at every
  initial/recreated stub are the only changes. After removing those exact changes,
  source matches official pinned upstream byte-for-byte (upstream-provenance.json).
  Queue, scheduler, ACK, backpressure and reconnection policy are not reimplemented.
- `APP/archive/CapabilityArchiveStatusUpdaterBolt.java`: same archive-outcome
  observation delegates to existing ArchiveStatusUpdaterBolt.recordOutcome before
  authenticated storage. Generated CrawlExecution topology chooses this and
  CapabilitySpout. Original synthetic M0/M1 and local-test archive components stay
  stock/separate; hardened server never accepts their anonymous writes.
- `CrawlWorkerTest.java`: outer-test-owned nested Java argument files replace long
  command arguments; --disable-@files preserves nested literal references. All
  existing assertions/deadlines remain. Added real >40,000-character classpath,
  spaced directory and literal quote/backslash/@ argument round trip.
- Public deployment/contract docs and root AGENTS.md updated for paired rollout.
  No dependency version, protobuf, admission guard, RocksDB/H2 schema, scheduler,
  retry budget, TorProtocol or isolation/Compose change.

### Regression evidence

| Current check | Result / material assertion |
| --- | --- |
| New server regression against base guard | RED: anonymous write was acknowledged, proving the test detects original S1-01. `server/rpc-red.log`. |
| Expanded AdmissionRpcConformance | PASS: missing/wrong multi-item PutURLs and GetURLs denied with zero ACK; scheduling/metadata/reservation/quota and lease unchanged; protected/unprotected crawl coverage; separate-JVM restart; all6 admin denials; valid lease/retry/terminal/recreated channel/replay. `server/AdmissionRpcConformance-final2.log` after `tests-lint-final3.log` compilation. |
| Remaining three Frontier conformance drivers | PASS: concurrency/quota/WAL crash, explicit disposable migration/compatible rollback and shutdown drain. `server/*-green2.log`. |
| Real CapabilitySpout/StatusUpdater component integration | PASS5/5: actual loopback channels, file failure/rotation, rejected stream never reaches handler, legitimate ACK/retry/terminal, real reconnect with re-read token. `client-tests/junit-deterministic.log`; root Surefire repeats all5 PASS. |
| Windows worker tests | PASS3/3: original lifetime/ownership assertions plus long-path argument-file regression under normal Maven. |
| Full Maven verify | PASS:124 run,0 failures,0 errors,1 existing Windows-only symlink skip; packaged shaded application built. `maven-verify-complete-cache.log`, `jvm-summary.json`. |
| TorProtocol/robots/policy JVM checks | Included in successful verify:35 wire tests plus cache/policy/scope regressions; local SOCKS only, no real Tor acceptance. |
| Python | M1 PASS32, archive PASS2, security PASS4, M3 PASS2. M2 full native Windows command FAIL113:3 failures,5 errors,2 skipped,103 passed; known POSIX permissions, /bin/bash and fake-JDK fixture portability. No assertions weakened to force PASS. |
| Lint/static | Server guard/test javac -Xlint:all clean; new client source javac -Xlint:all exits0 with14 warnings (deprecated gRPC marker, dependency annotation and upstream serialization patterns), not warning-free. Diff/secret/link/provenance validation recorded in ledger. |

All current evidence is under `.runtime/security-remediation/`; old failures and
pre-remediation evidence are retained. First ordinary compilation failed only
because sandbox blocked Kotlin daemon localhost access; authorized offline compile
passed. First verify found a new reconnect fixture condition error; it was corrected
to observe actual TRANSIENT_FAILURE while own server port is closed, not to force a
callback or weaken authentication assertions. Next verify passed all tests but
offline packaging lacked existing plugin dependencies; official Central filled the
local cache with strict checksums, then full unchanged verify passed.

### Compatibility, limitations and closure

Old tokenless workers cannot mutate/lease on the hardened server. Deploy this
candidate with the matching generated topology/clients and private token-file input.
Stock synthetic fixtures are intentionally separate, not an anonymous fallback.
Existing read-only RPC policy and shared worker/operator capability remain; this
fix does not establish per-session/role authorization or encrypt plaintext traffic.

CallCredentials validates at call/stream establishment, not every streaming item.
Removing a file prevents future leases/new streams but does not revoke an already
authorized active stream. Existing static server token has no TTL/rotation protocol;
no expiry semantics or active-stream revocation is claimed. Invalid server credentials
are rejected; there is no retry with credentials removed. Upstream ACK wait/retry
and channel reconnection policy remain unchanged. Reconnect tests establish real
TRANSIENT_FAILURE; very fast restart paths without that state are not newly qualified.

Remaining mandatory gates:

1. Full controlled CLI -> candidate Frontier -> actual Storm cluster -> SOCKS
   fixture -> archive acceptance is BLOCKED by unavailable usable Linux Docker
   runtime. Component/wire/conformance checks do not substitute for that full gate.
   No system installer or host setting was changed to bypass the blocker.
2. Independent read-only diff reviewer launch was rejected by the collaboration
   tool: "possible cybersecurity risk". No independent PASS is claimed or retry
   disguised under another task. Primary source review/provenance/testing completed;
   independent human/tool review remains required before closure/rollout.
3. No live deployment, real-Tor M2/M3 qualification, commit, push or merge.

Rollback rules from section13 still apply: preserve admission-aware schema and
authentication gate in a coordinated client/server pair, otherwise stop execution
with data retained. Never restore anonymous mutation or remove CFs/reseed quota.

## 15. Security RC checkpoint and qualification — 2026-11-04

User authorizes local commits only; no push, merge, PR or owner handoff dispatch.
Reviewed baseline853934e; source checkpoint9091f4f and tests checkpoint1f039df.
New quick RC gates:12/12 targeted JVM tests and4/4 freshly compiled real Frontier
conformance drivers PASS. DeepSeek's13 untracked audit files are preserved and
excluded from checkpoint; binary/runtime evidence remains ignored.
Completed RC triage, secondary review, roadmap and Stepan handoff are recorded
below; S1-01/full audit remain PARTIALLY VERIFIED.

### RC verification matrix and checkpoint identity

Base853934e57a17b827487b023e8e2c295375290b65. Local commits, no remote publication:

| Commit | Reviewable change |
| --- | --- |
| 9091f4ff5611fcb0878df2b884e73ad71a07e9b1 | Existing capability protects scheduling/lease calls; paired client adapters. |
| 1f039dfbe70bed232bc79606d3908bbc6d5d9304 | Actual RPC/client regressions and Windows argfile launcher. |
| 2aaa3cceddaaa47e9c91a851b24c1ab8ec98cd37 | Contract/AGENTS/security documentation checkpoint. |
| cc01871cc29f163258a30a2269340672112eeddf | Test-only fake-JDK executable naming; no changed assertions. |

This final RC documentation commit is identified by `git log -1 --format='%H %s'`;
its own hash cannot be embedded in its content. The final response and ignored
`.runtime/security-rc/checkpoint-commits.json` contain the complete commit list.

| Gate / current execution | Result and evidence under .runtime/security-rc |
| --- | --- |
| Quick JVM before commits | PASS12/12, checkpoint-jvm.log. |
| Full offline Maven verify | BUILD SUCCESS,124 run/123 PASS/1 SKIP/0 fail/error; maven-verify-offline.log. Includes client5, worker3, transport35 and archive/CLI regressions. |
| Freshly compiled Frontier | PASS4/4 on disposable actual RocksDB/gRPC, including separate-process rejected-state/replay checks; frontier/*.log. |
| M2 before fixture correction | Historical current-task input:103 PASS/3 FAIL/5 ERROR/2 SKIP, pre-existing before S1-01. |
| M2 after minimal fixture correction |105 PASS/2 FAIL/4 ERROR/2 SKIP, still full-suite FAIL; python-m2-after.log. Four RunnerContractTest tests PASS; m2-runner-fixture.log. |
| Source/static/provenance | Whitespace/UTF8/limited secret signatures clean; adapters normalize to official pinned source except identity/credential wiring. No schema/Tor/scheduler source changes. |
| Independent S1-01 reviewer | BLOCKED by prior collaboration-tool refusal; no bypass/reworded retry. Secondary self-review below is not independent. |
| Full controlled Storm/SOCKS G6 | BLOCKED: no usable verified Linux Docker runtime. vmcompute running alone is not a Docker engine; no Docker service/pipe found; WSL command reports not installed. |
| Actual public onion / real-Tor / deployment | NOT RUN, not authorized. |

One JVM skip is the existing non-Windows archive symlink gate. Full lint is not
warning-free: retained Kotlin/shade and14 client javac warnings are described in
section14. Limited pattern scanning is not full entropy/Gitleaks/CVE assurance.
Build reproducibility here means the stated pinned toolchain/commands and restored
cache pass offline, not a claim of byte-identical JARs or a fresh-clone single command.

### Secondary S1-01 review — limited independence

The same implementing lead performed this separate checklist review; product/M2
read-only researchers did not independently approve the authorization patch.
No new confirmed CRITICAL/HIGH regression caused by the patch was identified.

| Checklist / affected symbols | Result / concrete basis |
| --- | --- |
| AdmissionRpc.guard exact full method IDs | Command/PutURLs/GetURLs require token across every crawl; admin/unknown denied before dispatch. Fresh auth conformance PASS, including unprotected crawl and six admin methods. |
| Missing/wrong/unavailable credentials | Existing constant-time compare; local failure status sanitized without file contents/cause. New client tests show no anonymous stream handler/mutation/ACK. |
| Initial/recreated/reconnected worker channels | Every source-pinned newStub uses CallCredentials; file validated before open/prepare and reread for new call. Actual TF/restart5-test suite PASS. |
| Storage/lease/retry/replay invariants | Rejected calls leave scheduling metadata/reservation/quota unchanged after a new JVM; denied GetURLs does not steal lease. Authorized retry/terminal/replay and all4 conformance gates PASS. |
| Alternate mutation paths | All PutURLs crawl IDs protected; GetURLs treated as mutating. Six admin endpoints remain denied even with capability; direct internal trusted service calls are not a remote RPC bypass. |
| Logs and secrets | Guard returns empty trailers; FrontierCapability uses sanitized status. Explicit source review plus limited reachable-history signatures found no operational secret; known synthetic URL-negative fixtures remain. |
| Legacy compatibility / persistent schema / Tor | Paired rollout mandatory; tokenless clients intentionally denied. No change to admission schema, RocksDB/H2 ownership, scheduling or Tor/Compose policy. |

Remaining review findings, not S1-01 regressions:

| ID | Severity / confidence | Evidence / recommended action |
| --- | --- | --- |
| RC-R1 (S1-02) | HIGH conditional / DESIGN GAP | Same worker/operator capability, AdmissionLedger.open plus FrontierCapability. Keep trusted single-deployment scope; owner must approve any future role/session split. |
| RC-R2 (S1-03) | MEDIUM, HIGH on hostile network / DESIGN GAP | AdmissionClient/ManagedChannelUtil use plaintext; actual public reachability not established. Keep isolated deployment; separately review endpoint identity/TLS before widening trust. |
| RC-R3 | MEDIUM / confirmed lifecycle limitation | guard executes once per RPC; CallCredentials per stream establishment. No TTL or active-stream revocation. Do not promise file deletion revokes an existing stream; define/drain epochs before future rotation work. |
| RC-R4 | MEDIUM assurance gap | Full generated-topology G6 and independent review not completed. Run stated gates before full closure/rollout; component evidence remains scoped. |

### M2 triage: every original failure/error/skip

All ten names exist in pre-S1 Stage1 logs. Relevant entrypoint/runner/shell logic
matches base853934e. They are not caused by S1-01; that does not certify the
unreached security assertions. Windows mode bits also do not prove actual ACL leakage.

Reproduce each row from root:
`python -B -m unittest discover -s test/m2 -p FILE -k TEST -v`.
Class prefixes below give exact unittest identities; FILE is the indicated .py.

| FILE / exact CLASS.TEST | Original -> current | Cause / safe action and qualification |
| --- | --- | --- |
| test_bridge_security.py / BridgeSecurityTest.test_runtime_warnings_errors_transport_and_bootstrap_are_sanitized | FAIL -> FAIL | invoke:89 expects0600, Windows returns0666 (384!=438), before sanitization checks. Keep assertion; execute on Linux filesystem. No product confidentiality PASS inferred. |
| test_bridge_security.py / BridgeSecurityTest.test_tor_verify_errors_are_withheld_and_no_fallback_started | FAIL -> FAIL | Same permission failure before no-fallback/error-withholding assertions. No permission mock or new skip; Linux unit qualification required, no real Tor needed. |
| test_runner.py / RunnerContractTest.test_jdk_mismatch_rejected_before_docker | FAIL -> PASS | Dummy JDK omitted .exe and mock matched .name; actual runner correctly requests Windows executables. Fixture suffix/.stem fix only; original version-mismatch assertion preserved. Linux parity rerun still needed. |
| test_runner.py / RunnerContractTest.test_jdk25_selected_consistently | ERROR -> PASS | Same dummy executable naming prevented reaching mocked version/compile_release assertions. Same test-only fix; real installed JDK cannot repair an isolated dummy fixture. |
| test_same_container_readiness.py / SameContainerReadinessTest.test_default_variant_preserves_original_direct_storm_launch | ERROR -> ERROR | Native CreateProcess cannot launch /bin/bash. Expected storm-only call not reached; Linux/Bash gate required. |
| test_same_container_readiness.py / SameContainerReadinessTest.test_failed_series_prevents_crawler_start_without_retry | ERROR -> ERROR | Same launch error; expected exit7/java-only ordering not reached. Linux/Bash needed to verify no crawler after failed readiness. |
| test_same_container_readiness.py / SameContainerReadinessTest.test_invalid_opt_in_cannot_silently_start_crawler | ERROR -> ERROR | Same launch error before exit2/no-tool assertion. Linux/Bash qualification required. |
| test_same_container_readiness.py / SameContainerReadinessTest.test_opt_in_series_runs_before_storm_in_same_container | ERROR -> ERROR | Same launch error before java-then-storm order. POSIX executable/shebang/PATH semantics required; substituting Git Bash is not equivalent qualification. |
| test_onion_readiness.py / OnionReadinessTest.test_jdk_auth_offer_has_separate_phase_and_never_sends_http_after_auth_failure | SKIP -> SKIP | Existing Windows skip; network_probe uses pwd/getuid. Linux account + mocked SOCKS test required; no Tor/container needed for this unit gate. |
| test_runner.py / M1RegressionContractTest.test_unsafe_compose_project_names_are_rejected_before_any_docker_operation | SKIP -> SKIP | Existing POSIX fake-tool/Bash test; invalid-name exit2/no-tool assertions need Linux. Adjacent Windows source-regex parity is not a runtime substitute. |

Only test_runner.py dummy file suffix and mock .stem changed (4 lines replaced).
AST comparison proves existing assert/assert* nodes unchanged; no deadline, skip,
security assertion or acceptance condition was weakened. No production runner fix.

### Controlled E2E prerequisite and exact Linux follow-up

BLOCKED here: Docker CLI absent from normal PATH/standard install locations,
no Docker-named service/pipe discovered; WSL runtime reports not installed.
Existing vmcompute service was observed running but neither started nor modified.
Unsigned downloaded Docker CLI remains unused. Do not install/enable WSL/Docker,
change daemon/VPN/DNS/routes/firewall or remove volumes as part of this RC.

On a separately approved Linux host/workspace, prerequisites: verified JDK25,
Maven, Python3.12+, Bash/POSIX tools, Docker with Compose and permission for owned
loopback/containers, pinned stock2.4 service JAR (SHA256
ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df), and local images
python:3.12-slim, crawlercommons/url-frontier:2.4,
onionall-storm:3.1.0-jre25-cleanup2. The last image must come from the existing
hash-checked lifecycle build, not an unverified replacement. Native conformance
and full integration both use fresh unique DBs/volumes and retain failure evidence.

```sh
export JAVA_HOME=/absolute/path/to/verified-jdk25
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -C verify
bash scripts/cli/build-cli.sh
export URLFRONTIER_BASE_JAR=/absolute/path/to/verified-stock2.4-service.jar
bash frontier-server/build.sh --test
python3 -B -m unittest discover -s test/m2 -v
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 \
  python3 -B test/admission/local_integration.py
```

Use ONIONALL_OFFLINE=1 for the CLI build only with a complete known cache. Image
build, if separately authorized/needed: `bash scripts/patches/build-storm-lifecycle.sh`;
it is not executed here. Do not run the real-Tor acceptance runner for this gate.
Require actual packaged CLI/generated capability clients, denied anonymous calls,
stable quota/replay, leases/status/reconnect,503 retry, archive/hash/provenance and
unchanged network-isolation assertions. Inspect produced admission-local.json/logs;
exit0 alone is insufficient. Current G6 remains NOT RUN/BLOCKED.

### DeepSeek arbitration: six prioritized product findings

Input:12 documents/63 claimed findings in preserved untracked
docs/audits/non-security/. Its printed audit date is not used as proof of freshness.
Only these six were technically arbitrated; the remaining57 are UNVERIFIED here.
CONFIRMED below denotes a code-proven narrowed claim, not a new runtime experiment.

| Finding | Verified evidence / classification | Product impact; security risk | Priority / minimum action / regression gate |
| --- | --- | --- | --- |
| ARCH-01 | crawler.flux9-bolt vs CrawlExecution:188-226 generated5-bolt/capability topology. CONFIRMED divergence; REJECTED universal claim that generated product path was never demonstrated: dedicated test/admission/local_integration.py and historical M4.1 evidence exist. | HIGH assurance/maintenance; LOW conditional evidence-attribution risk, no proved DIRECT fallback. | P1: execution-mode/topology/client/server/transport/gate matrix, not forced unification. Fresh hardened generated-topology G6 plus separate synthetic controls. |
| ARCH-06 | AdmissionLedger:81-90 only admission session/reservation verbs; CrawlExecution.status:44-63 lacks scheduler progress. CONFIRMED app read-model gap; readonly server APIs already exist. | HIGH truthful lifecycle dependency; LOW/info risk, not an auth vulnerability. | P1: bounded readonly projection using existing reads where adequate, otherwise reviewed additive snapshot. Never use leasing GetURLs as status. Reads must not alter lease/queue/reservation/quota; expose UNKNOWN when terminal reason unavailable. |
| REC-06 | readQueueInfos:690-724 has half-open last-row debt, but constructor:170-174 forces recoveryQscan(true); legacy recovery/readQueueInfos branch unreachable. Already present at853934e. REJECTED active-candidate defect; dormant debt confirmed. | No reproduced current recovery corruption/security vulnerability. | P1 verification/P2 debt: retain authoritative rebuild; no stats_epoch/schema fix. Test>=3 mixed-state queues across two clean restarts and crash, exact authoritative membership/counters. |
| REC-07 | recoveryQscan(false):262-264 empty active branch is also bypassed; true path counts CF1 and validates CF0. REJECTED active-candidate defect. | No current zero-active failure established; no proved security issue. | Same recovery gate first. Do not blindly increment in both scans and double-count; no product edit justified by this claim. |
| REC-10 | CrawlExecution:73-75 default120s/max600s vs default120-minute retry, TTL:125, interval:206. CONFIRMED default consumer-lifetime mismatch, REJECTED universal retry impossibility: short override1min and historical503->200 fixture exist. | HIGH operational UX/liveness; LOW availability security risk. Persisted retry is not lost scheduling. | P1: define bounded PAUSED/next_attempt_at/resume contract; owner chooses later lifetime policy. Default retry survives exit/restart, later same-plan consumer honors due date, no false COMPLETE/new quota; short503->200 gate. |
| DATA-06 | ArchiveStore.bound:91-94 and pages/search/links/export LIMIT lack cursor. CONFIRMED targeted pagination/export gap; REJECTED "every read" and current valid page-link truncation: history is unbounded, snapshot input<=100 links. | MEDIUM complete retrieval; LOW security risk, existing bounds protective. | P1 before complete graph/export: bounded compatible cursor+continuation/watermark. >=250 pages across>=3 plans, >300 edges across valid snapshots; complete ordered walk without omissions/duplicates/concurrent append drift. |

WP-03 must not infer successful DONE/FAILED from CF1 terminal rows: terminal0
deletes scheduling data, and empty CF0 pointer means UNSCHEDULED, not success.
Use catalog/provenance or explicit UNKNOWN, never fabricated outcomes.

Dependency correction: **WP-01 -> narrowed current-path WP-08 verification gate ->
WP-03 read model -> lifecycle**. WP-11 evidence/mode documentation starts alongside
WP-01 and precedes topology retirement. WP-08 code/schema fixes are not a hard
prerequisite after the active-defect claims were rejected. No M5-M9 implementation
or DeepSeek document modification in this task.

### Unified proposed roadmap / measurable acceptance

These are proposed delivery labels, not a retroactive renaming/acceptance of the
historical M0-M8 milestones. M0/M1 scoped acceptance and blocked real-Tor M2/M3 stay.

| Stage | Result / dependencies | Acceptance tests / risks / implemented vs remaining |
| --- | --- | --- |
| RC0 Security/reproducible checkpoint | Local reviewed code/test/doc commits and exact paired-client contract. | Existing local JVM/server gates PASS; G6/independent review still BLOCKED, not full security/release acceptance. No known confirmed critical regression from patch; shared capability/plaintext/revocation risks remain. |
| M5 Operational Closure | WP-01 unified app/Python/server verification, pinned prerequisites; early WP-11 mode/evidence matrix and reviewed deployable fixture stack; narrowed WP-08 counter qualification. Depends on RC0 owner review. | Fresh supported checkout gives explicit PASS/FAIL/PARTIAL; no silently skipped server gate. >=3-queue repeated/crash recovery gate passes; each topology's evidence scope explicit. Already have builds/conformance/scripts; unified wrapper/CI/product launch and Linux qualification remain. No premature topology deletion/schema fix. |
| M6 Lifecycle/status/recovery | WP-03 bounded non-mutating scheduler observation after recovery gate, then truthful bounded-run PAUSED/UNKNOWN/next-attempt and failure provenance. | Status changes no scheduling/lease/quota; no false COMPLETE; default retry persists past worker exit and later resume consumes no new reservation. Already durable scheduling/ownership; progress/completion/owner retry policy remain. Avoid H2 second queue and unsupported terminal inference. |
| M7 Archive/Search/Graph API | Complete pagination/export before higher API; reviewed outcome typing/read ownership if needed. Depends on M6 truth semantics and existing catalog invariants. | Multi-plan/edge cursor walk, stable continuation/concurrent append/export; hash/provenance and bounded memory maintained. Existing schema2 snapshots/edges/local search remain; complete retrieval and service contract missing. Any schema changes need separate design/rollback. |
| M8 Graph Explorer/user workflow | Read-only graph/service/UI over M6/M7 truth; explicit approved crawl actions. | Pending/rejected/failed/UNKNOWN shown honestly; graph reads never admit/lease; active HTML isolated/sanitized, auth/CSRF/CSP reviewed. Data/kernel exists; UI not implemented. No UI scheduler or hidden approval. |
| M9 Product qualification/release | Owner-authorized platform/recovery/backup/restore/retention/soak and separate controlled real-Tor qualification. Depends on prior accepted contracts and artifact/advisory review. | Measured restore and repeated platform gates, unchanged isolation, open risks resolved/accepted explicitly. No production/release readiness claim now; deployment/owner visual/hardware and real-Tor remain separate. |

Disagreements with DeepSeek: forced current recovery was overlooked (REC06/07);
generated-topology historical evidence was omitted; existing readonly RPCs do not
equal a complete application progress model; terminal CF1 inference is invalid;
retry lifetime mismatch is not universal/permanent retry failure; pagination claim
overstates every API/page links;250-page tests cannot use one max100-page plan;
stats_epoch/schema work and large topology/doc retirement are not justified RC
prerequisites. Source-only labels/63-findings count are not runtime acceptance or
permission to implement all recommendations.

### Short Stepan handoff package (prepared, not sent)

1. Base853934e; four checkpoint hashes above plus the final RC documentation commit
   resolved from current HEAD. Optional local review patch is under ignored
   .runtime/security-rc/; no push or transfer occurred. DeepSeek audit remains local,
   protected/untracked and is not silently included in the patch/commits.
2. Fixed: capability-free scheduling/lease mutation; matching durable credentials;
   Windows long-classpath test launcher; fake-JDK fixture naming. No schema/Tor change.
3. Fresh proof: offline Maven123 PASS/1 SKIP, Frontier4/4, quick auth/client12/12;
   M2 now105 PASS but2 FAIL/4 ERROR/2 SKIP. Existing component/wire evidence scoped.
4. Blocked: Linux unit qualification, full generated Storm/SOCKS G6, independent
   authorization review; no real Tor/product deployment/installed thin-CLI claim.
5. Breaking: old tokenless workers cannot PutURLs/GetURLs. Accept only paired
   client/server rollout; no fallback or stock4-CF rollback.
6. Compare locally: `git log --oneline origin/migration/java25-storm31..security/comprehensive-hardening`
   and `git diff --stat origin/migration/java25-storm31...security/comprehensive-hardening`;
   use `git diff` without --stat for code. Source/main refs were not changed.
7. Review/accept by checking per-commit scope and executing gates above. Reject by
   leaving the security branch unmerged with concrete feedback; no reset/history rewrite
   needed. Nothing is remotely visible until separately authorized publication.
8. Recommended next milestone: bounded M5 verification/mode/qualification gate,
   before read-model/lifecycle or UI implementation. Do not implement WP-08 blindly.
9. Owner-only decisions: merge/publication, runtime installation/deployment, topology
   retirement, role/TLS/stream-revocation policy, bounded-vs-continuous consumer model,
   schema changes and later real-Tor/product acceptance.
10. **Ready for local review and a draft PR once owner authorizes publication;
    not merge/release/full-security-ready.** No PR created and no message sent to Stepan.


## M5 REC-C - approved archive ownership containment (2026-11-04)

REC-09 pre-fix CONFIRMED Windows JVM evidence is retained in checkpoint1955ef7
and ignored worker-crash-characterization/case-* proof logs. Product HIGH conditional
availability/overlap; security LOW/conditional MEDIUM with crash plus authorized
restart. Not a new unauthenticated RPC mutation, not S1-01 closure.

WorkerOwnership.Preparation/claim/read/stopped/publish, CrawlWorker.main/OwnedChild,
CrawlExecution.start and WorkerObservation.read implement the approved local guard.
PREPARING is published before resources/admission; exact-parent UUID handoff precedes
child creation, then pinned scope/PID/startInstant. Released OS lock or absent parent
alone never proves cleanup. RUNNING/LAUNCHING/PREPARING/UNKNOWN after an unassisted
crash blocks start, even when the test later terminates its own surviving child.
Malformed/future/foreign/legacy markers are preserved and denied, without signaling
unknown processes. Status uses the archive marker instead of a selectable work path.
ArchiveStore.hasExecutionHistory is read-only and prevents a used legacy catalog
without its old lock from being treated as fresh. Existing authority guards stay
in Frontier; no protobuf, quota/reservation, schema or Tor policy change.

Regression conditions: normal exit/restart, bounded owner deadline, CLI force-kill,
wrapper force-kill/live listener, child kill, PREPARING/LAUNCHING crash, PID reuse,
invalid handoff, stale/unknown/foreign/future marker, changed work/plan and legacy
catalog/lock. Actual Windows Process.destroy also leaves an orphan; its regression
requires fail-closed BLOCKED, not an unsupported graceful-cleanup claim.

Compatibility: additive archive sidecar crawl-worker.lock.owner-v1.json. Legacy
whole-tree stop cannot be retrospectively proved using absent PIDs; no automatic
migration is shipped. Preserve data and request an operational stopped-proof review.
Older binaries ignore the marker and lose REC-C containment; rollback requires
proved stopped processes and preserved evidence. Not OS containment; detached
unobserved descendants, full Storm/Linux process-tree qualification and power-loss
filesystem guarantees remain OPEN. No host/network/native component change.
Current executed results are recorded in m5-execution-ledger.md. Full security
audit and S1-01 remain PARTIALLY VERIFIED / NOT CLOSED.


REC-C follow-up also pins CLI-owned wrapper birth at launch: CrawlExecution.stop
rejects unknown/mismatching identities and never inspects a dead wrapper's PID tree.
Signals use the same identified ProcessHandle, with birth recheck. Child readiness
transfers its pinned birth instead of late PID lookup. Tests simulate mismatching
birth on an owned fixture, not mass OS PID recycling or signaling foreign processes.
Only the wrapper publishes cleanup proof; CLI failure leaves marker fail-closed.
No change to RPC/capability policy; full Storm forced-owner gate remains OPEN.


## M5 archive-fault ACK arbitration (component CONFIRMED; remediation design pending)

ParserArchiveAckCharacterizationTest: real installed JSoupParserBolt.prepare/execute
plus ArchiveParseFilter, owned corrupted immutable blob, actual H2. Success commits
before emission/ACK; failure yields ERROR then ACK, no fail, no FETCHED/document or
snapshot. This conflicts with03_REC failure scenario4's parent-fail requirement.
Affected ArchiveParseFilter.filter, dependency JSoupParserBolt.handleException,
CrawlTopology parser binding, existing CapabilityArchiveStatusUpdaterBolt.store.
Product severity MEDIUM (terminal treatment/recovery of archive write faults);
security LOW conditional on storage/processing fault, no unauthorized mutation or
false snapshot proved. Confidence HIGH for installed component; full Storm
child-reservation/failure ACK tree still NOT RUN. No S1-01 status upgrade.

Owner chose parent FAIL with bounded retry using existing Storm/Frontier, subject
to separate minimal design approval. ADR0004 records adapter, shared existing
error budget/backoff, audit-before-write, lost-write/lease and rollback gates.
No parser/status/Frontier remediation implemented. verify keeps this required
acceptance NOT RUN; positive defect characterization must not make audit green.


REC-C final actual Storm diagnostic run41896563641 atcee2d84: normal core gates
PASS, actual prepared Storm child survived pinned wrapper kill (S state), lock
released, attempted start returned ownership BLOCKED with resources/state/quota
unchanged. After TTL the SAME birth-pinned child was state Z, while Java
ProcessHandle still reported alive; marker phase remained RUNNING, no cleanup proof.
[Linux proc state documentation](https://docs.kernel.org/filesystems/proc.html)
identifies Z as zombie. Observed reaping/Java-liveness limit is CONFIRMED in this
fixture, not proof of all descendants stopped. No reaper/containment/OS change or
forced metadata migration applied. Full product/crash gate remains FAIL; recovery
stays BLOCKED under the approved contract. All owned containers/network removed,
volume retained, host baseline unchanged; sanitized artifact SHA256
5926a087afcddba5060660f18ce74417caf9d03f99d9768aa6f113bf84888767 retained.
