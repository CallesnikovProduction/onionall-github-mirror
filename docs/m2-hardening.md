# M2 hardening and portability audit — 2026-10-31

This audit accompanies bridge integration. No new commit is authorized by the
latest audit request; preserve the working changes for review.

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
