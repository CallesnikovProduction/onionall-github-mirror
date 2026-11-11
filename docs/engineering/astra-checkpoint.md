# Astra engineering checkpoint

## Post-Astra: независимая проверка Sol — 2026-11-11

Эта запись дополняет, а не заменяет исторические результаты ниже.
Исходный фактический HEAD: `5ed275e5da3df899ddd602fd17706be6085c16c4`,
merge Astra в GitHub `main`. Проверен диапазон `4b0d4da..5e08b3f`:
24 изменённых/добавленных файла, удалений и изменений production dependencies нет.
Начальное рабочее дерево чистое. По новой инструкции владельца дальнейшая
публикация выполняется в GitHub `main`, затем тот же HEAD отправляется в Entire
`origin/main`; feature больше не обновляется. Уже опубликованный в feature
checkpoint `8ddde82` сохранён без переписывания истории.

### Вывод и review

**Да: существующий generated product действительно получил и сохранил страницы
Ahmia через официальный Tor.** Это не утверждение о полном завершении crawl,
контейнерной приёмке или закрытии security review.

- CRITICAL/HIGH: новых подтверждённых блокирующих дефектов в проверенном Astra
  delta и capability wiring не найдено; это направленный engineering review,
  не исчерпывающий повторный аудит репозитория.
- MEDIUM, исправлено: `scripts/runners/runtime.py`, прежние проверки `args[:2]`
  теряли runtime/token defaults при глобальных `--json`, `--archive-dir` перед
  командой. `cli_command_path` пропускает известные inherited options вместе с
  их значениями. До fix регрессия падала в 12 комбинациях, после проходит;
  approval не добавляется, явные пути сохраняются.
- Подозрение на host search с `:80`/`:443` снято: `ArchiveStore.normalize`
  удаляет default ports перед persistence. Новый настоящий CLI/catalog regression
  подтверждает поиск; production SQL не менялся.
- MEDIUM, остаётся: `ArchiveStatusUpdaterBolt.recordOutcome` не восстанавливает
  точную Tor-категорию из generic exception class, оставленного SimpleFetcher.
  Часть реальных ошибок имеет `transport_category=UNKNOWN`. Не записываем raw
  exception и не объявляем это доказанным timeout без дополнительных наблюдений.
- S1-01 остаётся **FIX IMPLEMENTED / PARTIALLY VERIFIED / NOT CLOSED**.
  Независимо проверены `AdmissionRpc.guard`, фактическая регистрация обоих
  services, обязательные capability clients и реальные conformance assertions.
  Missing/wrong capability не допускаются к Command/PutURLs/GetURLs; admin
  mutations запрещены. Полный controlled-deployment auth-negative Storm gate
  из `docs/security/SECURITY-REVIEW.md` не заменён native/transport результатами.
  Shared token, plaintext RPC, active-stream revocation и hostile filesystem
  writer ограничения сохраняются.

Реальная цепочка: Kotlin research CLI → Java CrawlService/CrawlExecution →
capability Admission/Frontier/RocksDB → generated Storm LocalCluster →
SimpleFetcher/TorProtocol → JSoup/discovery → HTML objects/H2/observed graph.
Frontier остаётся единственным scheduler и владельцем reservations. Нового
crawler, schema, application service или production dependency не добавлено.

### Свежая матрица доказательств

