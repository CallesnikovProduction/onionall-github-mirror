# admission: Frontier, запуск и ownership

Наследует [общие Java-соглашения](../AGENTS.md).
Admission отвечает на вопрос: можно ли резервировать URL в этой сессии и
как проверить подтверждённое серверное резервирование. Пакет также содержит
исторически связанные package-private части локального исполнения.

## Классы

| Класс | Фактическая роль |
| --- | --- |
| [AdmissionClient](AdmissionClient.java) | Bounded unary JSON RPC Admission/Command, list/counts через тот же authenticated endpoint |
| [CrawlAdmissionService](CrawlAdmissionService.java) | Open, сверка spec, reserve seeds, reservation readback и повторная сверка session |
| [FrontierCapability](FrontierCapability.java) | Чтение capability-файла и CallCredentials для legacy RPC |
| [FrontierReadback](FrontierReadback.java) | Строгая проверка envelope, page/cursor/counts/spec; State, Row, Page, Counts описывают ответы |
| [DurableDiscoveryFilter](DurableDiscoveryFilter.java) | Bounded извлечение графа, durable admission outlinks и discovery metadata |
| [JobStatus](JobStatus.java) | Чистая оценка состояния по approved/admitted, counts и Worker; возвращает Assessment |
| [JobStatusView](JobStatusView.java) | Версионированная Map статуса, отделяющая свежий readback от кэша |
| [CrawlExecution](CrawlExecution.java) | Approval, startup-lock, preparation, admission, bounded worker, итоговое сохранение и status |
| [CrawlRuntimePreparer](CrawlRuntimePreparer.java) | JSON конфигурации/фильтров/топологии и отдельный worker JAR; общий атомарный JSON writer |
| [CrawlResources](CrawlResources.java) | Bounded classpath resources внутри копии исходного JAR |
| [CrawlTopology](CrawlTopology.java) | Канонический Flux graph без побочных эффектов |
| [CrawlWorker](CrawlWorker.java) | Публичный wrapper entrypoint, child-owned lock и deadline; OwnedChild отслеживает descendants/cleanup |
| [WorkerLauncher](WorkerLauncher.java) | ProcessBuilder, handoff, readiness, bounded wait и identity-protected stop |
| [WorkerOwnership](WorkerOwnership.java) | Crash-журнал и Preparation: PREPARING/LAUNCHING/RUNNING/STOPPED/UNKNOWN |
| [WorkerObservation](WorkerObservation.java) | Read-only наблюдение identity/scope/lock; Observation содержит nullable состояние и детали |

## Основные API

[AdmissionClient.java](AdmissionClient.java):

```java
public AdmissionClient(String host, int port, String token) throws IOException;
public static AdmissionClient fromRuntime(Map<String, Object> runtime, String token) throws IOException;
public static AdmissionClient fromCapabilityFile(String host, int port, Path file) throws IOException;
public static AdmissionClient fromRuntimeWithCapabilityFile(Map<String, Object> runtime, Path file) throws IOException;
public Map<String, Object> command(Map<String, Object> request) throws IOException;
public FrontierReadback.Page list(String session, String cursor, int limit) throws IOException;
public FrontierReadback.Counts counts(String session) throws IOException;
public void close();
```

[CrawlAdmissionService.java](CrawlAdmissionService.java):

```java
static Map<String, Object> admit(String id, Map<String, Object> plan, Map<String, Object> runtime, String capability) throws IOException;
static Map<String, Object> admitWithCapabilityFile(String id, Map<String, Object> plan, Map<String, Object> runtime, Path capabilityFile) throws IOException;
```

[FrontierCapability.java](FrontierCapability.java):

```java
public static FrontierCapability from(Map<String, Object> conf);
public void applyRequestMetadata(RequestInfo request, Executor executor, MetadataApplier applier);
public static String token(Path file) throws IOException;
```

[FrontierReadback.java](FrontierReadback.java):

```java
public static void verifySession(Map<String, Object> session, Map<String, Object> plan) throws IOException;
static Page page(Map<String, Object> reply, String session, String offeredCursor, int limit) throws IOException;
static Counts counts(Map<String, Object> reply, String session) throws IOException;
```

[CrawlExecution.java](CrawlExecution.java):

