# crawl: прикладные операции и runtime

Наследует [общие Java-соглашения](../AGENTS.md). Планы и observations сохраняет
[archive](../archive/AGENTS.md); запуск worker и admission делегируются
[admission](../admission/AGENTS.md).

## Классы и границы

| Класс | Роль |
| --- | --- |
| [CrawlService](CrawlService.java) | Прикладной фасад создания/чтения/approval планов, истории ошибок, start/status; хранит Path архива и Execution |
| [CrawlService.Execution](CrawlService.java) | Существующая подмена start/status, используемая тестами; default реализация вызывает CrawlExecution |
| [RuntimeDescriptor](RuntimeDescriptor.java) | Загрузка JSON как Map<String, Object>, проверки строк и bounded целых параметров |
| [CrawlConfigurationFactory](CrawlConfigurationFactory.java) | Чистое построение StormCrawler/Tor/Frontier/admission config; без RPC и запуска процессов |
| [JobStatusService](JobStatusService.java) | Чтение каталога, worker observation и Frontier readback, оценка и сохранение состояния |
| [JobStatusService.FrontierObservation](JobStatusService.java) | Приватный record сессии, счётчиков и nullable reachability |

CrawlService открывает ArchiveStore на время операции. Здесь нет второго scheduler.
Генерация топологии и worker lifecycle фактически остаются в admission.

## Важные API

[CrawlService.java](CrawlService.java):

```java
public CrawlService(Path archive);
public CrawlService(Path archive, Execution execution);
public Map<String, Object> createPlan(List<String> seeds, int depth, int maxPages, String provider) throws IOException;
public Map<String, Object> plans(String cursor, int limit) throws IOException;
public Map<String, Object> plan(String id) throws IOException;
public Map<String, Object> approve(String id) throws IOException;
public Map<String, Object> errors(String id, String cursor, int limit) throws IOException;
public Map<String, Object> status(String id, Path runtime, Path token) throws Exception;
public Map<String, Object> start(String id, Path runtime, Path token) throws Exception;
```

[CrawlService.Execution](CrawlService.java) (методы интерфейса public abstract по правилам Java):

```java
abstract Map<String, Object> status(Path archive, String id, Path runtime, Path token) throws Exception;
abstract Map<String, Object> start(Path archive, String id, Path runtime, Path token) throws Exception;
```

Default Execution реализован анонимным классом в конструкторе и вызывает
CrawlExecution.start/status. Его методы с Path root не являются перегрузками
самого CrawlService. Политика запуска и ownership остаются в admission;
JobStatusService использует FrontierReadback.verifySession напрямую.

[RuntimeDescriptor.java](RuntimeDescriptor.java):

```java
public static Map<String, Object> descriptor(Path file) throws IOException;
public static String text(Map<String, Object> value, String key) throws IOException;
public static int number(Map<String, Object> value, String key, int fallback, int max) throws IOException;
```

[CrawlConfigurationFactory.java](CrawlConfigurationFactory.java):

```java
public static Map<String, Object> build(Map<String, Object> plan, Map<String, Object> runtime, Path archive, Path tokenFile, Path work, int timeout, int retry) throws IOException;
```

[JobStatusService.java](JobStatusService.java):

```java
public static Map<String, Object> reconcileStatus(java.nio.file.Path archive, String id, java.nio.file.Path runtimeFile, java.nio.file.Path tokenFile) throws IOException;
```


## Контракты и эффекты

- start без runtime проверяет существование задания и возвращает BLOCKED/exit_code=7
  с network_requests=0. При заданном runtime передаёт вызов Execution.
- descriptor проверяет regular file без symlink при первоначальной проверке и размер ≤32 KiB;
  дальнейшее чтение выполняется Files.readAllBytes. Это не атомарное открытие с NOFOLLOW_LINKS.
- text требует непустую строку ≤4096 символов; number проверяет целочисленность и диапазон 1..max.
  Map runtime не заменяется новым DTO.
- ConfigurationFactory сохраняет Tor ONLY, HTML bounds, robots, capability-путь,
  session ID и существующие имена reflective компонентов.
- reconcileStatus пишет admission/status observations и bounded transition history;
  результат дополняется job_status. Ошибка чтения H2 не превращается в пустое задание.
- Frontier session/spec и counts проверяются до признания readback актуальным.
  Для gRPC UNAVAILABLE reachability=false, DEADLINE_EXCEEDED даёт null;
  иные распознанные gRPC-ошибки дают reachability=true без подтверждённых counts.
  IOException без gRPC-причины распространяется.
- Кэш и worker exit zero не означают crawl completion. Текущий JobStatusView
  различает свежие сведения, legacy execution state и неизвестные исходы.

## Работа с пакетом

Соглашения об именах/Javadoc описаны [выше](../AGENTS.md).
Сохраняйте Java-callable API и существующую типизацию JSON Map.
Status reconciliation принадлежит JobStatusService; не дублируйте его в CLI или worker.
