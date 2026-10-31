# M2 hardening and portability audit — 2026-10-31

This audit accompanies bridge integration. No new commit is authorized by the
latest audit request; preserve the working changes for review.

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
