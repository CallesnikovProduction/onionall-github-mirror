# M3 autonomous discovery — offline implementation, NOT accepted

Baseline f0d1ce11940d44a66cd5470ecfef4fa8006a31ec is recorded in Entire.
M2 is still BLOCKED; no new Tor experiment or M3 acceptance claim is made.

## Existing flow and minimal addition

Stock JSoupParserBolt already extracts/resolves HTML outlinks, invokes URLFilters
and ParseFilters, then emits discovered tuples into the existing status stream
and URLFrontier updater. M3 reuses this flow and TorPolicy; it does not implement
another HTTP client, queue, frontier or recovery controller.

DiscoveryPlanner accepts raw references from one validated parent. It resolves
relative URLs, enforces the existing strict v3/scheme/host allowlist, discards
fragments, deduplicates canonical URLs within each document and stops admitting new targets at explicit
per-session depth/page/link budgets. The source counts against the page budget.
Explicitly preapprove A/B/C: discovering an unknown onion never expands TorPolicy.
The bounded set belongs to one parser instance; it is NOT durable global state.

DiscoveryParseFilter applies the planner to the stock parser's outlinks. Parent
metadata discovery.edges holds bounded JSON edge records (source, valid target
or null, timestamp, depth and decision). Invalid raw references are not persisted
because they may contain credentials. Eligible children carry discovery.source,
discovery.at, discovery.depth and discovery.result=ADMITTED. ADMITTED is not an
RPC acknowledgement or a successful fetch. Cross-document/retry duplicates are
re-emitted to the existing frontier deduplication mechanism: the parser must not
suppress an outlink before a prior attempt's status RPC has been acknowledged.
A replay regression proves this avoids the pre-ack in-memory suppression trap. Existing frontier status metadata
remains authoritative for later FETCHED/FETCH_ERROR/retry outcomes. Distinct
parents retain their own edge observations even when targets are duplicates.
Rejected links already removed by upstream URLFilters cannot be audited here.

Known non-HTML suffixes, including archives/databases/documents/images, receive
METADATA_ONLY and are removed from output links; no bodies or previews are read
by this component. Only extensionless paths or .html/.htm candidates are admitted.
A response with a misleading/absent suffix can still be binary: **a pre-body MIME
contract is not implemented**, so this is not yet safe to activate as unrestricted
live M3. Turning off Tika/non-HTML parsing does not prevent downloading a body.
Tor transport remains unchanged per the task boundary.

## Controlled scenario / reproduction

1. Run offline Maven verify using the existing cache and Java17.
2. Run `python3 -B -m unittest discover -s test/m3 -p 'test*.py'`.
3. After M2 passes, obtain three OWNED v3 hostnames from retained service keys.
   Prepare a new directory:

```sh
python3 -B test/m3/prepare.py .runtime/m3/run <A.onion> <B.onion> <C.onion>
```

The generator writes a separate Flux topology, JSON-as-YAML config, parse/URL
filter resources and a manifest with m3_verified=false. It requires three distinct
v3-shaped names; TorPolicy checks actual checksum during runtime initialization.
It never starts containers, changes networking or supplies bridge credentials.
Package both m3 JSON resource files into a COPY of the fresh shaded application
JAR before Flux use. Generated topology preserves the base graph/one parser task;
M0/M1/M2 topology/config files are unchanged. Configuration persists discovery.*,
sets fixed2s delay/robots enforcement, capped errors, no successful revisits,
page/depth/outlink budgets, and explicitly disables sitemap/non-HTML parsing.
It is experimental input, not a verified deployment recipe.

`test/m3/fixture.py` serves virtual hosts A/B/C on port8080, with A→B twice plus
an archive link, B→C and A, C→A. /robots.txt denies /blocked with crawl-delay2;
/unavailable returns503; /temporary returns503 once then200. /dump.zip returns404
rather than a binary payload. It never contacts upstream or serves filesystem
files. Example future isolated fixture command (not run in this pass):

```sh
python3 test/m3/fixture.py <A.onion> <B.onion> <C.onion> --log /evidence/requests.jsonl
```

For real acceptance, reuse M2's isolated crawler/outbound/fixture networks and
read-only/capability restrictions, publish three hidden services to this fixture,
retain all service keys/volumes, seed ONLY A, and prove B/C automatic discovery
with exact request counts, real SOCKS ATYP3 and actual frontier snapshots. Do not
start this step while the M2 recovery/security prerequisites remain blocked.

## Unfinished acceptance requirements

- Global max-pages across restarts/tasks: admission state must be reconciled with
  durable frontier before restarting; current in-memory budget must not be
  represented as a durable limit. Cross-session duplicate/revisit policy is not
  proven by an in-memory set. No new storage architecture chosen silently.
- Live parser/filter ordering, depth propagation and URLFrontier persistence of
  graph/outcomes require a real controlled topology test. Current adapter test
  invokes the actual ParseFilter API/ParseResult and verifies metadata/outlinks,
  not a running Storm topology or gRPC server.
- Extensionless/mislabelled binaries need pre-body content-type policy; link
  suffix gating alone does not fulfill requirement8 universally.
- Real multi-service Tor routing, robots, unavailable service isolation and timed
  retries remain integration work. Existing M2 protocol/scheduler regression
  tests still run, but are not M3 acceptance evidence.
- Resume/recovery cannot be claimed until M2 gate passes; no endless retries,
  no DIRECT fallback, Tor allowlist or network isolation changes are introduced.

## Executed results

Final results are recorded after the checks below. Logs remain ignored under
.runtime/m3. Earlier local adapter tests exposed two API assumptions (Metadata's
two-key getter is not a default-value getter; new Outlink metadata may be null).
Both were fixed; failed setup logs are not acceptance evidence.

Final offline JDK17 Maven verify: **42 tests passed**, including4 new discovery
checks and the38 existing M2/policy/lifecycle tests. Python M3 fixture:1test
passes; Python M2:66pass. git diff --check passes. No dependency versions or
M0/M1/M2 transport/configuration changed, no Docker/Tor/network operation,
no persistent volume/key deletion. M3 remains IN PROGRESS / acceptance BLOCKED;
network-independent components are committed separately from the M2 baseline.