```java
public static Map<String, Object> status(Path archive, String id, Path runtimeFile, Path tokenFile) throws IOException;
public static Map<String, Object> start(Path archive, String id, Path runtimeFile, Path tokenFile) throws Exception;
public static void verifySession(Map<String, Object> session, Map<String, Object> plan) throws IOException;
static void stop(Process worker, Instant identity);
```

[CrawlRuntimePreparer.java](CrawlRuntimePreparer.java):

```java
static Path prepare(Map<String, Object> plan, Map<String, Object> runtime, Path archive, Path tokenFile, Path work, Path jar, int httpTimeout, int retryMinutes) throws IOException;
static void write(Path path, Object value) throws IOException;
```

[CrawlResources.java](CrawlResources.java):

```java
static void publish(Path original, Path destination, Map<String, byte[]> resources) throws IOException;
```

[CrawlTopology.java](CrawlTopology.java):

```java
static Map<String, Object> build(Path config);
```

[CrawlWorker.java](CrawlWorker.java):

```java
public static void main(String[] args) throws Exception;
static void requireAvailable(Path path) throws IOException;
```

[WorkerLauncher.java](WorkerLauncher.java):

```java
static Map<String, Object> run(Path archive, String id, Path work, Path storm, Path jobJar, Path runtimeWorkerLock, int seconds, WorkerOwnership.Preparation preparation, Map<String, Object> session) throws IOException;
static void stop(Process worker, Instant identity);
```

[WorkerOwnership.java](WorkerOwnership.java):

```java
static Map<String, Object> read(Path lock) throws IOException;
static void requireAvailable(Path lock) throws IOException;
static void publish(Path lock, Map<String, Object> value) throws IOException;
static Map<String, Object> claim(Path lock, boolean fresh, String ticket) throws IOException;
```

[WorkerOwnership.java](WorkerOwnership.java):

```java
Preparation(Path path) throws IOException;
Preparation(Path path, String plan) throws IOException;
String handoff() throws IOException;
public void close() throws IOException;
```

[WorkerObservation.java](WorkerObservation.java):

```java
static String scope();
static String start(long pid);
static Boolean alive(Object pid, Object expected);
public static Observation unavailable(String basis);
public static Observation read(Path execution, Path archive, String plan);
```

[JobStatus.java](JobStatus.java):

```java
public static Assessment assess(boolean approved, boolean admitted, boolean readbackRequested, FrontierReadback.Counts counts, Worker worker);
```

[JobStatusView.java](JobStatusView.java):

```java
public static Map<String, Object> assemble(Map<String, Object> catalog, FrontierReadback.Counts c, WorkerObservation.Observation worker, Boolean reachable, boolean requested, String catalogAt);
```


## Совместимые forwarding API

CrawlExecution.verifySession(Map<String, Object> session, Map<String, Object> plan)
сохраняет public API и делегирует FrontierReadback.verifySession(session, plan).
Реализация проверки spec находится только в FrontierReadback;
JobStatusService использует её напрямую, не через координатор.

Package-private CrawlExecution.stop(Process worker, Instant identity) делегирует
WorkerLauncher.stop(worker, identity). Проверка start instant, сигналы и ожидание
реализованы только в WorkerLauncher. Это две точки входа, не две реализации cleanup.
CrawlExecution.status делегирует JobStatusService.reconcileStatus с сохранением его writes.

## Admission и credentials

CrawlExecution читает approval до admission; Frontier остаётся authority.
Open и итоговый session readback сверяются с plan_id/origin/seeds/max_pages/depth/approved.
Каждый seed требует reserve и отдельный reservation readback с тем же reservation_id.
Архив хранит checkpoint-наблюдение, не разрешение на планирование.

AdmissionClient ограничивает actual JSON request 32 KiB, response 64 KiB и deadline 10 s;
transport retries отключены. JSON требует единственный object с outcome, UTF-8,
без duplicate keys/trailing tokens. Канал использует plaintext с capability header:
только loopback или проверенная изолированная deployment-сеть без опубликованного
Frontier-порта. Hostname validation не доказывает изоляцию; произвольный runtime
endpoint/TLS остаётся отдельной границей RPC-001.

