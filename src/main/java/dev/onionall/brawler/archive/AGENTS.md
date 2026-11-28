# archive: каталог, документы и наблюдения

Наследует [общие Java-соглашения](../AGENTS.md).
Хранит планы/approval, immutable HTML, snapshots, provenance, связи и failure observations.
Admission, quota, leases и retry scheduling принадлежат Frontier.
M6 добавляет Kotlin ResearchCatalog с additive `research_sessions_v1` в этом же H2.
Методы createResearchSession/researchSession/updateResearchSession/researchSessionExists
— только compilation bridge; JDBC connection не покидает ArchiveContext. Записи сохраняют
catalog_guard/CAS revision и capacity guard; core schema version не меняется.

## Классы

| Класс | Роль |
| --- | --- |
| [ArchiveStore](ArchiveStore.java) | Стабильный facade; все API передаются через context monitor |
| [ArchiveContext](internal/ArchiveContext.java) | Единственный владелец connection, transaction/rollback/cleanup/close и capacity guard |
| [ArchiveSchema](internal/ArchiveSchema.java) | Core-v2 validation и restart-idempotent DDL при открытии |
| [PlanRepository](internal/PlanRepository.java) | Specification, approval, canonical scope и плановые queries |
| [ObservationRepository](internal/ObservationRepository.java) | Admission JSON и bounded status transitions |
| [BlobStore](internal/BlobStore.java) | SHA-256 verification, capacity и immutable no-replace publication |
| [SnapshotRepository](internal/SnapshotRepository.java) | Archive/import, identity, versions, latest, HTML search metadata и verified page readback |
| [GraphRepository](internal/GraphRepository.java) | First-snapshot edges, provenance и same-job target resolution |
| [FailureRepository](internal/FailureRepository.java) | Aggregate + observation transaction и sequence pagination |
| [ArchiveQueryService](internal/ArchiveQueryService.java) | Job projection, search и export |
| [ResearchSessions](internal/ResearchSessions.java) | Bridge к заранее созданному borrowed ResearchCatalog |
| [ArchiveSupport](internal/ArchiveSupport.java) | Общие validation/JSON/projection helpers и distinct URL budget |
| [DocumentRepository](internal/DocumentRepository.java) | Additive v1 UTF-8 Markdown/plain text, immutable versions и .txt blobs |
| [ResearchExportService](internal/ResearchExportService.java) | Guarded verified capture HTML/text и передача exporter вне JDBC lock |
| ArchiveTextDocuments (Kotlin archive) | Strict UTF-8/media validation, lossless source и inert Markdown |
| ArchiveForumExtractor / NativeContent (Kotlin archive + Rust) | Controlled phpBB/semantic post structure, explicit replies, bounded inert Markdown |
| ArchiveResearchBundle (Kotlin archive) | Paired JSON/Markdown bundle, atomic final manifest, no overwrite |
| [ArchiveParseFilter](ArchiveParseFilter.java) | Проверка pipeline config/approval и commit HTML до parser emission |
| [ArchiveStatusUpdaterBolt](ArchiveStatusUpdaterBolt.java) | Stock-Frontier fixture updater; общая recordOutcome перед store superclass |
| [CapabilityArchiveStatusUpdaterBolt](CapabilityArchiveStatusUpdaterBolt.java) | Product updater поверх capability-aware адаптера с тем же архивным наблюдением |
| [FailureObservation](FailureObservation.java) | Record phase/source/httpStatus/retryable/terminal/nextDueEpoch/transport; null сохраняет неизвестность |
| [SearchProvider](SearchProvider.java) | search-контракт; LocalArchive читает каталог, AssistedAhmia возвращает ручную official_url |

## API и возвращаемые данные

[ArchiveStore.java](ArchiveStore.java):

```java
public ArchiveStore(Path location) throws IOException;
public Map<String, Object> createPlan(List<String> seeds, int depth, int maxPages, String provider) throws IOException;
public Map<String, Object> plans(String after, int limit) throws IOException;
public Map<String, Object> plan(String id) throws IOException;
public Map<String, Object> approvePlan(String id) throws IOException;
public void recordAdmission(String id, Map<String, Object> observation) throws IOException;
public void recordJobStatus(String id, Map<String, Object> status) throws IOException;
public boolean hasExecutionHistory() throws IOException;
public Map<String, Object> admissionObservation(String id) throws IOException;
public Map<String, Object> job(String id) throws IOException;
public Map<String, Object> archive(String jobId, String url, int depth, String source, int status, String contentType, byte[] html, List<Map<String, Object>> links) throws IOException;
public Map<String, Object> importHtml(String jobId, String url, byte[] html, List<Map<String, Object>> links) throws IOException;
public Map<String, Object> pages(String jobId, int limit) throws IOException;
public Map<String, Object> page(String pageId) throws IOException;
public Map<String, Object> links(String jobId, int limit) throws IOException;
public Map<String, Object> search(String query, String jobId, int limit) throws IOException;
public Map<String, Object> search(String query, String jobId, String host, int limit) throws IOException;
public Map<String, Object> exportSummary(String jobId, int limit) throws IOException;
public Map<String, Object> recordFailure(String jobId, String url, String category) throws IOException;
public Map<String, Object> recordFailure(String jobId, String url, String category, FailureObservation observation) throws IOException;
public Map<String, Object> failureHistory(String jobId, String cursor, int limit) throws IOException;
public void close() throws IOException;
```

