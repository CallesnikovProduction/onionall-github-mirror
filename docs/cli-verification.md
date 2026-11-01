# CLI delivery — 2026-11-02

Branch migration/java25-storm31, baseline85f3369. Existing uncommitted OnionSmoke
files were preserved as the prerequisite; no TorProtocol/TorPolicy/Storm topology
behavior was modified. CLI uses Picocli4.7.7 already available in the local cache.
No JLine/Jansi/TUI stack was added. Brand and bounded HTML/file handling are
separate classes; a narrow transport function allows deterministic command tests.

## Actual implementation boundary

Commands: fetch, inspect, status; a real-console numbered menu on no arguments.
JSON/non-TTY never animate; NO_COLOR/--no-color disable colors. Purple branding
has wide/compact ASCII variants without font requirements. Spinner measures
elapsed time only: TorProtocol does not expose streaming-byte progress.
Status reports local TCP readiness separately from NOT_TESTED Tor/onion readiness.

Fetch keeps the Ahmia-only allowlist and existing TorProtocol's hostname-in-SOCKS,
no-DIRECT, no automatic redirects/retries, finite call deadline, MIME/size gates.
It is a user-requested single fetch diagnostic, not robots-aware crawling. No
bulk/discovery/frontier commands are exposed. Offline inspect does not follow
links, reads at most4MiB and reports JSoup-detected charset, raw href statistics,
title and description. Terminal control characters are sanitized when rendered.

Output uses complete same-directory temporary files. Non-overwrite publication
uses a hard link (atomic create-if-absent); overwrite uses atomic replace. Parent
filesystems must support the operation; failure leaves no falsely completed file.
Default download filename is ahmia.html, even for another path on the approved host.
All options and exit codes are described in README. No private bridges/config are read.

## Executed checks

- Java25 offline Maven verify:66 tests PASS (16 new CLI,2 existing smoke,48 prior).
- Packaged CLI suite:9 PASS: real TorProtocol over local SOCKS fixture, ATYP3
  exact onion hostname/port80, HTML/title/save;302 no follow/save; refused SOCKS
  preserves cause and creates no file; scope rejection before socket; SIGINT
  exit130 without a complete file; actual narrow/color and NO_COLOR PTY menus;
  install/update/uninstall and unrelated-file/symlink preservation.
- Python M1:31 PASS/1 optional SKIP; M2:67 PASS; M3:2 PASS.
- Actual Linux user installation: ~/.local/bin/onionall points to
  ~/.local/share/onionall/cli/bin/onionall. Help/version/offline inspect/status PASS.
  Independent install tests also used XDG paths containing spaces. Java17 launcher
  rejection exit69 was executed. No Maven is invoked by installed commands.
- Thin distribution56MiB: required Storm API/shaded dependencies, StormCrawler
  core and HTTP/parsing libraries; no Flux/Tika/frontier/gRPC/test libraries.
- Shell syntax and whitespace checks PASS. Main remains unchanged. No host
  VPN/DNS/routes/firewall/proxy, Docker daemon, volumes or Tor secrets touched.

## Real installed CLI fetch

One network request to the specified Ahmia root after local prerequisites passed:

```json
{"http_status":200,"bytes":4735,"elapsed_seconds":5.554051019,"title":"Ahmia — Search Tor Hidden Services","saved":"/home/callesnikov/Projects/onionall-brawler/.runtime/cli/ahmia-live.html","passed":true}
```

Installed command used --json and explicit --output; result exit0, empty stderr.
A previous installed invocation failed before network with missing commons-cli;
controlled fixtures then found missing commons-lang3. Both packaging omissions
were fixed in the explicit dependency list (including core Commons utilities),
and all package tests reran successfully from the normal build script. Earlier
failure diagnostics are preserved, not relabeled as Tor failures or erased.

Evidence is ignored in .runtime/cli: build-delivery.log, distribution-final.log,
install-checks.log, python-*.log, help/version/inspect/status captures, live.json,
live.stderr, ahmia-live.html and distinct failure logs. HTML must never enter Git.

Reproduce with JDK25 selected:

```sh
ONIONALL_OFFLINE=1 ./scripts/build-cli.sh
python3 -B -m unittest discover -s test/cli -p 'test*.py'
./scripts/install-cli.sh --no-build
onionall --help
```

NO_COLOR and redirected output were verified; this is Linux testing, not a claim
of Windows packaging. Full M1 Docker/M2/M3 Tor gates were not rerun for this UI
layer. SOCKS fixture ATYP3 plus existing no-DIRECT tests are scoped evidence,
not system-wide DNS-leak proof. M2 recovery, M3 real multi-service crawl, atomic
global admission and Tor security delivery remain BLOCKED. No new supervisor.
