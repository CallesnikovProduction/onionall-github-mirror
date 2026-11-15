# Archive hardening & research readability — инженерный отчёт

## 1. Executive Summary

Archive сохраняет исходные HTML и UTF-8 text bytes, проверяет SHA-256, различает
specification/admission/status/failure evidence и формирует читаемый paired
Markdown/JSON export. Подтверждённые ошибки ownership, provenance, failure INSERT,
timestamp ordering и response readback исправлены. Новые domain/rendering компоненты
на Kotlin, JDBC adapters остаются Java; dependencies/версии не менялись.

Core schema остаётся **2**; text documents — independently versioned additive
extension. HTML consumers не получают Markdown под видом HTML. Все лимиты сохранены:
131072 bytes/document, 64 MiB objects, 128 MiB H2 pre-write guard, 100 distinct URLs/job,
depth≤5, failures/observations≤1000, status transitions≤32. Ни scheduler, ни
автоматическое удаление данных не добавлены. Loom не применяется.

Основная область изменений — Archive и его tests/docs, включая разрешённый
ResearchCatalog. Пользователь отдельно разрешил минимальное исправление
`Brand.header()` и расширение существующего geometry regression. Остальные модули
изучены read-only; их исходные незакоммиченные изменения сохранены.

Полноценный Tor/Frontier crawl не выполнен. Контролируемый Archive workflow прошёл
с проверкой реальных H2/blob/export результатов; публичный Discourse был прочитан
в рамках fallback и честно дал PAGE_TEXT_ONLY, без post boundaries.

## 2. TODO Resolution

Проверены как фактические TODO, так и идентификаторы из задания, отсутствовавшие
в комментариях. TODO не закрывались переименованием комментария.

| TODO | Первопричина | Решение | Тест | Статус |
| --- | --- | --- | --- | --- |
| ARCH-CTX-001 | synchronized reentrant: close из read callback освобождал connection | operationDepth + finally unwind, close запрещён внутри active callbacks | ArchiveOwnershipHardeningTest | исправлен |
| OBS-001 | status-only JSON получал admission clock/authority | private admission marker; conservative legacy inference; marker не выходит в projection | ArchiveOwnershipHardeningTest | исправлен; legacy {} ambiguity обозначена |
| OBS-002 | payload мог записать audit/provenance | managed fields проверяются под guard; точное эхо текущих полей разрешено/удаляется, injected/conflicting/stale запрещены | ArchiveOwnershipHardeningTest; existing status integrations | исправлен с compatibility regression |
| OBS-003 | observation mutation пропускала capacity | единый context.capacity внутри transaction | sparse >/=128 MiB tests | исправлен |
| FAIL-001 | INSERT ссылался на created_at, реальная schema — occurred_at | явные правильные колонки, additive-column fixture | ArchiveFailureQueryHardeningTest | исправлен |
| FAIL-002 | producer после saturation может повторять archive-first failure | Archive fail-closed/rollback/reuse сохранены, никаких dropped observations | saturation regression | upstream policy отложена точному владельцу |
| FAIL-003 | frozen observation page и live aggregate count выглядели одинаково | additive basis fields THROUGH_SEQUENCE/LIVE_AT_READ, cursor прежний | append-between-pages regression | исправлена неоднозначность projection |
| QUERY-001 | лексикографический порядок variable-precision timestamps неверен | CAST AS TIMESTAMP(9) WITH TIME ZONE в search/pages/history/graph/failure summary | query/snapshot nanosecond + offset fixtures | исправлен без data rewrite |
| QUERY-002 | merge мог расширять observation authority | protected immutable set + реальные specification keys; counters/owners вычисляются после merge | authority/query tests | проверен/усилен |
| QUERY-003 | LIMIT не ограничивает substring scan cost | стоимость подтверждена; bounded catalog envelope сохранён, speculative FTS не вводился | literal/host/limit regressions | известное performance limitation |
| QUERY-004 | export читал разные component states между writers | consistentRead: catalog_guard на весь capture, nested read reuse | deterministic lock/interleaving test | исправлен для cooperating writers |
| RS-001 | direct bridge get/exists не получал ownership | bridge serializableRead + closed rejection | ArchiveOwnershipHardeningTest | исправлен |
| RS-002 | требовалась проверка внешней transaction/savepoint/CAS | existing savepoint contract сохранён, rollback/CAS/reopen проверены | ownership + ResearchCatalogTest | подтверждён |
| PLAN-001 | readers требуют owner monitor | connection() уже fail-closed; valid/unowned/owned/closed paths проверены | ArchiveRepositoryContractsTest | контракт подтверждён |
| GRAPH-001 | monitor ownership не означает active transaction | requireTransaction перед recordFirstLinks | ArchiveSnapshotHardeningTest | исправлен |
| SNAP-001 | replay не создаёт новые link observations | исходный first-snapshot immutable contract подтверждён; append event journal не подмешан | existing graph + A→B→A regressions | контракт подтверждён; event journal отдельное решение |
| SNAP-002 | A→B→A latest указывает на старую immutable version | latest определён как latest observed content, first provenance не меняется | ArchiveSnapshotHardeningTest | подтверждён/документирован |
| SNAP-003 | после commit readback мог вернуть rival snapshot | возвращается exact committed page/snapshot identity, page() остаётся current latest | controlled rival commit proxy | исправлен |
| BLOB-001 | file force не равен directory fsync/power-loss proof | supported process/restart model явно ограничен | blob integrity/concurrency tests | отложен storage crash protocol |
| BLOB-002 | orphan/temp расходуют capacity | retention и conservative accounting сохранены; destructive cleanup запрещён | capacity + SQL-failure/reopen regressions | отложен verified recovery/GC protocol |

