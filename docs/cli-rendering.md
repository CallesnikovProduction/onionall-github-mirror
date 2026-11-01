# Persistent interactive header — 2026-11-02

Presentation-only change on migration/java25-storm31, baseline7f7932e.
TorProtocol, policy, SOCKS settings, crawler and functional command contracts
were not modified. No new dependencies or terminal framework.

## Rendering contract

Brand owns single-column ASCII geometry and a custom three-row glyph wordmark.
Three silhouettes were compared: teardrop was too egg-like, squat bulb too much
like a pumpkin; the selected layered/tapered bulb has multiple shoots, brows,
large eyes and smile. ASCII-only geometry is deliberate: it is also the reliable
fallback for terminals where Unicode cell widths differ. No emojis or font needs.
Wide100+ uses a full bulb/one-line wordmark;70–99 a compact bulb/stacked words;
below70 simplified branding. Short terminals reduce header height to leave room
for content. Truecolor uses the requested five violet shades;256/basic ANSI
fallbacks use the same geometry. Caption remains searchable as plain text.

InteractiveSession owns an ANSI viewport only when the existing color/TTY gate
permits it. Header is rendered once on entry, then only on dimension changes.
One initial visible-screen clear; no alternate screen or scrollback erase.
Normal frames update only changed body lines via CUP/EL. Scrolling margins protect
the header during native console input. Result/status summaries fit the content
region; long fields are clipped and non-ASCII data escaped to preserve cell width.
Direct commands retain full values and original output formatting.

Menu actions replace the body; Enter returns to the main menu. Fetch feedback is
indeterminate elapsed time, not fabricated byte/percentage progress. Native Java
Console continues to own line input. Read-only stty size polling detects resize;
if unavailable, COLUMNS/LINES defaults are used. Subprocesses/streams are closed.
Shutdown and Ctrl+C reset scrolling margins and show the cursor, idempotently.

Compatibility choice: NO_COLOR/--no-color/dumb terminal preserve the existing
plain stream with **no escape sequences**. The header prints once, but cannot
physically stay pinned without cursor controls; subsequent plain text may scroll.
JSON/pipes never enter the interactive renderer. This explicit fallback preserves
existing no-control-output tests rather than silently changing NO_COLOR behavior.

## Actual verification

- Java25 offline Maven verify PASS:69 tests, including3 new renderer unit tests.
- Entire CLI packaged suite PASS:14 tests, including5 new real PTY scenarios.
- Real PTY80/120/50 navigation through help, status, missing-file errors and back:
  caption once, no repeated screen clear, stationary header.
- Real ioctl resize120→80→50: reflow exactly on size change, no redundant repaint.
- CLI→actual TorProtocol→owned loopback SOCKS fixture: progress stays in body;
  SIGINT exit130 resets margins/cursor and leaves no complete output.
- Completed controlled fetch at120×24: HTTP200, title, all result lines and return
  prompt simultaneously visible; header identical after return to menu.
- Truecolor/256/basic palettes, ASCII cell bounds and NO_COLOR/unit geometry PASS.
- Original JSON/non-TTY/parser/installation/transport tests remain green.
- Installed updated CLI at ~/.local/bin/onionall; real installed PTY navigation PASS.
- Shell/diff whitespace checks PASS. No public Tor fetch, Docker or host-network
  modification for this presentation task. M2/M3 acceptance remains BLOCKED.

Evidence stays ignored in .runtime/cli: render-build.log,
render-delivery-tests.log, render-installed-80.txt, render-{80,120,50}.txt,
render-fetch-complete-120x24.txt, progress/resize captures and original .ansi streams.
The .txt files are replayed real PTY streams (documented ANSI subset), not invented
screenshots. They were inspected for overlap/clipping; no native GUI screenshot
was taken. Prior69-test caption failures were corrected before delivery.

Reproduce with JAVA_HOME selecting JDK25:

```sh
ONIONALL_OFFLINE=1 ./scripts/build-cli.sh
python3 -B -m unittest discover -s test/cli -p 'test*.py'
./scripts/install-cli.sh --no-build
onionall
```
