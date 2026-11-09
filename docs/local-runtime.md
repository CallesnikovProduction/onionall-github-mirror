# Local product runtime

`./onionall` is the canonical Linux/Bash entrypoint. It reuses the JVM CLI,
generated product graph and authenticated custom Frontier. Historical M0/M1/M2
Compose deployments and their volumes remain separate.

## Build

Requires Bash and Docker Engine/Compose; no host JVM, Maven or Python.
Use the [portable development workflow](../README.md#development-without-installing-java).

```sh
./onionall build --images
./onionall test
```

The launcher builds the JDK 25 toolchain image, verifies root and crawler POMs,
packages the CLI and prepares/builds the independent Frontier Maven project.
The exact pinned base is extracted automatically; no manual JAR download needed.
Maven/Wrapper caches persist under `.runtime/`, outputs use the caller UID/GID.
`--clean` affects generated Maven outputs, never runtime storage. Legacy native
build scripts remain available when an explicitly selected JDK 25 is installed.

IDEA: import `crawler/pom.xml` and `frontier-server/pom.xml` as separate Maven
projects, with Maven JDK 25. First prepare the server's exact pinned dependency:

```sh
java frontier-server/build-support/FrontierBuild.java prepare "$URLFRONTIER_BASE_JAR" frontier-server
```

Use standard `src/main/java` and `src/test/java`. Never mark the whole server
directory as Sources Root. Its bytecode remains release 11 on JDK 25. This is
independent Maven import; the root POM does not aggregate these projects.

## Start, health and safe stop

```sh
export ONIONALL_BRIDGES_FILE=/absolute/path/to/private-bridges.conf
./onionall up
./onionall status
./onionall down
```

The private file follows the existing [obfs4 format](../docker/m2/bridges.example.conf).
Only Tor joins the outbound network. Crawler/Frontier/probes stay internal with
no published ports. Tor health requires authenticated bootstrap 100%, bridges
and a running obfs4 transport. A separate SOCKS5 negotiation checks the protocol;
it does not prove target onion availability. Frontier health uses an authenticated
read-only Admission RPC, without creating sessions or acquiring leases. Stock
Frontier cannot pass it. Crawler PREPARED means CLI and installed Storm version
execute; a successful crawl and persisted data require separate observations.

`up` waits at most 120 seconds for health; failures retain diagnostics and data.
Check Docker, private bridges and `status` before retrying. It requires the
cleanup2 image label and refuses simultaneous canonical fixture/Tor modes.
`down` first stops owned one-off crawler containers, then services. It never
passes `-v` or deletes data. It is safe to call after a failed startup.

## Crawl and inspect

```sh
SEED_URL='YOUR_EXPLICITLY_APPROVED_ONION_URL'
PLAN_ID=$(./onionall cli crawl plan --seed "$SEED_URL" --scope same-host --depth 2 --max-pages 20 | python3 -c 'import json,sys; print(json.load(sys.stdin)["plan_id"])')
./onionall cli crawl show "$PLAN_ID"
./onionall cli crawl approve "$PLAN_ID"
./onionall cli crawl start --plan "$PLAN_ID"
./onionall cli crawl status "$PLAN_ID"
./onionall cli pages --job "$PLAN_ID"
./onionall cli crawl errors "$PLAN_ID"
./onionall cli search-local keyword --job "$PLAN_ID"
# Replace PAGE_ID with an actual identifier returned by pages:
./onionall cli page show PAGE_ID
```

The launcher supplies default runtime/token paths for `crawl start/status` only.
The existing implementation still owns plans, explicit approval, scope, durable
reservations, deduplication, retry and robots. Seed depth is 0, children 1,
grandchildren 2; depth 3 is not fetched. Foreign-host/binary-extension links are
metadata observations. The 20-page limit caps reservations (including robots
denials), so archived HTML can be fewer. The current product graph performs one
fetch at a time: effective global 1 <= ceiling 4 and per-host 1. Four concurrent
requests to the same host are not enabled.

Start runs foreground for 180 seconds plus bounded cleanup. Pending or future
due work may remain PAUSED; resume with the same plan ID and `crawl start`.
Frontier v1 cannot establish reliable success/terminal provenance for every
unscheduled row. Status separates archive/failure/worker observations from
pending, held, scheduled-wait, unscheduled and unknown scheduler states. Worker
exit zero alone never establishes COMPLETE. H2 can report contention during
live callbacks; inspect after foreground start returns.

## Research commands

The prepared runtime's proxy can be inspected/configured without changing host
proxy/DNS settings. Start runtime first so its descriptor exists:

```sh
./onionall cli config proxy show
./onionall cli config proxy set --type socks5 --host tor --port 9050
./onionall cli system doctor
```

Use `socks-fixture` with `--fixture`; container `127.0.0.1` means that container,
not the host. Explicit native CLI deployments can pass `--runtime PATH` and
configure `127.0.0.1:9050` if their official Tor client runs there. Only SOCKS5,
gateway hostname/IPv4 and ports 1..65535 are accepted; no proxy credentials or
DIRECT fallback. `set` atomically updates host/port for the next worker and
preserves other descriptor fields. Canonical up preserves the selected endpoint.
`doctor` is a bounded SOCKS negotiation, not proof of official Tor identity,
bootstrap, onion HTTP or Frontier readiness; use canonical `status` for the
managed Tor/Frontier health checks. Raw descriptors/secrets are not printed.

New Kotlin commands reuse the same plan ID, catalog, approval and authenticated
Frontier execution. They cap research plans at depth 2 and 20 reservations:

```sh
./onionall cli research plan --url "$SEED_URL" --depth 2 --max-pages 20
# Inspect the returned scope and use its plan_id:
./onionall cli crawl show PLAN_ID
./onionall cli research run --plan PLAN_ID --approve
./onionall cli research results PLAN_ID
./onionall cli research links PLAN_ID
./onionall cli research files PLAN_ID
./onionall cli research search --query keyword --job PLAN_ID --host APPROVED_ONION_HOST
```

`--approve` is explicit approval of the stored immutable scope. Without it only
an already approved plan can run. `results` separates scheduler observations
from archive counts, lists stored source-to-target links and file candidates,
and reports unknown fetch/retry-attempt counts and durations as null. Lists are
bounded at 100 and label possible truncation. File candidates are never downloaded.
Legacy `crawl` commands and their JSON contracts remain supported.

`research search` is local URL/title/text search using the existing H2 catalog,
with SQL job/host filters applied before the result limit. It makes no provider
request and reports possible truncation. `--provider ahmia|torch` is rejected
explicitly: automated provider access is not implemented/permitted by the current
verified contract. Existing `search-provider QUERY --provider ahmia` prepares a
manual URL; selected permitted URLs can enter `crawl plan --import FILE --provider
ahmia`. A manual plan does not prove downloaded provider results or Tor acquisition.

## Controlled fixture

After building and preparing images, select the existing internal SOCKS graph:

```sh
./onionall --fixture up
./onionall --fixture status
SEED_URL='http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/a'
PLAN_ID=$(./onionall --fixture cli crawl plan --seed "$SEED_URL" --scope same-host --depth 2 --max-pages 20 | python3 -c 'import json,sys; print(json.load(sys.stdin)["plan_id"])')
./onionall --fixture cli crawl approve "$PLAN_ID"
./onionall --fixture cli crawl start --plan "$PLAN_ID"
./onionall --fixture cli crawl status "$PLAN_ID"
./onionall --fixture cli pages --job "$PLAN_ID"
./onionall --fixture cli crawl errors "$PLAN_ID"
./onionall --fixture down
```

The fixture never forwards or resolves actual destinations. Real TorProtocol and
the product graph process `/a → /b → /c` at depths 0/1/2. `/b` returns 503 once,
then retries after one minute; `/blocked` is robots-denied, `/deep` depth-denied,
foreign host scope-denied and `/dump.zip` metadata-only. A relative `/redirect`
returns 302 to the already admitted `/b`; it does not add a new admission path.
Worker duration is 120
seconds. Reuse the same plan for resume/deduplication; a new plan is a new crawl.
Retained fixture HTTP logs are cumulative. Tor is explicitly NOT_RUN_FIXTURE.

## Storage and verification boundaries

Default `.runtime/local/`, fixture `.runtime/local-fixture/`: private
`admission-token`, `runtime.json`, H2 `archive/catalog.mv.db`, HTML
`archive/objects/` and `execution/PLAN_ID/` configs/logs/ownership evidence.
Named volumes: `onionall-runtime_frontier-data`, `onionall-runtime_tor-state`;
fixture Frontier: `onionall-runtime-fixture_frontier-data`. Build/stop never
delete these. Never use stock Frontier on admission databases or erase ownership
markers. Raw runtime logs may contain credentials; keep them private.

Container assembly, controlled fixture acceptance and real Tor acceptance are
separate checks; see the engineering evidence for executed results. Docker-free
`test/admission/native_smoke.py` checks the real generated CLI/Frontier/Storm
graph with the existing fixture in a separate Linux network namespace containing
only loopback. It requires extracted Storm 3.1.0 libraries and the existing
cleanup2 patched server JAR. This is separate evidence and never substitutes for
Compose/Tor acceptance; no native production crawl launcher is introduced.