Map-результаты сохраняют существующие JSON keys. plan возвращает карту сохранённой specification; approvePlan обновляет approval в БД. job добавляет наблюдения/счётчики.
recordFailure возвращает failure_id/job_id/url/category; failureHistory возвращает rows,
through_sequence, next_cursor и nullable fetch_attempt_count.

jobId у pages/search может быть null для общего просмотра. id/pageId обозначают UUID,
cursor принадлежит конкретной истории, limit проверяется в диапазоне 1..100.
Search сопоставляет literal URL/title/text; необязательные jobId и валидный onion
host ограничивают SQL до LIMIT. Это bounded catalog query, не внешний provider.
HTML и text search SQL/row mapping принадлежат SnapshotRepository и DocumentRepository.
ArchiveQueryService проверяет общие фильтры и объединяет detached metadata под одним
consistentRead/catalog_guard; SQL latest projection и ResultSet mapper snapshots
не передаются в orchestration. Общая сортировка и итоговый LIMIT остаются в сервисе.
archive принимает crawler status/contentType и discovery provenance; importHtml явно
использует source_kind=MANUAL_IMPORT, http_status=0 и fetched_at=null.

[ArchiveParseFilter.java](ArchiveParseFilter.java):

```java
public void configure(Map<String, Object> conf, JsonNode params, String name);
static Path requiredRoot(Map<String, Object> conf);
static String requiredJob(Map<String, Object> conf);
public void filter(String url, byte[] content, DocumentFragment fragment, ParseResult parse);
```

[ArchiveStatusUpdaterBolt.java](ArchiveStatusUpdaterBolt.java):

```java
public void prepare(Map<String, Object> conf, TopologyContext context, OutputCollector collector);
static void recordOutcome(Path root, String job, String url, Status status, Metadata metadata, Optional<Date> nextFetch) throws IOException;
public void store(String url, Status status, Metadata metadata, Optional<Date> nextFetch, Tuple tuple);
```

[CapabilityArchiveStatusUpdaterBolt.java](CapabilityArchiveStatusUpdaterBolt.java):

```java
public void prepare(Map<String, Object> conf, TopologyContext context, OutputCollector collector);
public void store(String url, Status status, Metadata metadata, Optional<Date> nextFetch, Tuple tuple);
```

[SearchProvider.java](SearchProvider.java):

```java
abstract Map<String, Object> search(String query, int limit) throws IOException;
```

Реализации search имеют ту же сигнатуру. AssistedAhmia проверяет query ≤256 и limit 1..100,
возвращает USER_ACTION_REQUIRED/network_requested=false, без HTTP.

## Persistence и ограничения

- ArchiveStore создаёт каталоги и применяет additive DDL при открытии. Core schema version=2;
  failure_observations_v1 и status audit имеют отдельное additive представление.
  Новая несовместимая schema version запрещает открытие.
- H2 использует AUTO_SERVER=FALSE, WRITE_DELAY=0, DB_CLOSE_ON_EXIT=FALSE.
  Все публичные операции, research и close сериализованы monitor ArchiveContext.
  serializableRead означает Java monitor, не SQL isolation/read-only. consistentRead
  job/search/export/failure-history/researchDocument использует catalog_guard transaction;
  внутри существующей transaction переиспользует её без nested commit. Простые metadata
  reads остаются autocommit. Create/approve plan, observations,
  snapshots, failures и research mutations используют одну context transaction и
  catalog_guard FOR UPDATE, сериализующий writers разных экземпляров в одном JVM.
  Repositories не меняют autocommit/commit/rollback и не сохраняют borrowed connection.
  Это не многопроцессный сетевой каталог. Закрывайте экземпляр после операции.
- Пути проверяются на symlink, содержимое хранится по SHA256. Проверки путей не являются
  общей защитой от всех filesystem races. Blob force, digest verification и atomic
  hard-link creation без замены предшествуют snapshot commit, включая dedup replay.
  Unsupported hard links запрещают публикацию; unsafe move fallback отсутствует.
  Existing corrupt object отклоняется; missing object можно восстановить replay.
  При SQL-ошибке rollback не удаляет уже опубликованный blob. Directory fsync не
  выполняется: power-loss durability не заявлена. Capacity scan консервативно учитывает
  retained temp/orphan files; нет destructive cleanup или непроверенного cached counter.