| Проверка | Результат | Наблюдение |
| --- | --- | --- |
| Post-Astra review | PASS | Независимый scoped review, воспроизведение launcher bug; S1-01 не закрыт |
| JVM build | PASS | JDK 25.0.5, Kotlin 2.3.21, root verify и crawler/thin CLI packaging; 263 JVM tests, 0 failures/errors/skips |
| Frontier | PASS | Отдельный Maven verify; все 7 настоящих conformance suites; authenticated RPC readiness |
| StormCrawler | PASS | Настоящий generated graph в fixture и реальном crawl |
| Tor bootstrap | PASS | Tor 0.4.9.14 + установленный obfs4proxy, существующие приватные bridges; cookie-authenticated bootstrap=100 |
| SOCKS/onion routing | PASS | Remote-name SOCKS5, Ahmia robots HTTP200; настоящий HTTP через TorProtocol; DIRECT отсутствует |
| Depth-2 fixture | PASS | Ветвящийся index/categories/articles: 6 HTML, 12 edges, 8 reservations, 9 HTTP requests, 503→200, 302 |
| Archive/graph | PASS | Реальные parser callbacks, provenance, размеры, SHA256 и CLI readback; без fake catalog rows |
| Process restart persistence | PASS | Повторный Frontier JVM на тех же RocksDB; H2/graph/counts/admission/next due и повторный HTML readback |
| Container recreation | PASS в свежем CI; NOT RUN локально | CI 43026423823: actual container IDs changed, повторный HTML/graph/Frontier readback; локальный daemon недоступен |
| Real Tor crawl | PASS | 7 HTML / 46233 bytes / 66 persisted edges; фактическая глубина 1 при maxDepth=2 |
| CLI | PASS | plan/show/approve/run/results/links/pages/page show/errors/search-local, proxy show/doctor; proxy set отдельно покрыт тестами |
| Tooling/config | PASS | 43 tooling + 2 прежних archive fixture tests, обе Compose profile config, workflow syntax, diff/link checks |

После возобновления задачи [portable CI 43026423823](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/43026423823)
на source `c0fc1a7b2159ba07d42c23fa708bea907aab0f04` прошёл полностью:
образы, обе Maven entrypoints, 7 Frontier suites, 43 tooling tests, readiness,
настоящий CLI/Storm depth-2 crawl и безопасный shutdown. Canonical fixture:
3 HTML на глубинах 0/1/2, 9 edges, 6 HTTP requests, 503→200 и 302;
после реального пересоздания Frontier/SOCKS containers совпали pages, hashes,
graph, admission/quota, scheduler counts и next-attempt date. Итог PAUSED /
INCOMPLETE, не завершённый crawl. [Sanitized result artifact](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/43026423823/artifacts/13860535383)
содержит только result.json; ZIP digest
`f423f3bfae011828d804577fd5b8085600e036471b5bc34ed03fe8c50b9d4631`.
Это новое container-persistence доказательство, а не повторное использование
результатов Astra.

Отдельный [verification CI 43026423796](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/43026423796)
завершился exit 2 / NOT RUN. Все исполняемые JVM, Python и 7 Frontier suites
прошли. Runner намеренно оставляет общую приёмку незелёной:
`archive-write-ack-acceptance` требует отдельного parent-fail/retry решения при
ошибке archive commit; старые opt-in product/crash gates в этом default run не
запускались. Этот контракт в `scripts/runners/verify.py` и комментарии
`.github/workflows/verify.yml` сохранён. PASS portable workflow не закрывает
общую fault/security приёмку. Дальнейшая публикация документации не меняет
проверенный source; повторный дорогой CI для неё помечается `[skip ci]`.

Root и crawler POM запускают одни и те же 263 tests: это не 526 разных tests.
Первый sandbox Maven запуск был ограничен Kotlin cache; первый tooling запуск
не имел локальных sockets. Повторные разрешённые native runs прошли полностью.
Первый native smoke обнаружил старый packaged CLI без research; после завершения
актуальной упаковки повторный E2E прошёл. Эти неудачи не превращены в PASS.

### Реальный crawl Ahmia

Владелец явно подтвердил разрешение администраторов и seed:
`http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion/`.
Torch отдельно не запускался: точный доверенный URL не был предоставлен.
Scope: same-host, depth=2, max-pages=20, effective concurrency=1/per-host=1,
HTML ≤128 KiB, worker TTL=180 s плюс bounded cleanup, binary downloads disabled,
robots enabled. Никакого расширения на найденные внешние hosts.

Первый job `28c98592-b797-4b6b-af37-75718f3e7e01` с timeout=20 s
дал FETCH_ERROR/REQUEST_TIMEOUT в private worker diagnostic, 0 HTML, 1
future-scheduled reservation. Состояние сохранено, успех не заявлен.
После настоящего Tor homepage preflight HTTP200 (4735 bytes, 4.71 s) повторный
job использовал поддерживаемые timeout=30 s и retry=1 min:

- Job: `684c0cf6-3795-47a1-9ca1-0318be86274f`.
- Fetch timestamps: 2026-11-11 **15:18:19–15:18:35 Europe/Istanbul**
  (12:18:19–12:18:35 UTC).
