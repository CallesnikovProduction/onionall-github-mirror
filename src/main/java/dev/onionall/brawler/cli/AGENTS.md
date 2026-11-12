# cli: команды и терминал

Наследует [общие Java-соглашения](../AGENTS.md).
Picocli разбирает аргументы; CrawlService выполняет прикладные операции,
ArchiveStore владеет persistence. Классы presentation не принимают решения admission.

## Классы

| Класс | Роль |
| --- | --- |
| [Onionall](Onionall.java) | main, дерево команд, menu, options, output/error mapping; Transport позволяет подменить fetch в тестах |
| [ArchiveCommands](ArchiveCommands.java) | Вложенные команды Crawl, Plan, ShowPlan, Approve, Start, Status, Errors, Pages, Page, ShowPage, SearchLocal, ProviderSearch, Archive, ImportHtml, Export |
| [HtmlFiles](HtmlFiles.java) | JSoup parsing, bounded offline inspect и атомарная публикация файлов |
| [DiscoveryReport](DiscoveryReport.java) | Offline discover; Link/Report records содержат validation/scope/dedup observations |
| [Brand](Brand.java) | Wordmark/palette/layout, safe text и Activity spinner |
| [CliPresentation](CliPresentation.java) | Строки статуса и оформление известных типов строк |
| [InteractiveSession](InteractiveSession.java) | ANSI viewport, resize poll, progress и idempotent terminal restore; Size/Dimensions изолируют измерение |
| [Mascot](Mascot.java) | Headless Java2D raster и ANSI half-block/ASCII output |

Onionall также содержит команды Fetch/Discover/Inspect/Status.
Kotlin ResearchCommands и RuntimeCommands находятся в `src/main/kotlin` того же
пакета и регистрируются в Onionall.execute. Research Backend в ArchiveCommands
делегирует существующим сервисам; Kotlin не зависит от Java sources при compilation.
Research plans ограничены depth 2/pages 20, run сохраняет explicit approval,
results/links/files/search читают существующий каталог. Config proxy меняет только
endpoint descriptor; system doctor доказывает лишь SOCKS negotiation.
M6 SessionCommands добавляет live `research discover`, `import`, `candidates`, `select`,
`approve`, `status`, `pages`, `export`; plan/run принимают `--session` наряду с legacy
`--url`/`--plan`. Research session ID отличается от child plan IDs. ArchiveCommands.sessions
только соединяет Kotlin ResearchWorkflow с Java CrawlService/ArchiveStore. Selection
не запускает crawl; batch approval относится к frozen child scopes и отдельным budget caps.
Экспорт локальный, bounded и сохраняет source/time provenance; file candidates не скачиваются.
Контракт: [M6](../../../../../../../docs/m6-research.md).
Fetch выполняет один transport GET; Inspect/Discover работают с локальными файлами;
Status делает локальный TCP connect. ArchiveCommands.Start может запустить настоящий worker,
поэтому весь ArchiveCommands нельзя считать offline-пакетом.

## Точки входа и внутренние API

[Onionall.java](Onionall.java):

```java
Path downloads();
Path archiveDirectory();
Config config();
void print(Map<String, Object> result) throws IOException;
int failure(int code, Exception e) throws IOException;
static int execute(String[] args, PrintWriter out, PrintWriter err, boolean tty, Map<String, String> env, Transport transport);
private static int execute(String[] args, PrintWriter out, PrintWriter err, boolean tty, Map<String, String> env, Transport transport, boolean interactiveContent);
public static void main(String[] args);
```

У execute существуют package-private перегрузка и private перегрузка с interactiveContent;
Transport.fetch имеет контракт:

[Onionall.java](Onionall.java):

```java
abstract ProtocolResponse fetch(Config config, String url) throws IOException;
```

Команды реализуют Callable<Integer>: `public Integer call() throws Exception`,
а группы ArchiveCommands.Crawl/Page/Archive объявляют `throws IOException`.
Параметры задаёт Picocli через @Option/@Parameters/@ParentCommand, не ручной parsing argv.
main использует System.exit; execute возвращает код и служит существующей тестовой точкой входа.

[HtmlFiles.java](HtmlFiles.java):

```java
static Document parse(byte[] content, String base) throws IOException;
static Map<String, Object> inspect(Path file) throws IOException;
static void save(byte[] bytes, Path target, boolean overwrite) throws IOException;
```