- HTML и каждый text document ограничены 1..131072 bytes, объекты суммарно 64 MiB, H2 write guard 128 MiB.
  Планы ограничены 1..100 seeds/pages, depth 0..5 и одним origin; links ограничены 100.
  Превышение лимита не запускает удаление данных.
- Replay одинакового content_hash/source_kind переиспользует snapshot и обновляет latest/last_seen.
  Identity = page_id + content_hash + source_kind. История и page_links сохраняют
  provenance первого snapshot; повторные discovery dispositions/referrers не являются
  отдельным журналом событий (ARCH-001). Не добавляйте их silently в immutable edges:
  требуется отдельный bounded event contract/migration review. Manual import не
  подтверждает transport или robots.
- recordAdmission не меняет specification/approval, в том числе plan_id/provider/created_at;
  matching plan_id разрешён для worker callers. Audit/provenance fields принадлежат
  repository; точное эхо текущих полей принимается под guard и удаляется из input,
  conflicting/new/stale managed fields отклоняются. Status-only row не получает
  admission authority/clock; private JSON marker _archive_admission_observed_at_v1
  отличает новый admission call и не выходит в public projection. Legacy пустой JSON
  неоднозначен и не получает выдуманную admission provenance. job фильтрует authoritative keys даже
  из legacy observations; state/admission/status остаются observational overlays.
  Null/non-object observation JSON отклоняется IOException.
  recordJobStatus проверяет версию представления,
  хранит до 32 transitions и сохраняет admission clock при статусной записи.
- Legacy failures агрегируются по URL/category; failure_observations_v1 добавляет отдельные записи
  с bounded sequence cursor. Оба набора ограничены; они не являются счётчиком network attempts.
- recordOutcome читает final scheduler intent superclass и пишет каталог до Frontier store.
  Nullable applied_to_frontier в истории не превращается в доказательство сетевого ACK.
- ArchiveParseFilter требует approved job, TorProtocol для HTTP/HTTPS и protocol.md.prefix.
  Parser exception попадает в установленный ERROR-путь. Его нельзя считать гарантированным
  tuple fail: существующий parser может выдать ERROR и ACK.
- Поиск использует literal LOCATE по URL/title/extracted_text; он не является full-text index.

## Изменения и зависимости

Пакет зависит от H2/JDBC, JSoup, Kotlin TorPolicy, StormCrawler parser/status API
и [capability adapters](../../../../org/apache/stormcrawler/urlfrontier/AGENTS.md).
Сохраняйте порядок blob → commit → parser emission и observation → Frontier store,
SQL schema/JSON compatibility и distinction между наблюдением и scheduling authority.

Формат и Javadoc: [общие соглашения](../AGENTS.md).
Полезные внешние контракты: [архив](../../../../../../../docs/archive-mvp.md),
[fixtures](../../../../../../../test/archive/AGENTS.md).

## Ownership и failure semantics

Направление зависимостей: facade → internal repositories/query services → context
и validation/blob helpers; context → JDBC/schema/borrowed ResearchCatalog. Внутренние
public типы доступны facade через Java package boundary, не являются новым внешним API.

Lock order для записи: context monitor → JDBC begin → H2 catalog_guard → filesystem
либо ResearchCatalog connection monitor/savepoint и повторный тот же guard. Research
read: context monitor → connection monitor → JDBC read. Connection не выдаётся callers,
обратного пути connection monitor → context нет. Close берёт тот же context monitor,
ждёт активный вызов; reentrant close запрещён во всех active callbacks (operationDepth). Nested context transactions
запрещены; research savepoint не коммитит внешнюю transaction и не делает DDL внутри неё.

Body failure откатывается, в том числе RuntimeException/Error. Primary exception
сохраняется, cleanup добавляется suppressed. Rollback failure не включает autocommit;
context запрещает дальнейшие операции и закрывает connection. Commit failure имеет
UNKNOWN outcome даже после cleanup rollback; безопасно открыть каталог заново и
проверить/replay operation. Commit success с restoration/readback failure остаётся
COMMITTED: readback находится за пределами transaction handler. Повторный close
после неудачного освобождения допускается, после успешного не обращается к JDBC.

Loom не используется: virtual threads не меняют single-connection ownership; для
bounded archive отсутствует подтверждённая задача роста concurrency. Составные
consistentRead projections согласованы относительно cooperating writers с catalog_guard;
прямой SQL writer, обходящий guard, не включён в guarantee. Storage предполагает
доверенный локальный каталог. Timestamp ordering использует TIMESTAMP(9) WITH TIME ZONE,
без переписывания legacy strings. Archive response возвращает exact committed snapshot;
page() по-прежнему возвращает latest observed (включая A→B→A replay).