- 7 genuine HTTP200 `text/html` snapshots: `/`, `/about/`, `/add/`, `/blacklist/`,
  `/legal/`, `/privacy/`, `/terms/`; все `source_kind=CRAWLER`.
- 46233 HTML bytes, 66 source-to-target link observations, 10 reservations.
- Максимальная успешно архивированная глубина: **1**, не 2. Depth-2 candidates
  `/onions/` и `/blacklist/banned/` достигли maxFetchErrors; не подменяем их fake
  deeper pages. `/banned/` дал 301. Всего 7 failure observations: redirect,
  четыре transient FETCH_ERROR и два TERMINAL_MAX_FETCH_ERRORS.
- Итог: **PAUSED / INCOMPLETE**, pending=0, held=0, scheduled_wait=8,
  unscheduled=2, archived=7; success/done/terminal/retry_wait остаются null.
  Нулевой worker exit не объявляет завершение задания.
- Root object SHA256:
  `26c953d3f595fffc93bb40a81e945d7743e32ff284829b1111cffdcc4e3feafc`.

Docker не использовался: worker и owning Frontier работали в отдельном Linux
network namespace только с loopback. Test-only byte relay передавал SOCKS bytes
по приватному UNIX socket официальному Tor, работавшему вне namespace; relay не
разрешал имена и не выполнял HTTP. Системные службы/маршруты/DNS не менялись.
У Tor сохранены bridge-only, SafeSocks и OnionTrafficOnly. Это native integration
proof с сохранённой сетевой изоляцией, а не новая production startup схема.
Оба owning Frontier JVM были остановлены штатно; после повторного запуска
прочитаны те же pages/links/counts. Затем отдельные CLI processes ещё раз
прочитали все 7 catalog records и HTML, проверили hash/size. Tor завершён,
приватные данные и identity сохранены; volumes/user state не удалялись.

Локальные retained evidence (Git-ignored, не включать в commits/artifacts):

- `.runtime/sol-review/`: build/test логи и ограниченные результаты transport;
- `.runtime/native-jpw3lvjk/result.json`: линейный fixture, 3 pages/9 edges;
- `.runtime/native-40v168_r/result.json`: branching fixture, 6 pages/12 edges;
- `.runtime/sol-real-mxfxf8un/`: первая неуспешная попытка;
- `.runtime/sol-real-b70lba2e/archive/catalog.mv.db` и `archive/objects/`:
  настоящий H2 catalog и 7 immutable HTML objects;
- `.runtime/sol-real-b70lba2e/result.json`, `post-stop-readback.json`:
  scalar verification/metadata; execution logs остаются private.

### Проверенные команды и границы запуска

Из корня checkout на этом Linux с доступным JDK25 и подготовленным cache:

```sh
mvn -B -o -Dmaven.repo.local=.runtime/maven-linux-repository -Dkotlin.compiler.daemon=false verify
mvn -B -o -Dmaven.repo.local="$PWD/.runtime/maven-linux-repository" -f frontier-server/pom.xml verify
ONIONALL_OFFLINE=1 MAVEN_OPTS=-Dkotlin.compiler.daemon=false bash scripts/cli/build-cli.sh
FIXTURE_PARENT_NETNS=$(readlink /proc/self/ns/net) unshare -Urn sh -c 'ip link set lo up; exec python3 -B test/admission/native_smoke.py --branching --storm-libs .runtime/storm-lifecycle-build-cleanup2/libs --patched-server .runtime/storm-lifecycle-build-cleanup2/context/storm-server-3.1.0.jar'
```

Последняя команда требует уже извлечённых pinned Storm libs/cleanup2 patch;
создаёт новое retained test state, не production runtime и не реальный Tor.
Ограничения sockets/namespace текущего окружения требуют соответствующего
разрешения исполнения; файлы Kotlin daemon можно избежать указанным property.

Для чтения **уже сохранённых реальных данных** сейчас, без поднятого runtime:

```sh
target/onionall/bin/onionall --json --archive-dir .runtime/sol-real-b70lba2e/archive pages --job 684c0cf6-3795-47a1-9ca1-0318be86274f
target/onionall/bin/onionall --json --archive-dir .runtime/sol-real-b70lba2e/archive page show 63f89808-a6f6-4110-9782-907206f18071
target/onionall/bin/onionall --json --archive-dir .runtime/sol-real-b70lba2e/archive research links 684c0cf6-3795-47a1-9ca1-0318be86274f
```

