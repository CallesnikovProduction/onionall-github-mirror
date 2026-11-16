# M7 — журнал исполнения

Дата: 2026-11-16, Europe/Moscow. Текущий scope: **Phase A / baseline**.
M7 в целом **PARTIAL**. Функциональность navigation, рефакторинг Frontier,
масштабирование и real Tor в этом scope не объявляются реализованными.

## Исходные требования и Git-база

Полностью прочитаны предоставленные владельцем документы; приватные письма,
сертификаты и сами внешние файлы в Git не копировались.

| Документ | SHA256 прочитанного файла |
| --- | --- |
| monster-prompt.md | `0b61757b43b855de7f6432ac0db478b6d48485cd0ee117839cc3f6a8e11254dc` |
| monster-crawling-instruction.md | `83361eb9913c5ec58c1b5aa16ac677449b62f20ef9aaf16ad74ac49d943892d9` |
| frontier-server-instruction.md | `5d722ab5cce203f7fb812df1ee8d15d5d34f6c87ed7a5f428e6f437f916973a3` |

Первоначальный checkout: `feature/astra-onionall-reconstruction`,
`5e08b3fb3ed8093c6491a735ce5a87e4d4fec89f`, без tracked-изменений.
После `git fetch origin` обнаружены 20 интегрированных коммитов в main.
[PR #7](https://github.com/CallesnikovProduction/onionall-github-mirror/pull/7)
уже merged; его M6 и последующий M6.1 присутствуют в актуальном коде.

`m7-monster-refactor` создана строго от
`209d83e9ae3096408a6fa7c128452e062ff060ba`. Проверена ancestry исходного Astra
и Security RC `0a645fdd63b6f21d9af66b988c027d77fb9eb14f`.
M7-ветки на origin перед созданием не было. Local main, migration, Security RC
и Astra refs не изменялись. Все 18 пользовательских untracked-файлов в
docs/audits и onion_codex_specs сохранены по исходным SHA256 и не staged.

По действующему поэтапному правилу этот checkpoint закрывает только baseline.
Следующие фазы и их ограничения перечислены ниже; они не подменяются данным
исправлением checkout. История и результаты остаются здесь, а не в AGENTS.md.

## Проверенная исходная архитектура

- Приложение: 50 Java- и 25 Kotlin-файлов в src/main; 70 Java test sources.
  Это количество файлов, не число классов или уникальных тестов.
- Frontier: 19 main Java- и 9 test Java-файлов. Семь самостоятельных conformance
  drivers; RocksDbDecompositionChecks вызывается из RecoveryCountersConformance.
- CLI → Kotlin research workflow → существующие Java CrawlService/ArchiveStore
  → authenticated Frontier → Storm/TorProtocol → HTML/H2 → readback/export.
- Frontier владеет reservations/quota/scheduling/retries. H2 хранит каталог и
  существующий research_sessions_v1 JSON/CAS, не отдельную очередь.
- Декомпозиция RocksDB уже выполнена: package-private QueueRecovery и UrlIndex
  заимствуют DB/CF; RocksDBService сохраняет владение ресурсами и locks.
  Повторять извлечение из-за старого описания спецификации не требуется.
- Java runtime/toolchain — 25; Frontier сохраняет **release11**. Предложенные
  records/sealed не применимы без отдельного изменения compatibility contract.

Существующий [Graphify report](../../graphify-out/GRAPH_REPORT.md) прочитан как
историческая карта: graph.json указывает built_at_commit `b3650f7`,
1645 nodes / 4081 edges. Это не свежий граф `209d83e`. Graphify executable/MCP
в текущей Windows-среде не найден; новая extraction **NOT RUN**. Ответственности
и найденные участки дополнительно сопоставлены с текущим исходным кодом.

## Первый воспроизведённый дефект и минимальное исправление

**M7-BASE-01 / CONFIRMED / product P2, security impact не установлен.**
На Windows checkout преобразовал четыре golden Markdown/text файла в CRLF.
ResearchExporter формирует LF, а ResearchExporterTest:135 сравнивает текст точно.
Первый полноценный JVM verify: 416 tests, 1 failure, 0 errors, 4 skips.

Причина проверена byte comparison: каждый рабочий golden файл после CRLF→LF
полностью совпал с HEAD blob. POC изменил только generated копии в target:
те же 11 ResearchExporterTest прошли без изменения assertions.

Исправление: два узких `text eol=lf` правила в [.gitattributes](../../.gitattributes)
для `src/test/resources/research/export/*.md` и `*.txt`. Рабочие fixture-файлы
приведены к уже сохранённым HEAD bytes. Продуктовый код, тестовые assertions и
ожидаемое содержимое не менялись. `git ls-files --eol` подтвердил i/lf и w/lf.
Отдельный `git -c core.autocrlf=true -c core.eol=crlf checkout-index` в новый
ignored prefix извлёк все четыре файла с LF и точным совпадением с HEAD blobs.
Эти параметры применены только к одному процессу Git, host/user config не менялся.

Коммит: `b0645a1278a24bdefdfd9f4abee42f54f8d59777`,
`fix(test): preserve LF in exact research export fixtures`.
Обычный push только в origin/m7-monster-refactor; remote SHA подтверждён.
Независимый агент проверил diff, неизменность fixture bytes/assertions и оба
фактических JVM/Frontier лога; замечаний к этому ограниченному fix нет.
Rollback — убрать только эти два правила; это вернёт риск CRLF на Windows,
но не меняет runtime export, schema, RPC или пользовательские данные.

## Текущие проверки

Локальное evidence: `.runtime/m7-baseline-20261116-eyjd5ykz/`, ignored.
Использованы существующие Temurin 25.0.5+8, Maven 3.10.0, Python 3.12.
Никаких системных установок или изменений host networking не выполнялось.

| Проверка | Реальный результат и предел доказательства |
| --- | --- |
| Первый offline JVM verify | BLOCKED до компиляции: в cache отсутствовала закреплённая M6 зависимость kotlinx-coroutines-core-jvm:1.11.0; jvm-verify.log |
| Разрешение зависимости и исходный JVM verify | Maven Central, strict checksums (`-C`), без изменения версии. FAIL: golden CRLF, 416 selected / 1 failure / 4 skips; jvm-verify-resolved.log |
| Golden LF POC | PASS: 11 tests, assertions неизменны; golden-eol-poc.log/json |
| Полный JVM verify после fix | BUILD SUCCESS: 416 selected, 412 passed, 0 failures/errors, 4 skipped; jvm-verify-fixed.log |
| Frontier Maven verify | PASS: все 7 реальных suites, disposable RocksDB, RPC, reopen/crash/replay; frontier-verify.log. Вложенный decomposition check не считается восьмым driver |
| Native CLI smoke | PASS: 9 отдельных JVM команд, help/import/select/plan/approve/status/export; approval повторно прочитан новой JVM. MANUAL_INPUT, external requests=0; cli-baseline-result.json. Это не packaged/container crawl |
| Выбранные Python fixtures/tooling | PASS: 17 tests; fixture-tooling-discovered.log. Socket fixtures, pause marker и propagation negative controls. Не полный Windows tooling suite и не crawler concurrency proof |
| POSIX SIGINT helper test | NOT RUN на Windows; остальные четыре M6.1 fixture methods включены явно |
| Локальный canonical Docker E2E | BLOCKED: Docker CLI/стандартная установка отсутствуют. Исторический CI не засчитывается за текущий запуск |
| Linux portable workflow | PASS на b0645a1: [43834305934](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/43834305934). Container build/test: 416 JVM tests, 0 failures/errors/skips; 7 Frontier suites; 53 tooling tests. Повторные root/crawler invocations не суммируются как новые уникальные tests |
| Linux canonical E2E | PASS ограниченного fixture gate: 3 HTML pages на depths 0/1/2, 9 graph edges, 5 reservations, 6 HTTP requests, retry 503→200 и redirect 302. Archive/graph/Frontier state прочитаны после пересоздания контейнеров. Это не verified crawl completion и не real Tor |
| Real Tor / внешнее исследование | NOT RUN: нет подготовленного локального runtime/bridge input; новых origin-specific разрешений и числовых профилей в трёх инструкциях нет. Заявленные владельцем разрешения приняты, детали не выдумывались |

Четыре JVM skips: два POSIX/readability случая FrontierCapabilityTest и два
symlink случая ArchiveStoreTest/BlobStoreTest. Они не обозначены PASS.
Первая Python invocation через `test.scripts` столкнулась с одноимённым пакетом
стандартной библиотеки и не запустила реальные tests; лог сохранён отдельно.
Корректный unittest loader затем выполнил выбранные 17 tests. Намеренный FAIL
в propagation negative control не является падением реального Frontier suite.

Свежий Linux CI завершён 2026-11-16 09:02:53 UTC, workflow conclusion=success,
head=`b0645a1278a24bdefdfd9f4abee42f54f8d59777`, job=`133187003244`.
Linux запустил все 416 JVM tests без skips; это отдельная платформа, а не
изменение четырёх Windows skips задним числом. Containerized build/test,
Compose validation, controlled runtime readiness и retained shutdown завершены.

Сохранён [canonical-e2e-result artifact](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/43834305934/artifacts/14211230542).
SHA256 загруженного ZIP по Actions:
`ce68825af0a623f03454d04809c78fdbd66db6e14e28e22df9a9e0b9dd695d0f`.
Result явно сохраняет `real_tor=NOT_RUN`, `crawl_completed_verified=false`,
`job_state=UNKNOWN`, `completion=INCOMPLETE`, `effective_concurrency=1`.
Frontier snapshot: reserved=5, pending=0, held=0, scheduled_wait=4,
unscheduled=1; done/success/terminal/retry_wait=null. Эти неизвестные значения
не заменены успешными terminal counters. Новое доказательство закрывает
ограниченный fixture gate, не M7 navigation/concurrency или S1-01 deployment
acceptance. Сырые workflow/Storm logs в репозиторий не добавлены.
Санитизированный итог сохранён локально в ci-baseline-result.json.

Перед документационным checkpoint повторены Markdown links (включая новый
ledger отдельно), whitespace/diff и scoped secret-signature checks: ошибок
не найдено. Повторно сверены все 18 owner SHA256; посторонние файлы не включены.
Это целевая проверка публикуемого diff, не заявление о полном secret audit.

Нативные команды из корня checkout, с указанными существующими tools:

```powershell
$env:JAVA_HOME="$PWD/.runtime/security-stage23/tools/jdk-25.0.5+8"
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$m7Maven="$PWD/.runtime/security-stage23/tools/apache-maven-3.10.0/bin/mvn.cmd"
$m7Cache="$PWD/.runtime/security-stage23/maven-repository"
& $m7Maven -B -o "-Dmaven.repo.local=$m7Cache" verify
& $m7Maven -B -o "-Dmaven.repo.local=$m7Cache" -f frontier-server/pom.xml verify
python -B scripts/runners/verify.py --links-only
```

Offline CLI использует свежие target/classes и Maven dependency:build-classpath,
с Java argument file для Windows. Точные argv/exit codes девяти вызовов сохранены
в cli-baseline-result.json; все exit0. Synthetic seed не запрашивался. Экспорт
не содержит полученных страниц и не является доказательством acquisition.

## Сопоставление обязательных требований с текущим HEAD

Это исходная карта работ, а не объявление всех пунктов реализованными.
Два специализированных агента выполнили независимые read-only исследования
Frontier и crawling; lead повторно проверил ключевые участки и исполнил baseline.
Вложенная делегация не использовалась; одновременные source edits исключены.

| Требование / документы | Текущее состояние и evidence | Приоритет / следующий минимальный gate |
| --- | --- | --- |
| M6 discovery/candidates/approval; crawling §§3–5,10,18 | Реализовано в AhmiaDiscoveryProvider, ResearchWorkflow, ResearchCatalog и SessionCommands. PR7 merged. Нового live provider запроса нет | Сохранить existing contracts; реальный provider только через CLI и подготовленный Tor |
| Navigation detection/ranking/trace; crawling §§6–9,12,19 | Общая модель отсутствует. DurableDiscoveryFilter.filter сейчас резервирует a[href] в DOM-порядке до archival; ForumPagination ограничена numeric thread links | P1: read-only Kotlin inspect над verified snapshot, затем отдельный selection-before-reserve vertical slice с existing parent/depth/quota guards |
| Forum/extraction/export; crawling §§11,20–21 | M6.1 ForumExtractor/ResearchThreads/ResearchExporter уже существуют. Golden regressions и offline CLI повторены. Универсальные движки/real forum не доказаны | Не переписывать работающий экспорт; small actual fixture route прежде новых adapters |
| Graph provenance; crawling §9 | GraphRepository хранит source snapshot/job/URL/disposition и same-job target_page_id. Это не журнал SELECTED/FETCHED и не verified shortest-path API | P1: derive только из подтверждённых snapshots; отдельно согласовать новые persisted semantics при необходимости |
| Metrics; crawling §§13–14 | Реальные archived bytes/pages/posts и freshness есть. Unknown scheduler terminal/retry/fetch totals остаются null | P1: bounded snapshot presentation; никаких точных counters/percentiles без источника observations |
| Concurrency; crawling §17, Frontier §8 | ResearchWorkflow.run последовательный; archive-wide research-run.lock и existing worker ownership. Queue lease check→emit→hold требует отдельного latch POC | P1: сначала measured local characterization. Нельзя включать coroutine fan-out или менять ownership без design review |
| Stress/fault/restart; crawling §§15–16, Frontier §§7–9,17 | Семь Frontier suites повторены. Existing two-origin forum fixture есть; 20 origins/1000 pages, actual concurrent crawler и deployment fault matrix здесь NOT RUN | Сначала small correctness, затем bounded medium/stress; пользовательские БД не используются |
| Frontier decomposition/legacy; Frontier §§3–6,11–12 | QueueRecovery/UrlIndex уже интегрированы. Memory/Distributed/Sharded — public upstream components; отсутствие вызовов не доказывает безопасное удаление | KEEP текущие границы; reflection/config/overlay/API analysis до удаления |
| Targeted Frontier correctness; Frontier §10 | STRONG по коду: GetURLStatus продолжает после protobuf onError; iterator key после EOF/неясный close; JSON non-object получает INTERNAL. Static DecimalFormat и duplicate leases — HYPOTHESES | P1/P2: isolated RED fixtures прежде fixes. Dormant scan(false)/metadata/delete paths не выдавать за открытый anonymous mutation endpoint |
| Admission/S1-01; Frontier §§10,13,18 | Auth/immutable reservation/quota/replay/reopen suites PASS. Shared capability/plaintext остаются. S1-01 controlled-deployment closure OPEN | P0 acceptance: deployed registered-service negative matrix и persisted state, не новая auth system |
| Repository/scripts/docs; master §§9–15,27–29 | Canonical runtime и responsibility-oriented scripts уже есть; исторические M6 docs местами всё ещё называют PR7 open. AGENTS не место результатов/истории | Следующий отдельный scope: reference inventory до move/delete. Не переписывать root/legacy ради размера |
| Language/API/storage compatibility; master §§6–8, Frontier §§18–19 | Kotlin-first application; Frontier release11/JDK25; текущие protobuf/CF/keys сохранены | Нет новой schema, scheduler, auth system или механической Java→Kotlin migration |

Источник Frontier-подозрений: RocksDBService.getURLStatus и RocksDBURLItemIterator,
AdmissionRpc.bindService, UrlIndex.schedulingKey, QueueRecovery.readQueueInfos.
Они не стали CONFIRMED runtime findings только из-за существующего TODO.
Общие «все разрешения получены» не заменяют точные budgets для новых origins;
внутренние ссылки одобренного scope не требуют повторного разрешения поштучно.

## Следующий этап и критерии

1. Phase A baseline зафиксирован текущим Windows запуском и свежим Linux CI;
   известные платформенные ограничения сохранены явно. Следующая фаза не начата.
2. Следующий M7 scope: bounded read-only navigation inspect по проверенному
   archived snapshot — anchors/GET forms/buttons/rel-next, DOM evidence, причины
   отказа, deterministic ranking. Никаких enqueue и schema migrations в POC.
3. После проверки модели — один фактический CLI→Frontier→Storm маршрут к
   архивированной странице; selection должен предшествовать существующему reserve.
   Verified fetched route требует source_kind=CRAWLER, fetched_at/HTTP provenance
   и approved job. Проверенный MANUAL_IMPORT snapshot допустим для offline inspect,
   но не доказывает fetch; это уточнение добавлено после независимого review.
4. Отдельные Frontier RED fixtures: corrupt status, non-object JSON, iterator EOF
   в subprocess; только после доказательства — минимальные compatibility-safe fixes.
5. Ownership/concurrency, новые terminal observations и approval registry требуют
   самостоятельного contract review. Stress/real Tor следуют после small fixture.

Приёмка текущего fix: свежий Windows checkout сохраняет LF golden bytes,
строгие export assertions проходят, весь доступный JVM baseline не получает
новых failures, Frontier guards не меняются. Чистый diff/secret-signature check,
неизменные owner hashes и подтверждённый remote SHA обязательны перед публикацией.
PR полной M7-реализации и merge в main этим scope не объявляются выполненными.
Изменения опубликованы только в `m7-monster-refactor` для review:
[GitHub Compare](https://github.com/CallesnikovProduction/onionall-github-mirror/compare/main...m7-monster-refactor).
