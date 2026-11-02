# Persistent interactive header — 2026-11-02

Presentation-only change on migration/java25-storm31, baseline7f7932e.
TorProtocol, policy, SOCKS settings, crawler and functional command contracts
were not modified. No new dependencies or terminal framework.

## Rendering contract

Brand owns terminal layout and a custom ASCII glyph wordmark (see visual revision below).
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

## Filled pseudo-3D revision

After visual feedback, Brand alone now uses a filled shaded bulb, upper-left
highlights and a dark right edge. Five-row italic letter faces have a one-cell
offset extrusion; the wordmark stacks automatically when a full line cannot fit.
The product name remains ONIONALL BRAWLER. No continuous animation is added to
the stationary header. UTF-8 locales outside CJK use common single-cell block
glyphs for solid faces/shadows; ONIONALL_ASCII=1 forces the original ASCII cell
geometry. Missing UTF-8 locale metadata and NO_COLOR also retain ASCII. This is
pseudo-3D shading, not a 3D engine, bitmap protocol or font-specific icon.

Java25 Maven verify70 PASS; CLI/PTy suite14 PASS (wide/standard/narrow, resize,
progress/complete fetch, cancel, NO_COLOR/JSON, installation). Updated CLI installed.
No public request or Tor/runtime policy changes. Geometry/ASCII fallback equality
is covered by a new unit test. Logs: .runtime/cli/depth-build.log, depth-tests.log.
Actual PTY replays: render-80/120/50.txt and .ansi. The ignored
brand-depth-preview.png is a color replay of the real80-column stream using a
monospace font, not a native desktop screenshot. Downloaded/source images are not
used by the CLI, and no generated image enters Git.

## Reference-inspired surface mascot

Mascot now owns an original vector drawing inspired by the supplied purple onion
reference: swept shoots, rounded layered bulb, light face, dark wedge eyes and a
curved smile. It is rasterized off-screen by JDK java.desktop/Java2D in explicit
headless mode; no X11 connection, fonts, windows, image protocol or third-party
dependency. Standard JDK/JRE25 includes this module; a custom jlink runtime must
include java.desktop. The user's reference bitmap is not copied into the repository.

UTF-8 color terminals draw opaque colour surfaces using foreground/background
▀/▄ cells with transparent surrounding cells. Every row resets SGR, preventing
background bleed. Monochrome/ONIONALL_ASCII uses density characters from the same
shape (no slash outline). Wordmark glyphs/extrusion and transport remain unchanged.
ANSI is excluded from cell-width calculations. Mascot size follows the available
height and leaves room for the existing wordmark/menu/results. Resolution is
necessarily limited by terminal cells; tall/larger terminals provide more detail.

Actual verification:72 Maven tests PASS,14 CLI/PTY tests PASS. New tests check
transparent corners, light face/dark eyes, exact half-cell width and background
reset. First geometry run caught overflow at70columns; available mascot width
is now constrained by the unchanged wordmark. Explicit headless rendering avoids
a failed DISPLAY connection even on desktop hosts. Updated CLI installed and its
actual interactive PTY verified. No public onion GET needed for this visual pass.

Evidence: .runtime/cli/mascot-build.log, mascot-tests.log,
render-mascot-installed-80.ansi/.txt. mascot-reference-preview.png is a color replay
of the real80-column stream with proper foreground/background half-cell filling;
it is not a native desktop screenshot. No preview image is tracked in Git.

## Clean interactive exit — 2026-11-02

Supported ANSI TTY sessions now own one alternate screen (1049 entry/exit).
The previous primary screen, cursor position and scrollback remain intact.
Cleanup resets styling/margins/cursor visibility before leaving the alternate
buffer; normal close and the shutdown hook share the same idempotent close.
No global clear/scrollback erase is issued. Plain/no-color and unsupported TERM
fallbacks keep linear output; noninteractive commands are unchanged. PTY replay
checks primary-screen restoration on Quit, EOF, SIGINT and handled input errors.