Canonical Docker build/start/status/proxy/plan/approve/run/results/down команды
остаются в [local runtime](../local-runtime.md). Их fresh local Compose execution
BLOCKED отсутствующим daemon; не выдаём native proof за container proof.
В существующий portable workflow добавлен push trigger `main` по новому маршруту
публикации владельца. Real Tor не добавлен в default CI.

### Изменённые файлы и оставшиеся действия

- `scripts/runners/runtime.py`, `test/scripts/test_runtime_preparation.py`:
  исправление inherited-option forwarding и red/green regression.
- `src/test/java/dev/onionall/brawler/cli/ResearchCommandsTest.java`:
  подтверждение canonical default-port search без изменения production SQL.
- `test/archive/socks_fixture.py`, `test/admission/native_smoke.py`,
  `test/scripts/test_canonical_e2e.py`: optional branching graph, настоящий
  research/redirect/retry/restart/HTML proof; legacy fixture сохранён.
- `.github/workflows/portable.yml`: `main` запускает уже существующий CI.
- `docs/local-runtime.md`, этот checkpoint: актуальные команды и evidence.

Остаются: archive-write fault ACK/retry contract; надёжный terminal job
contract и точная persisted transport category; S1-01 controlled-deployment
security gate; отдельный доверенный Torch URL, если требуется второй источник.

---

Updated: 2026-11-09 (Europe/Moscow). Continuation: verified local research, PARTIAL.
Branch: `feature/astra-onionall-reconstruction`.
Base: `4b0d4daa9a56aafc9303cd7d34972bf126b1288a` (`origin/main` at preflight).
This base includes Security RC/S1-01, M5 and the owner's portable runtime changes;
`af171e78d9863e7c1ad2e946c7574d596229ada8` is its ancestor. The delivered checkpoint
SHA is obtained from `git log -1` for this file; no self-referencing hash is stored.

## Continuation: canonical research acceptance

The continuation directive prioritizes actual depth-2 fetch/archive/recreation.
`test/admission/canonical_e2e.py` now exercises the public canonical launcher in
the existing portable CI. The fixture adds a relative 302 to an already admitted
target, preserving the original graph for legacy callers. Local new acceptance
negative controls + real loopback SOCKS redirect: 2 passed outside the socket-
restricted sandbox; existing fixture tests: 2 passed. Initial sandbox socket
attempt failed with WinError 10013. AST/YAML/diff/secret-signature checks passed;
18 owner file hashes remain unchanged.

Fresh [CI 42628616540](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42628616540)
on `bd2dcca3f594072d9dbb6868f37f3aa6cfd80570` passed the canonical E2E: 6 actual
HTTP requests; 503 then 200 retry; 302 redirect; 3 archived HTML pages at depths
0/1/2; 9 persisted edges; 5 Frontier reservations. SHA256 object readback, graph
identity and actual Frontier/SOCKS container recreation all passed. Archive,
graph, quota, scheduling counts and next-attempt date survived down/up. No DB
rows were injected. Final status was UNKNOWN / INCOMPLETE (0 pending/held,
4 scheduled-wait, 1 unscheduled); success/terminal remain null. This is not
proof of a terminal crawl or real Tor. The sanitized result is a CI artifact.

The research scope adds Kotlin `research plan/run/results/links/files`
over existing Java services using a narrow compilation-boundary adapter. No new
schema, scheduler, process owner or authorization is introduced. Bounds are
depth <=2/pages <=20; approval remains explicit. Results expose real persisted
pages/links/file candidates and labelled bounded metrics; unknown attempt counts
and durations remain null. First unit run exposed SQL `max_depth` versus public
JSON `depth` confusion; fixed without changing that contract. Current targeted
JVM regressions: 28 passed, 0 skipped. CI 42631507046 subsequently exercised these
public commands through the same crawl/recreation gate successfully.