## 3. Additional Findings

- Standalone ResearchCatalog раньше включал autocommit после rollback failure и
  маскировал primary. Теперь borrowed connection не закрывается адаптером, unsafe
  restoration запрещена, adapter становится unusable до owner recovery; ambiguous
  commit явно UNKNOWN. Sanitized SQL diagnostics/no-cause contract сохранён.
- Старые fixture tests отражали private ArchiveStore.context, но пользователь уже
  ввёл ArchiveStoreNode. Fixture SQL теперь использует отдельный закрываемый H2
  connection, без fake ownership field и ослабления assertions.
- Inert Markdown rendering отдельно проверен на raw HTML, images/trackers, file/data/
  javascript/vbscript destinations, multiline reference definitions, escaped/unmatched
  backticks и invalid fence info. Ambiguous input остаётся полным literal source с
  явной limitation; код внутри корректных fences не повреждается HTML escaping.
- Header внутри fenced source не становится document title. Relative query-only links
  используют существующий UriReferences, не Java URI.resolve с потерей path.
- Разрешённый пользователем Brand fix: при width=8 max=7 конфликтовал с min=10 у
  Math.clamp. Восстановлена двухступенчатая политика: desired minimum уступает screen
  cap. Остальной branding сохранён; geometry cases расширены на widths 2,7,8,9,10,11.

## 4. Architecture Changes

`ArchiveStore → ArchiveContext → repositories → H2 / BlobStore` остаётся основой.
Context владеет единственным connection. serializableRead — Java monitor, не SQL
SERIALIZABLE/read-only. consistentRead использует общую guard transaction и
переиспользует активную transaction для составного чтения без nested commit.

Новые компоненты:

| Компонент | Ответственность |
| --- | --- |
| internal/DocumentRepository.java | approved/same-origin UTF-8 versions, text read/list/search, immutable .txt blobs |
| internal/ResearchExportService.java | единый verified HTML/text capture, затем filesystem output вне JDBC lock |
| kotlin/.../archive/ArchiveTextDocuments.kt | explicit media/charset validation, lossless source, conservative links/inert Markdown |
| kotlin/.../archive/ArchiveForumExtractor.kt | adapter существующего conservative extractor, snapshot provenance, explicit replies |
| kotlin/.../archive/ArchiveHtmlMarkdown.kt | headings/fences/tables/quotes/HTTP links без active content |
| kotlin/.../archive/ArchiveResearchBundle.kt | versioned paired JSON/Markdown + raw text + atomic manifest |

