# archive: каталог, HTML и наблюдения

Наследует [общие Java-соглашения](../AGENTS.md).
Хранит планы/approval, immutable HTML, snapshots, provenance, связи и failure observations.
Admission, quota, leases и retry scheduling принадлежат Frontier.
M6 добавляет Kotlin ResearchCatalog с additive `research_sessions_v1` в этом же H2.
Методы createResearchSession/researchSession/updateResearchSession/researchSessionExists
— только compilation bridge; JDBC connection не покидает ArchiveStore. Записи сохраняют
catalog_guard/CAS revision и capacity guard; core schema version не меняется.

## Классы

| Класс | Роль |
| --- | --- |
| [ArchiveStore](ArchiveStore.java) | AutoCloseable владелец embedded H2 connection и objects/; планы, каталог, поиск, граф, наблюдения |
| [ArchiveParseFilter](ArchiveParseFilter.java) | Проверка pipeline config/approval и commit HTML до parser emission |
| [ArchiveStatusUpdaterBolt](ArchiveStatusUpdaterBolt.java) | Stock-Frontier fixture updater; общая recordOutcome перед store superclass |
| [CapabilityArchiveStatusUpdaterBolt](CapabilityArchiveStatusUpdaterBolt.java) | Product updater поверх capability-aware адаптера с тем же архивным наблюдением |
| [FailureObservation](FailureObservation.java) | Record phase/source/httpStatus/retryable/terminal/nextDueEpoch/transport; null сохраняет неизвестность |
| [SearchProvider](SearchProvider.java) | search-контракт; LocalArchive читает каталог, AssistedAhmia возвращает ручную official_url |

## API и возвращаемые данные

[ArchiveStore.java](ArchiveStore.java):

```java
public ArchiveStore(Path location) throws IOException;
public synchronized Map<String, Object> createPlan(List<String> seeds, int depth, int maxPages, String provider) throws IOException;
public synchronized Map<String, Object> plans(String after, int limit) throws IOException;
public synchronized Map<String, Object> plan(String id) throws IOException;
public synchronized Map<String, Object> approvePlan(String id) throws IOException;
public synchronized void recordAdmission(String id, Map<String, Object> observation) throws IOException;
public synchronized void recordJobStatus(String id, Map<String, Object> status) throws IOException;
public synchronized boolean hasExecutionHistory() throws IOException;
public synchronized Map<String, Object> admissionObservation(String id) throws IOException;
public synchronized Map<String, Object> job(String id) throws IOException;
public synchronized Map<String, Object> archive(String jobId, String url, int depth, String source, int status, String contentType, byte[] html, List<Map<String, Object>> links) throws IOException;
public synchronized Map<String, Object> importHtml(String jobId, String url, byte[] html, List<Map<String, Object>> links) throws IOException;
public synchronized Map<String, Object> pages(String jobId, int limit) throws IOException;
public synchronized Map<String, Object> page(String pageId) throws IOException;
public synchronized Map<String, Object> links(String jobId, int limit) throws IOException;
public synchronized Map<String, Object> search(String query, String jobId, int limit) throws IOException;
public synchronized Map<String, Object> search(String query, String jobId, String host, int limit) throws IOException;
public synchronized Map<String, Object> exportSummary(String jobId, int limit) throws IOException;
public synchronized Map<String, Object> recordFailure(String jobId, String url, String category) throws IOException;
public synchronized Map<String, Object> recordFailure(String jobId, String url, String category, FailureObservation observation) throws IOException;
public synchronized Map<String, Object> failureHistory(String jobId, String cursor, int limit) throws IOException;
public synchronized void close() throws IOException;
```

Map-результаты сохраняют существующие JSON keys. plan возвращает карту сохранённой specification; approvePlan обновляет approval в БД. job добавляет наблюдения/счётчики.
recordFailure возвращает failure_id/job_id/url/category; failureHistory возвращает rows,
through_sequence, next_cursor и nullable fetch_attempt_count.

jobId у pages/search может быть null для общего просмотра. id/pageId обозначают UUID,
cursor принадлежит конкретной истории, limit проверяется в диапазоне 1..100.
Search сопоставляет literal URL/title/text; необязательные jobId и валидный onion
host ограничивают SQL до LIMIT. Это bounded catalog query, не внешний provider.
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
  Публичные операции экземпляра (кроме конструктора) synchronized; SQL catalog_guard FOR UPDATE сериализует транзакции.
  Это не многопроцессный сетевой каталог. Закрывайте экземпляр после операции.
- Пути проверяются на symlink, содержимое хранится по SHA256. Проверки путей не являются
  общей защитой от всех filesystem races. Blob force и atomic move предшествуют snapshot commit.
  При SQL-ошибке rollback не удаляет уже опубликованный blob.
- HTML ограничен 1..131072 bytes, объекты суммарно 64 MiB, H2 write guard 128 MiB.
  Планы ограничены 1..100 seeds/pages, depth 0..5 и одним origin; links ограничены 100.
  Превышение лимита не запускает удаление данных.
- Replay одинакового content_hash/source_kind переиспользует snapshot и обновляет latest/last_seen.
  История содержит provenance; manual import не подтверждает transport или robots.
- recordAdmission не меняет specification/approval; recordJobStatus проверяет версию представления,
  хранит до 32 transitions и сохраняет admission clock при статусной записи.
- Legacy failures агрегируются по URL/category; failure_observations_v1 добавляет отдельные записи
  с bounded sequence cursor. Оба набора ограничены; они не являются счётчиком network attempts.
- recordOutcome читает final scheduler intent superclass и пишет каталог до Frontier store.
  Nullable applied_to_frontier в истории не превращается в доказательство сетевого ACK.
- ArchiveParseFilter требует approved job, TorProtocol для HTTP/HTTPS и protocol.md.prefix.
  Parser exception попадает в установленный ERROR-путь. Его нельзя считать гарантированным
  tuple fail: существующий parser может выдать ERROR и ACK.
- Поиск использует локальный LIKE по title/extracted_text; он не является full-text index.

## Изменения и зависимости

Пакет зависит от H2/JDBC, JSoup, Kotlin TorPolicy, StormCrawler parser/status API
и [capability adapters](../../../../org/apache/stormcrawler/urlfrontier/AGENTS.md).
Сохраняйте порядок blob → commit → parser emission и observation → Frontier store,
SQL schema/JSON compatibility и distinction между наблюдением и scheduling authority.

Формат и Javadoc: [общие соглашения](../AGENTS.md).
Полезные внешние контракты: [архив](../../../../../../../docs/archive-mvp.md),
[fixtures](../../../../../../../test/archive/AGENTS.md).