The user approved the Ahmia onion homepage, same-host depth 2/max 20. Real Tor
remains BLOCKED: no private local bridge file/runtime, no local Docker, and the
repository's Actions secret list is empty (names-only inspection). No bridge
defaults or direct fallback were substituted. [Ahmia terms](https://ahmia.fi/terms/)
prohibit scraping without permission; automatic provider search is not enabled.
Torch's programmatic endpoint/identity is not verified. Manual selected seeds
can already enter `crawl plan --import --provider ...`; no mock search results.

Published research scope: `5c5aa1a04724a4d66ecc33f89a8e1cf17268442f`; its CI
[42631507046](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42631507046)
passed: 257 JVM tests per application entrypoint with no skips, all 7 real Frontier
suites, 39 tooling tests and research-command crawl/recreation. The next published
scope adds Kotlin runtime proxy show/set and SOCKS doctor. Atomic endpoint updates preserve descriptor
fields; canonical prepare preserves valid operator endpoint and rejects invalid
configuration without fallback. Native targeted JVM checks: 32 passed, 0 skipped;
tooling preparation: 5 passed, 1 skipped (Docker absent). The E2E gate now also
checks configured SOCKS handshake and endpoint persistence. Real Tor remains
blocked; no private bridge input is available locally or in repository CI secrets.

Published proxy/doctor scope: `4955c3c35b8aa81265230f35a59feec3bc6c5b3f`.
[CI 42632508365](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42632508365)
passed: 261 JVM tests per application entrypoint with no skips, all 7 real Frontier
suites, 41 tooling tests and the full research/doctor/proxy persistence E2E.
Local search now matches literal URL/title/text, with optional exact onion host
and job predicates before LIMIT. Kotlin `research search` is explicitly local;
external providers fail with a manual-selection instruction. No schema/index
migration or new database was introduced. Tests verify host/job isolation before
LIMIT, literal SQL input and unchanged manual-import provenance. Full native
JDK25 Maven verify: 262 selected, 0 failures/errors, 3 skipped platform cases;
`.runtime/astra-pass1/continuation-full-verify.log`.

Final functional source SHA: `68bf5aa0aa7c8a05e2eeff0f32d9f579c9bfe9b3`.
[CI 42633512664](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42633512664)
passed on that SHA: 262 JVM tests per root/crawler entrypoint, zero failures,
errors or skips; all 7 real Frontier conformance suites; 41 tooling tests.
Repeated build/test invocations are not additional distinct tests. The tooling
negative control intentionally prints a failed suite while asserting propagation;
all seven separately executed real conformance suites passed.

The final E2E uses `research plan/run --approve/results/links/search`,
`config proxy set/show`, `system doctor`, `pages`, `page show` and legacy local
text search through the canonical launcher. It checks actual URL/host/job search,
one metadata-only file candidate, SHA256 HTML readback and truthful metrics.
Result: 6 HTTP requests, 3 archived pages at depths 0/1/2, 9 edges, 5 reservations,
503->200 retry and HTTP 302. No depth-3, robots-denied, foreign-host or binary
fetch occurred. Actual Frontier and SOCKS container IDs changed after down/up;
archive/graph identity and authoritative scheduling/quota observations persisted.
No worker exception types were reported. Final job is UNKNOWN / INCOMPLETE:
reserved=5, pending=0, held=0, scheduled_wait=4, unscheduled=1, archived=3;
success, terminal, done and retry_wait remain null. It does not prove a completed
crawl or real Tor. The [sanitized result artifact](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42633512664/artifacts/13681214932)
contains no downloaded research content or raw worker logs; ZIP SHA256:
`ab361810f732ef2bad0cac644346e149d5b077c015e3a6d95d50251790a57971`.

Persistence proof is container recreation on the same Docker host, with retained
bind mounts and named volumes. It is not host/disk-loss recovery. CI runner storage
is ephemeral after the job; the sanitized result is the retained evidence.

| Continuation gate | Current result and boundary |
| --- | --- |
| 1 Operational | PASS in Linux CI: canonical images/build/test/up, authenticated Frontier, CLI/Storm, fixture SOCKS and shutdown. Local Windows Docker execution remains BLOCKED |
| 2 Deterministic E2E | PASS: actual generated graph HTTP acquisition, depth enforcement, persisted HTML/edges, CLI readback and container recreation |
| 3 Real research | BLOCKED / NOT RUN: approved seed exists, but no usable private bridge/runtime input was found; no actual onion response/content claimed |
| 4 Discovery | PARTIAL: local search and manual provider URL/selected-seed import exist; automated provider acquisition and persistent keyword-candidate results are not implemented. Ahmia permission and a verified Torch interface are absent |
| 5 Repository quality | PARTIAL: affected runtime/CLI/AGENTS instructions, tests and checkpoint updated; owner files preserved; no blind legacy deletion or repository-wide TODO cleanup claimed |

Four functional commits were pushed individually to the Astra branch and their
remote SHA verified: `bd2dcca`, `5c5aa1a`, `4955c3c`, `68bf5aa`. The subsequent
checkpoint/guide correction is documentation-only; it does not change the tested
source SHA. Its `[skip ci]` avoids rerunning the same functional build; local link,
whitespace, diff and scoped secret-signature checks still apply.

## Execution boundary and decisions

The owner master directive is the active reconstruction scope: preserve working
Java and durable state; new application functionality is Kotlin-first. Begin with
the existing canonical workflow. Do not replace Frontier scheduling, weaken S1-01,
change Tor isolation or silently migrate persistent state. Source comments/Javadoc
from the owner remain intact. No code/data deletion or language migration occurred.
The local Windows host cannot run the Linux Docker workflow: Docker was absent
from PATH/standard install location and WSL reported not installed. No installation,
service, host networking or security-setting change was attempted.

## Current repository inventory and implemented flow

| Area | Classification and actual responsibility |
| --- | --- |
| `onionall`, `compose.runtime.yaml`, `scripts/runners/runtime.py` | ACTIVE: canonical Linux/Bash build, lifecycle and CLI forwarding; custom Frontier, internal crawler network, retained volumes |
| `docker/toolchain/`, `.mvn/`, `crawler/pom.xml`, `frontier-server/pom.xml` | ACTIVE: container JDK25 + wrapper; separate crawler/server Maven builds |
| Root `pom.xml` | ACTIVE compatibility build, despite artifact name `onionall-reactor`; no `<modules>`. Shares source/output and much build configuration with crawler POM; duplication exists, deletion/consolidation is not yet justified |
| Java `crawl/`, `admission/`, `archive/`, `cli/` | ACTIVE: application boundary, approved launch, authenticated admission, worker ownership, catalog/HTML storage and presentation |
| Kotlin `tor/`, `discovery/` | ACTIVE transport/scope and offline planner; discovery Storm adapters explicitly local-test |
| `frontier-server/src/main/` | ACTIVE owning RPC/RocksDB authority; existing capability protects mutations, server owns reservations/quota/scheduling |
| `src/test/`, `frontier-server/src/test/`, `test/` | TEST INFRASTRUCTURE: JVM, durable conformance, platform/runtime fixtures; retained |
| `compose.m*.yaml`, legacy Flux/resources | TEST INFRASTRUCTURE / historical deployment contours with active callers; not competing product startup instructions and not proven dead code |
| `docs/adr`, operational contracts and ledgers | ACTIVE contracts plus HISTORICAL evidence; dated reports do not establish current execution |
| `docs/audits/`, `onion_codex_specs/` | Owner-authored untracked input; 18 files hash-preserved, excluded from commits |
| `target/`, `frontier-server/target/`, caches/logs | GENERATED; no generated files committed |
| `.runtime/local*`, Docker volumes, private files | Persistent/user state, not disposable merely because ignored; no user-state migration/deletion |

Product flow remains CLI plan/approval -> CrawlService/CrawlExecution -> protected
Frontier -> generated Storm graph/TorProtocol -> discovery reservation and HTML/H2
archive -> authenticated status updates. H2 is a catalog, never the scheduler.
The continuation adds Kotlin research CLI orchestration over that same flow;
no second scheduler, provider scraping or authentication subsystem is introduced.

## Concrete correction in this scope

The shell builder ran seven server suites, Python verification only six, Maven
only four. Maven now also runs RecoveryCountersConformance, AdmissionStateConformance
and AdmissionAmbiguousRpcConformance; Python now runs the last of these. Existing
assertions are unchanged. A dispatch regression enumerates actual conformance
sources, verifies every driver is selected and an ambiguous-RPC failure keeps the
overall result failed. This regression uses mocked execution; real runs are below.

The Frontier README incorrectly described a root reactor and `-pl frontier-server`.
It now documents the existing independent Maven imports and `-f` commands. The
portable workflow also triggers on the Astra branch, using its existing Linux
runner/internal fixture. Workflow execution is separate from local verification.

Read-only inspection of the owner's preceding portable CI run
[42518705000](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42518705000)
found a concrete startup failure: `Path.open(opener=...)` raises TypeError before
the capability file is created. This was reproduced locally with two real-file
regressions before changing implementation. The controller now uses built-in
`open(..., 'x', opener=...)`, retaining exclusive creation and mode 600, existing
token reuse and the strict permission check. This is a local correction to existing
Python deployment tooling, not a new application service or authorization system.
The historical CI build results are not credited as validation of the Astra SHA.

Published source checkpoints: `8f17d2f` (complete conformance selection),
`2e1df11` (capability creation) and `584d7d0` (single tmpfs mount). The fresh
[Astra CI run 42522560099](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42522560099)
on `2e1df11` built all images, passed container JVM/Frontier/tooling tests and
Compose syntax checks. Startup then exposed an independent existing YAML defect:
unquoted commas in the flow-list tmpfs entry created five mount paths, and Docker
rejected `exec` as nonabsolute. Quoting the single mount preserves rw/exec/nosuid/
nodev/mode=1777. A Compose CLI regression checks normalized mounts for all three
worker-derived services; it needs no Docker Engine. Local YAML parsing reproduced
the five-element input and confirms one corrected element. Windows without Docker
skips this Compose regression explicitly; the successful Linux run executed it.

The fresh [Astra CI run 42523305221](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42523305221)
on full source SHA `584d7d0b8ebb0a03e791e2e2b2bb17f4488341ef` completed successfully.
Both Maven application entrypoints ran the same 253 tests with no failures,
errors or skips; these are not 506 distinct tests. All seven real Frontier
conformance suites passed, and all 36 tooling tests passed, including private
token mode/exclusive creation and the actual Compose CLI mount regression.
The tooling negative-control test deliberately prints an ambiguous-RPC `FAIL`;
its assertion checks failure propagation, separately from the passing real suite.
Image build, Compose validation, fixture up/status and down all succeeded.
Authenticated Frontier returned READY, the SOCKS handshake returned READY,
and packaged CLI/Storm probes completed. This proves startup/readiness/shutdown,
not a crawl: no worker research job or real Tor request was launched. Shutdown
did not request volume deletion; persisted-data survival after container recreation
was not asserted. The previous failed run remains part of the evidence.

## Original-pass verification (before the continuation above)

Local commands ran with cached Temurin 25.0.5+8, Maven 3.10.0 and Python 3.12.
Kotlin 2.3.21 actually compiled at JVM target 25; no versions were changed.

| Gate | Result / evidence |
| --- | --- |
| Initial `verify.py --offline` in sandbox | FAIL recorded, not erased: Kotlin daemon and gRPC loopback denied; Windows/POSIX tooling failures; `.runtime/verification/run-0fjtym9f/result.json` |
| Root Maven `verify` with permitted loopback | BUILD SUCCESS; 253 tests, 0 failures/errors, **3 skipped** (POSIX/readability/symlink capability cases); `.runtime/astra-pass1/maven-host.log`. Not an all-tests acceptance pass |
| Frontier input preparation | Exact original SHA256 validated; filtered dependency entries checked byte-for-byte; `.runtime/astra-pass1/frontier-prepare.log` |
| Frontier first offline Maven attempt | BLOCKED: prepared file-repository artifact not yet resolved into Maven cache; preserved `frontier-maven-offline.log` |
| Frontier Maven `verify` after resolution | PASS: compile, all **7 real conformance suites**, overlay package; `frontier-maven-resolve.log`, `frontier-server/target/maven-conformance-7573300950520228686/` |
| Python Frontier entrypoint with permitted loopback | PASS: **7 real conformance suites**, fresh disposable DBs; `.runtime/astra-pass1/frontier-m03wtex5/result.json` |
| Verification-runner regressions | PASS: 7 tests including deliberate failure propagation; `.runtime/astra-pass1/verification-tests.log` |
| Runtime token preparation | Before: 2 errors with the reproduced TypeError; after: 2 tests PASS. Real files, exclusive-open race and token reuse checked; Windows permission rejection retained. `runtime-token-before.log` / `runtime-token-after.log`; Linux CI also passed mode enforcement |
| Existing Python groups | M3 2/2, archive 2/2, security 4/4 pass; M1 32 selected/1 skipped; CLI 12 skipped. M2: 113 selected, 2 failures/7 errors/2 skipped; scripts: 32 selected, 2 failures/28 errors. See initial result JSON; no platform failures relabelled as success |
| CLI entrypoint with resolved Maven classpath | PASS: `Onionall --help`, exit 0; `.runtime/astra-pass1/cli-classpath-help.log`. Direct root shaded-JAR probe lacked provided Storm Config; not the packaged CLI contract |
| Offline CLI persistence smoke | PASS: depth-2 plan -> approve -> show -> pages across separate JVMs and fresh H2; `.runtime/astra-pass1/cli-plan-pwbwvrmz/result.json`. Approval persists; admission remains UNOBSERVED and archived pages are empty, as no fetch was run |
| Local canonical Docker images/up/status/down | BLOCKED: Docker/WSL prerequisite absent; no real container/runtime acceptance inferred from native JVM tests |
| Linux portable CI on `584d7d0` | PASS: root/crawler Maven (253 tests each, 0 skips), 7 real Frontier suites, 36 tooling tests, paired images, Compose config, authenticated Frontier/SOCKS and packaged CLI readiness, shutdown. [Run 42523305221](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42523305221); no depth-2 crawl or data-retention readback |
| Real-network/depth-2 acquisition | NOT RUN: no new approved seed or operational Tor runtime; no provider availability or persisted research-data claim |

Reproducible native diagnostic commands, using an explicitly selected JDK25 and
Maven on PATH (`CACHE` is a checkout-local dependency cache):

```sh
mvn -B -o -Dmaven.repo.local="$CACHE" verify
java frontier-server/build-support/FrontierBuild.java prepare "$URLFRONTIER_BASE_JAR" frontier-server
mvn -B -Dmaven.repo.local="$CACHE" -f frontier-server/pom.xml verify
python -B -m unittest discover -s test/scripts -p test_verification.py -v
python -B scripts/runners/verify.py --links-only
```

The first Frontier resolution needs access to its prepared file repository and
uncached Maven plugins; later offline builds require that completed cache. Raw
evidence remains ignored. Native JDK execution does not replace the canonical
container workflow documented in [local runtime](../local-runtime.md).

## Blockers and next executable action

1. Canonical depth-2 research commands, doctor, local URL/host/job search and
   persistence after actual container recreation passed at source `68bf5aa` in
   CI 42633512664. The next real-network milestone needs an existing authorized
   Linux Docker/Tor runtime with private bridge input and permitted source access.
   The local Windows host still lacks Docker/WSL prerequisites.
2. Keep Windows Python portability failures distinct: missing POSIX shell paths,
   symlink/mode semantics, `os.getuid`, and sandbox MSYS startup restrictions. Do
   not weaken assertions or install OS components to produce a green summary.
3. An Ahmia seed is approved; real Tor remains blocked on private bridge/runtime
   prerequisites and automated provider access restrictions. Do not substitute
   direct networking. No actual real Tor fetch/provider result is claimed.
4. Research result lists are bounded at 100 with possible-truncation labels.
   Total fetch/retry attempts and durations remain null because no durable source
   establishes them. Reliable terminal outcome, durable query/candidate storage
   and additional metrics need a narrow contract review before persistent changes;
   do not infer success from archived rows or unscheduled status.

Tracked changes were checked with `git diff --check`, local Markdown-link
verification and scoped secret-signature inspection. All 18 owner untracked files
still match their preflight SHA256 values and remain outside commits. Publication
uses only `origin/feature/astra-onionall-reconstruction`; no force push is used.
The original e35bc85 checkpoint was documentation-only; continuation source/test
changes require their own actual runs as recorded above.

Full research-system acceptance, S1-01 security closure and real Tor acceptance remain open. No PR,
merge, main/source branch change or deployment is part of this delivery.