RpcFailure различает TRANSPORT и PROTOCOL. Любая ошибка mutating Command означает
возможный commit (mutationOutcomeUnknown), даже PERMISSION_DENIED/INVALID_ARGUMENT;
авторитетный business rejection — проверенный JSON outcome, не gRPC code.
Обычная cause chain санитизирована. transportFailure() явно предоставляет исходный
gRPC status/cause/trailers; не логировать их как доверенный или безопасный текст.
CrawlAdmissionService после transport failure open/reserve выполняет только
session/reservation readback. Положительный проверенный readback подтверждает commit;
отрицательный или недоступный даёт AdmissionFailure.UNKNOWN, без повторной мутации.
Нарушение протокола/конфликт также запрещают worker. CrawlExecution сохраняет
admission_outcome в существующих JSON/H2 observations, не вводя новую authority.
Повторный операторский start использует тот же immutable plan/session; серверная
идемпотентность не отменяет локальный ownership guard после crash.

Все production file-backed клиенты (start/status/discovery и legacy adapters)
используют FrontierCapability и перечитывают файл при каждом новом RPC. String
constructor/fromRuntime остаются явным snapshot API для callers с готовым секретом.
Сервер держит один shared operator/worker token на lifetime; role/session isolation,
overlap ротации и отзыв открытых streams отсутствуют. Изменение server token требует
остановки активных workers/streams и согласованного перезапуска; перечитывание файла
само по себе не реализует безопасную live rotation.

FrontierCapability открывает файл с NOFOLLOW_LINKS и фактически читает ≤258 bytes
для проверки лимита 257, длины 32..256 и алфавита. Ошибки не раскрывают path/content;
metadata получает один terminal callback, включая rejection executor. Требуются
operator-owned path/parents и private permissions/ACL, доступные только доверенным
operator/worker identities. POSIX-only permission policy не заменяет Windows ACL.
CAP-002 остаётся открытым для hostile special-file substitution между path type
check и open: portable Java API здесь не даёт fstat/nonblocking-open guarantee.

Readback v1: max_pages/reservations ≤100 — реальная серверная квота, а page size
≤200 — отдельный лимит. >100 требует согласованного server/product изменения,
не снятия client bounds. Cursor фиксирует through_ordinal membership; scheduler
fields/readback_at наблюдаются заново на каждой странице. readback_at — server
wall-clock, не sequence/freshness lease; clock regression не отвергается.
Terminal provenance не сохранена: done/success/terminal/retry_wait обязаны быть
null; нулевые scheduler counts не доказывают COMPLETE (READBACK-003).

Фокусные проверки: `mvn -Dtest=AdmissionClientTest,AdmissionClientReliabilityTest,FrontierCapabilityTest,FrontierReadbackTest,FrontierReadbackValidationTest,CrawlAdmissionServiceTest,CrawlAdmissionRecoveryTest,DurableDiscoveryFilterTest test`.
Durable server/real RocksDB доказательства отдельно: `bash frontier-server/build.sh --test`.
Loopback fixtures не заменяют product Storm/Tor qualification.

## Процессы, ресурсы и persistence

- Startup-lock охватывает start; Preparation проверяет retained cleanup до файлов/RPC.
  Legacy execution history без ownership proof блокирует запуск.
- Handoff сначала публикует LAUNCHING, затем освобождает preparation lock.
  Wrapper проверяет run_id, родителя и start instant до claim; child не наследует HANDOFF environment.
- Runtime worker JAR получает UUID-имя; исходный установленный JAR не изменяется.
  Фильтры лежат в onionall-job/parsefilters.json и onionall-job/urlfilters.json.
- Readiness публикуется после захвата wrapper lock и создания child.
  Launcher проверяет owner_pid. Во время штатного ожидания worker пишет execution.json,
  не открывая H2; timeout-путь сохраняет прежний порядок stop → observation → catalog.
- Бюджет seconds 1..600 задаёт Storm TTL; wrapper получает seconds+90,
  CLI wait использует seconds+115. Startup readiness ограничен 10 s.
- Stop сравнивает start instant перед сигналом тому же ProcessHandle.
  Только наблюдённые descendants с известной identity могут получить сигнал.
  STOPPED/cleanup_confirmed публикует владелец; освобождённый lock сам по себе недостаточен.
- WorkerOwnership.publish использует force файла и atomic replace; directory fsync
  выполняется best effort. Это не заявление о полной power-loss durability или OS containment.
- Start/status observations используют существующий H2-контракт archive.
  Exit zero относится к процессу, crawl_completed_verified остаётся false без отдельного доказательства.
- DurableDiscoveryFilter не вводит локальный quota cache: reservation выполняет Frontier.
  Metadata-only и scope-отказы не превращаются в разрешённые fetch targets.