Additive tables archive_document_pages_v1 / archive_document_versions_v1 имеют plan/page
FKs, unique identity и composite latest-owner FK. Text bytes — objects/<sha>.txt,
старые HTML — objects/<sha>.html. Общие capacity scan и distinct URL union guard
не создают второй quota authority: это только bounded Archive storage, не Frontier
admission. Старые binaries не знают text rows; rollback mixed archive допускает
HTML чтение, но смешивать старые/новые writers с разным budget knowledge нельзя.

## 5. Markdown Support

Новые API: importDocument, archiveDocument, researchDocument, exportResearch.
importDocument HTML направляется в существующий HTML import; text/plain остаётся
PLAIN_TEXT даже с .md extension. text/markdown становится MARKDOWN; оба требуют
valid UTF-8 и поддерживаемый explicit Content-Type. Missing/invalid MIME, charset,
binary controls, oversized body отклоняются без committed rows. Raw source полностью
сохраняется, revisions deduplicate по page/hash/source kind/format; manual import
имеет status0 и fetched_at=null.

Search включает HTML/text latest projections с фильтрами до per-source LIMIT,
глобальным chronological merge и общим limit. JSON text result содержит document_id,
document_format/storage_location; чтение через researchDocument не маскируется под
старый HTML-only page. Metadata export дополнен documents, старые поля остаются.

**Upstream root cause:** generated CrawlConfigurationFactory включает
`tor.discovery.html.only=true`; TorProtocol отклоняет non-HTML/missing MIME,
non-identity encoding и отсутствие HTML signature до Archive. Pinned JSoupParserBolt
3.7.0 не вызывает ParseFilters для unsupported text/markdown. ArchiveParseFilter теперь
умеет сохранять уже вызванный text callback, но не может заставить внешний parser
или transport его вызвать. Эти компоненты не изменялись.

## 6. Forum Extraction

Поддержаны контролируемые phpBB-like div.post[id] → postbody/content и semantic
schema.org DiscussionForumPosting articles. Это проверенные DOM contracts, не
универсальная поддержка всех phpBB/Discourse/XenForo installations.

Посты сохраняют DOM order, source post_id, deterministic snapshot-scoped observation_id,
body_text/body_markdown, author/raw posted_at, canonical fragment, quotes/links и
attachment hints. Reply/quoted IDs связываются только с существующими уникальными
extracted source IDs и явными attributes/citations. Неизвестные поля nullable;
first visible post не объявляется original без доказательства. Отдельное хранение
raw HTML позволяет повторную extraction без потери исходного evidence.

STRUCTURED_POSTS означает найденные boundaries, не COMPLETE thread. Pagination/full-site
coverage остаются PARTIAL_OR_UNKNOWN. Scripts/embedded active elements удаляются из
readable view; attachments/images не скачиваются.

## 7. LLM Readability

[Реально созданный fixture bundle](archive/examples/README.md): 2 documents, 1 structured
thread, 2 posts. [Thread Markdown](archive/examples/research-export/threads/thread-001.md)
и [JSON](archive/examples/research-export/threads/thread-001.json) описывают одинаковые
snapshot/post/reply facts. [Отдельная проверка по 10 вопросам](LLM_READABILITY_REPORT.md).

Source IDs p100/p101 и authors заданы fixture. Reply p101→p100 и quoted reference явные;
у второго поста дата UNKNOWN/null. Manual capture не получает fictitious fetch date.
Code с `<`/`&&`, tables, quote и absolute links проверены assertions и чтением результата.
Никаких model-generated summaries или публикации материала внешним LLM не было.

Manifest schema_version=1 публикуется forced temp + no-replace hard link после всех
файлов. Directory без complete manifest — incomplete export. Existing destination
не заменяется. Bound export:100 documents/64MiB; interrupted output не удаляет archive.

## 8. Crawl Results