## Проверки

JDK 25, Maven, H2 2.4.240, JUnit 4.13.2; versions не меняются. Для данного checkout:

```sh
mvn -B -o -Dkotlin.compiler.daemon=false -Dmaven.repo.local=.runtime/maven-linux-repository -Dtest='dev.onionall.brawler.archive.**,ResearchCatalogTest,ResearchWorkflowConcurrencyTest,ResearchThreadsTest' test
mvn -B -o -Dkotlin.compiler.daemon=false -Dmaven.repo.local=.runtime/maven-linux-repository verify
```

Без готового local cache используйте штатную сборку из root AGENTS. Полный verify
требует разрешённых loopback sockets. Unit proxy tests проверяют coordinator faults;
реальный disposable H2 — concurrency/reopen/readback. Они не доказывают Tor crawl.
Архитектура, baseline и фактические проверки: [отчёт](../../../../../../../docs/engineering/archive-modularization.md).

## Текстовые документы и research export

Core schema остаётся 2. ArchiveSchema добавляет independently versioned
archive_document_pages_v1 / archive_document_versions_v1: FK к plan/page, unique
version identity и composite latest-owner FK. HTML tables и objects/<sha>.html не
перепрофилируются. Text bytes лежат в objects/<sha>.txt; общий 64 MiB scan включает оба
namespace и orphan/temp. URL budget считается UNION HTML/text canonical URLs под guard.
До эксплуатации смешанного архива обновляйте все writers; прежние binaries могут
читать HTML/core-v2, но не знают text rows и их quota contribution.

Новые facade API: importDocument(jobId,url,contentType,bytes), archiveDocument(jobId,url,
depth,source,status,contentType,bytes), researchDocument(documentId),
exportResearch(jobId,destination,limit). HTML import сохраняет прежний pipeline.
Text import требует явного text/plain или text/markdown с UTF-8 (без charset тоже
UTF-8), валидных bytes и отсутствия binary controls. Extension .md не определяет
формат; missing/unsupported/malformed MIME и charset отклоняются. Raw source сохраняется
полностью; Markdown link extraction консервативна и bounded100, не full CommonMark.
Unsafe destination schemes и ambiguous code delimiters дают literal fenced fallback,
с limitation. Raw HTML инертен, images становятся ссылками; HTTP relative links
разрешаются относительно source URL, code fences/inline code не переписываются.

ArchiveParseFilter умеет сохранять text callback, только если он уже вызван parser;
проверяет identity/no encoding. HTML выставляет archive.page.id, text —
archive.document.id/archive.document.format. Это не снимает upstream HTML-only gates:
TorProtocol/generated topology блокируют Markdown до Archive, JSoup unsupported MIME
может вообще не вызвать parse filters. Владельцы Tor/Crawl/Storm должны отдельно
согласовать bounded text-parser путь; CLI/read-only research должны выбирать
researchDocument по format. Старые pages/page/ResearchFiles остаются HTML-only;
search включает обе source types и выдаёт document_format/document_id для text.

Forum extraction переиспользует существующий read-only ForumExtractor для controlled
div.post/postbody/content и schema.org DiscussionForumPosting articles. Source post ID
отделён от snapshot-scoped observation ID. Author/date/thread/forum берутся только из
явных DOM fields, replies/quoted IDs связываются только с уникальными extracted post IDs.
Unknown layout даёт PAGE_TEXT_ONLY; Discourse/XenForo универсально не поддерживаются.
Даже STRUCTURED_POSTS не доказывает полноту темы; coverage остаётся PARTIAL_OR_UNKNOWN.
Attachments — только link hints, без загрузки. Script/active elements не исполняются.

Export создаёт новый destination без overwrite: research-index.md, paired JSON/Markdown
threads/documents и raw text .source.txt. Manifest schema_version=1 публикуется последним
через forced temp + no-replace hard link. Directory без complete manifest — incomplete,
его нельзя выдавать за результат. Export ограничен100 documents/64 MiB, phase capture
под guard отделена от filesystem output. Markdown не содержит generated summaries.

Текущие TODO: BLOB-001 (power-loss protocol), BLOB-002 (проверяемый recovery/GC без
автоудаления), FAIL-002-PRODUCER (worker stop/recovery после saturation). First-snapshot
links — существующий immutable contract, не полный discovery event journal.
Архивный hardening: [отчёт](../../../../../../../docs/archive-hardening-report.md),
[readability](../../../../../../../docs/LLM_READABILITY_REPORT.md),
[fixture example](../../../../../../../docs/archive/examples/README.md).
