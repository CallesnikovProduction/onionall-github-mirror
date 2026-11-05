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
public Map<String, Object> command(Map<String, Object> request) throws IOException;
public FrontierReadback.Page list(String session, String cursor, int limit) throws IOException;
public FrontierReadback.Counts counts(String session) throws IOException;
public void close();
```

[CrawlAdmissionService.java](CrawlAdmissionService.java):

```java
static Map<String, Object> admit(String id, Map<String, Object> plan, Map<String, Object> runtime, String capability) throws IOException;
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


## Admission и credentials

CrawlExecution читает approval до admission; Frontier остаётся authority.
Open и итоговый session readback сверяются с plan_id/origin/seeds/max_pages/depth/approved.
Каждый seed требует reserve и отдельный reservation readback с тем же reservation_id.
Архив хранит checkpoint-наблюдение, не разрешение на планирование.

AdmissionClient ограничивает request 32 KiB, response 64 KiB, deadline 10 s;
канал использует существующий plaintext-контракт с capability header.
FrontierCapability.token проверяет regular non-symlink файл ≤257 bytes,
длину 32..256 и алфавит. CallCredentials перечитывает файл для RPC;
ошибка даёт PERMISSION_DENIED без содержимого секрета и anonymous retry.
DurableDiscoveryFilter имеет собственный путь чтения конфигурации/токена:
не приписывайте ему все проверки FrontierCapability.token без чтения реализации.

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

## Границы для изменений

[crawl](../crawl/AGENTS.md) содержит сервис, runtime и config factory;
[archive](../archive/AGENTS.md) владеет каталогом; credentials используют также
[source-pinned adapters](../../../../org/apache/stormcrawler/urlfrontier/AGENTS.md).
Имена CrawlWorker и reflective filter/bolt/spout classes участвуют в запуске и probes.
Package-private helper API связан с tests/probes того же пакета.

Сохраняйте текущие nullable UNKNOWN-наблюдения, interruption и порядок cleanup.
Формат и русские Javadocs: [общие соглашения](../AGENTS.md).