| Контур | Наблюдаемый результат |
| --- | --- |
| Canonical Tor/Frontier | Docker daemon socket отсутствует, актуальный runtime readiness не подтверждён; crawl/admission не выполнялись |
| Public technical fallback | robots.txt + один GET [публичной Python discussion](https://discuss.python.org/t/discussion-regarding-rules-on-the-use-of-ai/109447), без login/redirects/binaries; robots permitted, HTTP200, text/html UTF-8, identity, 60538 bytes |
| Fallback extraction | PAGE_TEXT_ONLY, 0 structured posts, POST_BOUNDARIES_NOT_ESTABLISHED; это не Archive crawler execution |
| Public persisted Archive evidence | 0 approved live plans/admissions/crawler snapshots; original raw capture вне Git в /tmp, никакого false success |
| Controlled persisted fixture | approved plan budget3/depth2; manual HTML discussion + Markdown guide, 2 documents/2 posts, search/raw hashes/replies/export/reopen проверены; admission UNOBSERVED |

Fallback GET source SHA-256:
`5485accca60f055e617a02c3781a1b091454999e6119c96e5d9738b083421613`.
Источник выбран поиском публичной технической площадки; onion address для live crawl
не выдумывался. Fixture onion из существующего repository test никогда не запрашивался.

## 9. Tests

Окружение: OpenJDK25, Maven, H2 2.4.240/JUnit4.13.2, существующий checkout-local
cache; dependencies и toolchain не изменялись. Local gRPC/SOCKS tests выполнялись
с разрешённым loopback, без обхода TorPolicy для crawler.

| Gate | Фактический результат |
| --- | --- |
| Ownership baseline | 6 tests, 4 failures + 1 error; confirmed provenance/capacity/close/ownership defects |
| Research standalone baseline | 5 tests, 4 failures; unsafe cleanup/ambiguous commit подтверждены на прежних classes |
| Archive/research suite до final compatibility | 116 tests,0 failures/errors/skips |
| Archive/research + Admission/status integration после fixes | **129 tests,0 failures/errors/skips** |
| Brand geometry regression после explicit fix | **5 tests,0 failures/errors/skips**, widths2,7,8,9,10,11 и прежние layouts |
| Финальный полный root verify после Brand fix | **416 tests,0 failures,0 errors,0 skipped; BUILD SUCCESS**, JAR/shade собраны |
| Реальный fixture export QA | 1 targeted test,0 failures; paired JSON/Markdown/source и manifest прочитаны/сверены |
| JDK Javadoc doclint | exit0 без warnings/errors, owner custom tags сохранены |
| Markdown links / whitespace | PASS / git diff --check PASS |
| Scope preservation |16 исходных non-Archive diffs побайтно сохранены; Brand разрешён отдельно, index не тронут |

Основные команды из repository root:

```sh
mvn -B -o -Dkotlin.compiler.daemon=false -Dmaven.repo.local=.runtime/maven-linux-repository -Dtest='dev.onionall.brawler.archive.**,ResearchCatalogTest,ResearchWorkflowConcurrencyTest,ResearchThreadsTest,AdmissionClientTest,CrawlExecutionTest,JobStatusIntegrationTest' test
mvn -B -o -Dkotlin.compiler.daemon=false -Dmaven.repo.local=.runtime/maven-linux-repository -Dtest=InteractiveSessionTest test
mvn -B -o -Dkotlin.compiler.daemon=false -Dmaven.repo.local=.runtime/maven-linux-repository verify
python -B scripts/runners/verify.py --links-only
git diff --check
```

Отдельная package verification с `-DskipTests verify` тоже прошла, но финальный
BUILD SUCCESS выше получен **без** skipTests. Fixture bundle сначала создан Maven
method-selected test, затем финально перегенерирован JUnitCore Request.method тем
же test и тем же compiled code; старый собственный preview сохранён вне repository.
Javadoc: `-Xdoclint:all,-missing -package` с разрешением существующих owner tags
`Refactor:`/`Documented:` и resolved classpath, output `/tmp/archive-hardening-javadoc`.

Промежуточные ошибки не скрыты: старый reflection fixture потребовал обновления
после user ArchiveStoreNode refactor; strict managed-field rejection сломал 4
read-modify-write integrations и был исправлен. В полном verify до Brand fix оставался
1 out-of-scope CLI error; после explicit user authorization его исправление
подтверждено полным зелёным запуском. Assertions не ослаблялись; geometry range расширен.

Логи: `/tmp/archive-hardening-ownership-before.log`,
`/tmp/archive-hardening-research-before.log`, `/tmp/archive-hardening-compatibility-final.log`,
`/tmp/archive-brand-fix-tests.log`, `/tmp/archive-hardening-verify-after-brand.log`,
`/tmp/archive-hardening-javadoc.log`. Raw public fallback metadata:
`/tmp/archive-public-forum-readiness.json`; forum body не включён в Git.

Не выполнены canonical Docker/Storm/Frontier/Tor crawl, отдельный Frontier build,
packaged CLI/PTy acceptance, power-loss crash tests и empirical external-LLM evaluation.
Ни один из этих контуров не выдан за успешный по результату unit tests.

## 10. Git

Исходный HEAD: `b3650f77af819d2461cd216ee7642baafbb7f114`.
Текущая ветка **feature/m61-forum-research** сохранена по последующей инструкции
пользователя; feat/m61-forum-research не создавалась. Никакой истории/reset/force push.

Commit не создан, index не изменён: до начала уже были смешанные пользовательские
Archive и non-Archive изменения. Они не включались в чужой commit. Push не выполнялся, хотя финальный root verify зелёный:
обязательный полноценный crawl gate не пройден, origin transport не смог обновить
Entire credentials из-за DNS timeout eu.auth.entire.io. Другой remote не подставлялся.
После разрешения blockers нужен review собственного Archive delta относительно
сохранённого baseline, затем explicit staging без пользовательских правок.

## 11. Remaining TODO

| Владелец | Точный следующий контракт |
| --- | --- |
| Tor/Crawl configuration | отдельный approved bounded textual-document mode, строгий MIME/encoding/size и robots/same-origin; не снимать HTML guards глобально |
| Storm parser owner | отдельный text/markdown parser/filter route, сохраняющий original bytes, charset и callback outcomes; unsupported MIME не должен теряться молча |
| CLI/application research | routing document_format/document_id → researchDocument/exportResearch; pages/page/ResearchFiles остаются HTML-only и не должны parse-ить .txt как HTML |
| Worker/application lifecycle | FAIL-002-PRODUCER: bounded stop/pause/recovery после archive observation saturation, без dropped evidence, retry storm и fake Frontier ACK |
| Archive storage | BLOB-001 verified directory-sync/crash model; BLOB-002 reference-safe recovery/GC с active publisher ownership, без автоматического удаления по age |
| Archive forum adapters | Discourse/XenForo и cross-page/root-post coverage требуют отдельных real-layout fixtures; PAGE_TEXT_ONLY сохраняется честным fallback |
| Runtime/operator | существующий authorized Tor/Frontier runtime + safe permitted source и поддерживаемый crawl harness; затем ≤20 pages, depth≤2,1 worker, low pacing и persisted readback |
| Publication/operator | рабочая origin authentication и отделение пользовательских baseline changes; commit/push после разрешённых acceptance gates |

128MiB H2 guard — pre-write threshold, не filesystem quota: H2 allocation/commit может
увеличить физический файл. Cooperating writers сериализованы; malicious external FS/SQL
writers, symlink TOCTOU и power-loss correctness не заявлены. Raw source/schema
сохраняются без destructive cleanup.

## 12. Next Steps

Сначала включить отдельный approved text transport/parser path у перечисленных
владельцев, подключить Archive API к CLI, затем выполнить реальный bounded crawl и
проверить persisted discussion completeness. Расширять forum contracts по конкретным
fixtures, сохраняя unknowns и исходный HTML; discovery-event journal и GC проектировать
отдельно. Ни эти future capabilities, ни generic worker exit не выдаются за текущие
доказательства успешного research acquisition.
