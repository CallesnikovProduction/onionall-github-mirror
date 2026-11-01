# Repository hygiene checkpoint — 2026-11-01

Root: /home/callesnikov/Projects/onionall-brawler.
Branch: migration/java25-storm31; starting HEAD e769af3. Main remains97b9da4.
Initial worktree was clean and synchronized with origin. No unfinished merge,
rebase, cherry-pick or revert markers were present. Entire doctor reports valid
Git/Codex hooks and no stuck sessions. No history rewrite or main merge.

## Inventory and disposition

Metadata inventory excluded .git, followed no symlink and stayed within this
worktree. Initially .runtime contained23914 files /5351051835 bytes and target
38 files /105149531 bytes. These are local sizes, not Git content. No symlink
resolved outside the worktree. Text inspection covered6166 files, excluded
private .secrets contents, binary bodies and individual files above10MB.

| Class | Disposition |
|---|---|
| KEEP | Sources, fixtures, test harnesses, documentation, Entire settings/hooks; M2/M3 failure and acceptance evidence; RocksDB/Tor/onion state and private bridge config |
| REGENERABLE, retained | Main Maven cache and verified JDK25/offline archive needed for offline reproducibility; target rebuilt by clean verify; generated Compose/classpaths remain ignored evidence |
| SUSPICIOUS, retained | Three Maven download temporary files: ownership/current use not established; ignored IDE settings are local user work |
| REMOVE | 24 ignored Python bytecode files in scripts/test/docker __pycache__;38 unused foreign Maven-cache files,135767834 bytes, under .runtime/maven-linux-repository/com/simbirsoft/maketalents |

Total deleted:62 files. No tracked source or fixture was deleted. No Docker
containers/volumes were managed. No Tor keys, state or evidence were removed.
No mass git clean, host networking, VPN, DNS, routes, firewall or proxy changes.

## Foreign project boundary

MakeTalents status: **NO_CONTAMINATION_FOUND in active build/runtime or original
tracked files**. Actual matches were19 text metadata/POM files in an unused
ignored Maven-cache subtree (38 files total including binaries), not executable
dependencies. That subtree was removed only after dependency:tree succeeded
without its coordinates; subsequent offline clean verify passed without it.
The rebuilt shaded JAR contains no maketalents or com/simbirsoft class entries.
No foreign-project symlinks or references were found in scripts, active
configuration, local classpath files or IDE text inspected. The foreign repository
was neither accessed nor changed. This report intentionally names the audit target;
it does not introduce a build dependency. Processes of the foreign project were
not inspected or stopped; successful clean compilation establishes build independence.

## Ignore and secret checks

Added *.class, *.hprof and *.jfr outside existing ignored build/runtime directories.
git check-ignore confirms environment files, .secrets bridges, runtime evidence,
bytecode, heap dumps and recordings are excluded. No forbidden tracked runtime,
key, JAR/class or heap files were found. Current tracked contents were checked for
private-key blocks, AWS/GitHub token patterns and concrete private obfs4 bridge
lines without printing values; no matches. Targeted all-ref history path checks
for .runtime/.secrets/private-key/heap paths returned no entries. This is not an
exhaustive historical secret scanner or proof that arbitrary credentials cannot
exist. No demonstrated credential exposure or rotation requirement was found.

## Verification after removal

- Java25 offline Maven dependency:tree PASS, no foreign coordinates.
- Java25 offline Maven clean verify PASS:48 JVM tests,0 failures/errors/skips.
- Python M1:31 PASS/1 optional smoke SKIP; M2:67 PASS; M3:2 PASS.
- M0/M1/M2 Compose config validation PASS, required run variables supplied;
  no services deployed. All scripts/*.sh pass bash -n. Native Windows NOT RUN.
- git diff --check PASS. Only .gitignore and this report enter the checkpoint.
- Logs remain ignored: .runtime/platform-migration/hygiene-*.log.

No runtime behavior changed, so live M1/M3 integrations were not repeated.
Previous evidence remains valid for its recorded versions; this pass does not
claim a fresh Tor or Docker runtime result. AGENTS.md needs no new architecture
or contract entry for generated-file cleanup.

## Manual controlled Tor E2E readiness

Repository/build hygiene prerequisites pass. Live prerequisites remain unverified:
M2 Tor/recovery, M3 onion A→B→C, Tor security delivery/update and durable global
admission remain BLOCKED. Tor remains0.4.9.11, candidate not installed. Before
manual E2E, validate the existing bounded private-bridge preflight and owned
fixture readiness using documented harnesses. Do not claim production readiness,
use DIRECT fallback, delete volumes or change host networking to obtain PASS.