[DiscoveryReport.java](DiscoveryReport.java):

```java
static Report discover(Path file, String source, Map<String, Object> config) throws IOException;
```

Report.summary() возвращает Map<String, Object>; admitted=0/network_requests=0
являются частью offline-отчёта, не admission ledger.

[Brand.java](Brand.java):

```java
static String safe(String text);
static java.util.List<String> header(int width, int height, java.util.Map<String, String> env, boolean color);
static boolean blocks(java.util.Map<String, String> env);
```

[CliPresentation.java](CliPresentation.java):

```java
static List<String> status(Map<String, Object> result, boolean compact);
static String style(String text, boolean enabled);
```

[InteractiveSession.java](InteractiveSession.java):

```java
InteractiveSession(PrintWriter terminal, Map<String, String> env, boolean pinned);
InteractiveSession(PrintWriter terminal, Map<String, String> env, boolean pinned, Dimensions dimensions, boolean poll);
synchronized void begin();
synchronized void progress(String text);
synchronized void stopProgress();
synchronized void resize();
synchronized int availableRows();
PrintWriter writer();
public synchronized void close();
static String clip(String text, int width);
```

[Mascot.java](Mascot.java):

```java
static BufferedImage raster(int width, int height);
static List<String> render(int width, int rows, Map<String, String> env, boolean color);
```


## I/O, состояние и lifecycle

- config закрепляет Tor ONLY, loopback SOCKS и allowlist OnionSmoke.HOST.
  Порт 1..65535, timeout 1..120000 ms, body limit 1..131072; transport применяет свои ограничения.
  Fetch требует HTTP 200 и bounded text/html; redirects/retries не добавляются CLI.
- downloads/archiveDirectory учитывают CLI, окружение и XDG.
  Offline-команды не создают Frontier/transport клиентов. Assisted Ahmia даёт manual URL.
- HtmlFiles.inspect/discover ограничены 4 MiB. Discover обрабатывает до 1000 anchors,
  URL ≤2048 и явный base-url. Документный base/JS/refresh не задаёт разрешённую цель.
  Metadata-only правило переиспользуется из Kotlin DiscoveryPlanner.
- HtmlFiles.save без overwrite публикует hard link из same-directory temporary file:
  racing writer не должен быть заменён. overwrite использует ATOMIC_MOVE/REPLACE_EXISTING.
  JSONL discover/export metadata публикуются только по явному запросу.
- MANUAL_IMPORT ограничен 128 KiB, требует approved-план и не доказывает Tor/robots.
  Локальный поиск обращается к H2; crawl status может писать reconciliation observations.
- JSON, non-TTY, NO_COLOR и --no-color сохраняют plain output. Brand.safe заменяет Cc/Cf;
  InteractiveSession.clip дополнительно кодирует wide/combining символы перед cell accounting.
- writer() возвращает внутренний PrintWriter; close его Writer не закрывает сессию.
  Восстановление терминала выполняет InteractiveSession.close().
- Viewport включается только при pinned и поддержанном TERM. Session владеет alternate buffer,
  margins/cursor restore и resize timer; ввод через System.console().readLine принадлежит Onionall.
  Таймеры/Activity завершаются при close/finally; interrupt flag сохраняется.
- terminalSize читает stty size с bounded ожиданием либо COLUMNS/LINES.
  Это чтение размера, не изменение terminal modes.
- TCP reachable в status не доказывает SOCKS negotiation, Tor bootstrap или onion connectivity.
  Branding/строки состояния не должны превращать его в readiness assertion.
- Mascot задаёт java.awt.headless=true; Graphics2D освобождается в finally.
  Рисунок и ASCII используют встроенный вектор без внешних asset/font файлов.

## Границы для изменений

Сохраняйте имена команд/опций, JSON fields, exit codes, no-overwrite и восстановление терминала.
Transport/policy принадлежат Kotlin tor, orchestration находится в
[crawl](../crawl/AGENTS.md), persistence в [archive](../archive/AGENTS.md).
Формат/Javadoc и Java-имена: [общие соглашения](../AGENTS.md);
snake_case компонентов Link/Report сохраняет существующий JSON.

Unit CLI и packaged/PTY проверки имеют разные границы:
[контракты distribution](../../../../../../../test/cli/AGENTS.md).