## Runtime publication и discovery callbacks

Успешный возврат CrawlRuntimePreparer.prepare — единственный сигнал готовности
runtime для CrawlExecution; наличие отдельных JSON/JAR файлов таким сигналом
не является. Preflight проверяет approved archive ID, runtime/work binding и
генерирует config до файловых эффектов. Job-directory symlink отвергается;
operator-authorized parent aliases сохраняются. Родители принадлежат оператору:
эти path checks не являются containment против hostile directory writers.

CrawlResources принимает ровно два UTF-8 JSON ресурса onionall-job/parsefilters.json
и onionall-job/urlfilters.json, каждый ≤32 KiB, вместе ≤64 KiB. Parse chain —
DurableDiscoveryFilter затем ArchiveParseFilter; URLFilters пуст. Ключи JSON —
точные современные FQCN StormCrawler. Проверяются readable/CRC-consistent JAR и
runtime-visible resource bytes, включая Multi-Release lookup. Signed input
отвергается; подписи не удаляются. Публикация — createLink готового временного
файла в том же каталоге: коллизия не заменяет destination, hard-link support
обязателен, move-fallback отсутствует. Это atomic visibility, не полная
power-loss durability. Успешный commit не откатывается из-за cleanup failure.
Runtime artifacts/logs сохраняются; automatic orphan cleanup требует отдельного
WorkerOwnership-контракта и здесь не выполняется.

Canonical graph/name и parallelism=1 сохранены; launcher использует local mode.
Generated product config отключает stdout URL/text/metadata в StdOutIndexer,
сохраняя его FETCHED/status и ACK. Legacy/root fixtures не изменяются. Другие
upstream-компоненты могут логировать URL: worker logs остаются operator-private.

Discovery проверяет immutable reservation record: session, canonical URL, UUID,
depth 0..5, source и historical enqueued acknowledgement. Новая RESERVED должна
соответствовать запрошенному parent/depth; ALREADY_RESERVED сохраняет первую
provenance и вправе отличаться от текущего graph edge. Positive replies сверяются
с reservation readback; lost reply разрешается только положительным readback,
без повторного reserve. Negative/unavailable readback оставляет unresolved failure.
Отклонённые foreign links — graph data без RPC/fetch permission; legacy outlinks
очищаются только после успешного полного bounded callback.

discovery.admission.timeout.ms = min(30000, worker seconds * 1000) ограничивает
RPC/processing checks страницы; каждый RPC получает min(10 s, remaining budget).
Это не hard real-time deadline всего метода: filesystem/CPU work и bounded
channel close (до 5 s) — отдельные расходы. Time exhaustion не выдаёт success:
processing.complete=false, error/truncation metadata и exception; partial remote
commits остаются во Frontier. Локального reservation cache/quota нет.

Установленный JSoupParser 3.7.0 превращает ParseFilter RuntimeException в anchored
ERROR и ACK, не fail; обычные документы/outlinks после этого не emit-ятся.
Generated config задаёт selective ERROR interval для ephemeral
onionall.discovery.retry=true. Scheduler читает hint до MetadataTransfer; hint
не входит в persisted metadata и не влияет на последующий unrelated ERROR.
Общий ERROR=-1 сохранён. Discovery ERROR retries не имеют отдельного attempt cap;
они требуют работающего bounded worker и подчиняются Frontier due dates. Это не
доказательство полного ACK tree/lease recovery или attempt fencing (TOPO-001/002).

Фокусные проверки: `mvn -Dtest=CrawlResourcesTest,CrawlRuntimePreparerTest,CrawlTopologyTest,CrawlTopologyContractTest,CrawlGeneratedPolicyTest,DurableDiscoveryFilterTest,DurableDiscoveryAdmissionTest,ParserDiscoveryFailureTest test`.

## Границы для изменений

[crawl](../crawl/AGENTS.md) содержит сервис, runtime и config factory;
[archive](../archive/AGENTS.md) владеет каталогом; credentials используют также
[source-pinned adapters](../../../../org/apache/stormcrawler/urlfrontier/AGENTS.md).
Имена CrawlWorker и reflective filter/bolt/spout classes участвуют в запуске и probes.
Package-private helper API связан с tests/probes того же пакета.

Сохраняйте текущие nullable UNKNOWN-наблюдения, interruption и порядок cleanup.
Формат и русские Javadocs: [общие соглашения](../AGENTS.md).
