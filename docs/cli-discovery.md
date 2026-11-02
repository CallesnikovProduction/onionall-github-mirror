# Offline CLI discovery and terminal polish

## Boundary

`onionall discover <html-file> --base-url <approved-source-url> [--json]
[--export <new-report.jsonl>]` reads an existing file. It never constructs a
transport, resolver, crawler, Frontier client or queue. No recursion, fetch,
robots request, script execution, refresh or browser launch occurs. This is not
M2/M3 acceptance. TorProtocol and its security configuration are unchanged.

The explicit base must pass the current Ahmia-only policy. HTML `<base>` cannot
change it. JSoup is the same parser/charset mechanism used by `inspect`; anchors
with `href` are processed in document order. We do not bootstrap a Storm topology
just to inspect saved HTML. The session planner's ADMITTED/time-dependent output
is unsuitable for a deterministic report: offline discovery shares TorPolicy and
the planner's conservative metadata-only extension rule instead.

## Bounds and identity

- Input: regular saved file, at most 4 MiB; URLs: at most 2,048 characters.
- At most 1,000 anchors processed. `links_found` counts all anchors in the bounded
  input, `processed` and `truncated` disclose the cap. No silent partial admission.
- URI resolution handles relative/protocol-relative references. Empty and
  fragment-only links identify the source; query-only references preserve its
  path (avoiding JDK URI.resolve's path replacement).
- HTTP(S) schemes and hosts are lowercased; default ports removed; empty path is
  `/`; fragments removed. Path case, query order and percent escapes preserved.
  Unsafe authority, nondefault port, malformed URI, non-HTTP scheme, invalid v3
  checksum/version and paths rejected by TorPolicy are rejected.
- Structural inspection of an unknown onion invokes the same TorPolicy grammar
  with a temporary validation-only host; original immutable scope alone decides
  `in_scope`. No fetching/admission API receives the inspection policy.
- Distinct valid canonical targets count as `unique`; duplicate occurrences count
  separately. `in_scope`/`out_of_scope` count unique valid targets; invalid
  occurrences count as `rejected`. Thus processed = unique + duplicates + rejected,
  and unique = in_scope + out_of_scope. Reason counts include all processed links.

Each edge carries source URL, input reference, normalized target (or null),
URI syntax validity, separate onion-v3 validity, scope, URL-only eligibility, admission=false
and reason. References containing `@` are conservatively redacted to avoid
persisting userinfo credentials. Reports can still contain sensitive query
strings from input: keep them in ignored `.runtime/`, not Git. No content-derived
ANSI/control codes execute in human/JSON output.

`eligible_for_admission` means a unique URL passed scope and extension gates,
not that robots was checked, a durable budget was reserved, or enqueue is
available. Duplicates and binary/archive/database links are ineligible. Empty
extension or HTML suffix does not prove future response MIME; the transport's
existing bounded HTML gate still applies to any separately authorized fetch.

## Export and Frontier

JSON and JSONL preserve input order and stable reason ordering; no wall-clock
field or random ID is injected. Repeating identical inputs in the same location
produces identical output. JSONL contains one edge per processed occurrence.
Export is explicit, atomic and no-clobber via existing HtmlFiles.save; it cannot
replace the source or an existing report. No parallel queue is introduced.

Frontier admission is intentionally unavailable. ADR0002 documents missing
atomic durable global reservation and ambiguous timeout outcomes; existing
local-test Storm discovery cannot safely stand in for production admission.
Optional crawling is therefore not enabled by this CLI pass.

## Terminal

The mascot, 3D wordmark and pinned header remain. The footer has three blank
rows, only `Tor-native fetch engine`, and an `=` separator. Menu shortcuts use
parentheses with semantic purple/green/red accents. Prompt uses a one-cell arrow
on supported UTF-8 locales, ASCII otherwise. A blank before the prompt is added
when viewport height permits. Status uses aligned labels and explicitly reports
TCP-only reachability and Tor/onion NOT_TESTED. JSON stays machine-readable;
NO_COLOR, pipes and non-TTY output remain free of ANSI. Interactive status is
compact to retain all fields and the return prompt in small viewports.

## Reproduction

Select Java25 with JAVA_HOME. Run:

```sh
ONIONALL_OFFLINE=1 bash scripts/build-cli.sh
python3 -B -m unittest discover -s test/cli
bash scripts/install-cli.sh --no-build
onionall discover ./saved.html --base-url http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion/ --json
```

The launcher needs Java, not Maven, at runtime. Unit tests inject a transport
that throws on use; packaged integration uses a controlled saved fixture and
records zero SOCKS connections, deterministic output and export. This evidence
plus the offline call graph demonstrates this command's network-free boundary;
it is not a system-wide DNS-leak claim or full distributed crawl proof. PTY
tests exercise navigation, resizing, progress, cancellation and result visibility.

## Executed verification — 2026-11-02

- Java25 offline Maven verify plus packaged distribution: **80 tests pass**.
  Includes five discovery and two presentation tests, existing Tor security,
  lifecycle, planner and CLI regressions. Initial compilation exposed the Kotlin
  checked-exception declaration boundary; a Java throws adapter preserves its
  real IOException behavior without changing TorPolicy. Initial new test setup
  bypassed Picocli defaults; it now supplies an explicit policy configuration.
- Packaged CLI/installation/PTY suite: **15 tests pass** (14 existing plus offline
  discovery fixture). 80/120/50-column navigation, resize, cancellation, no-color,
  status, completed fetch and controlled SOCKS routing regressions executed.
- Installed CLI discover: exit0, three links, one unique target, one duplicate,
  one rejected scheme. JSON parses; stderr empty. Executed with a PATH containing
  only bash/dirname/readlink, with Java selected by JAVA_HOME; no Maven present.
  The first reduced-PATH attempt omitted readlink and exited127; adding that
  existing launcher prerequisite fixed the verification environment.
- Entire doctor: Git hooks OK; Codex hooks installed/approved; no stuck sessions.
- No public requests, M2/full gate, Docker or host networking operations.
  Single-page historical Tor success does not close M2/M3 blockers.

Ignored local records: `.runtime/cli-discovery/installed.json` and fixture.
No runtime pages, reports, caches, bridge lines or service keys are delivery files.
