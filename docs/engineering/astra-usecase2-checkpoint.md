# Usecase2 — журнал реализации

## Scope и среда

Продолжение 27 ноября 2026 на `feat/astra-usecase` от опубликованного
`854ea38`. SHA-256 `usecase2.md`
`651f02450ef4adcc88a0aaebf15a695a1834bcdbf9cfebe69af48d278dcd7714`.

Windows checkout использует Temurin 25.0.5+8, Maven 3.10.0,
Rust 1.99.1, Python 3.12.10 и GnuPG 2.4.9. Локальный Docker 29.6.2
и сохранённый Compose 5.7.0 возвращают exit 1 при запросе к `docker_engine`:
именованный канал отсутствует. WSL не установлен. Сохранённый Compose
валидирует canonical `tor` и `fixture` плюс M0/M1/M2, все exit 0.
Запуск контейнеров проверяется отдельно в Actions.
Исторический Graphify `31ce1d7` остался картой исходной базы. Graphify
и rust-anything для нового запуска отсутствуют.

## Реализованные scopes

| Commit | Изменение и проверка |
| --- | --- |
| `f8d9cef`, `defbade`, `05b48c0` | GnuPG в изолированном cwd, переносимые Bash/Windows paths и SIGINT fixtures. Проверки подписи, очистки и запрета fallback сохранены |
| `b026397` | A02. Версия JVM в заголовке или явном URL path, релевантная тема в заголовке. Противоречащие версии исключаются до result limit. На двух корпусах precision 0,50 → 1,00, recall 1,00 |
| `93e9fb7` | Canonical Docker gate использует durable research session, проверяет отказ до approval, реальные CRAWLER snapshots, JSON/Markdown и сохранность после пересоздания |
| `e33417f`, `961d7f0`, `0b7c7b9`, `a3eafef` | `research document` проверяет blob и pinned snapshot. `research route` показывает путь по сохранённым ссылкам одного job и сохраняет исходные evidence IDs. Tests покрывают A01–A10, B01, C01 и D01 локально |
| `42b7b67` | F06. Код поста и цитат сохраняется раздельно, без нормализации пробелов и потери source provenance. Markdown обрамляет код как данные |
| `388db3e` | `research batch validate`. До 200 intents и 256 КиБ, budgets и duplicate checks. Приём JSON отделён от approval и исполнения |
| `d16f30d`, `6158a6e` | UTF-8 в перенаправленном CLI, Windows classpath и путь к DLL. Полный offline `build-cli.sh` через закреплённый Maven wrapper, 45 файлов в bundle, `--version` и 10 offline packaged CLI вызовов прошли |
| `28af077` | A02 в canonical Docker, две реально полученные редакции, фильтр JDK 25 и сравнение доказательств после recreate |
| `253fbd2` | 200 конкретных intents R200 и отдельная рубрика. Валидатор принимает 200 уникальных ID и отклоняет дубль |
| `cd7261b` | F07 сохраняет наблюдаемую дату изменения в JSON и читаемом экспорте. Разные метки сохраняют отдельные snapshot evidence |
| `b35be1e`, `fa88a3e` | F01/F02/F03/F05/F08. Архивный CLI проверяет порядок, границы цитат, метаданные и Unicode, а также дедупликацию пяти страниц с полным списком наблюдаемых источников |
| `97bafbf` | Git Bash корректно выбирает JDK из Windows `JAVA_HOME`, включая `C:/…` и пробелы. Пять Python contracts и полный штатный Windows build прошли |

## Фактические результаты

- Windows. Повторная упаковка через закреплённый Maven wrapper с
  `JAVA_HOME=C:/…` прошла. JVM 462 PASS, 4 платформенных SKIP.
  Архивный F07 прошёл пятью вызовами из готового thin bundle, включая
  повторное чтение и текстовый export. 45 файлов в пакете. Frontier conformance
  7/7 на предыдущем проверенном коде. Rust 28/28, fmt и Clippy для трёх crate.
- Python verifier suites 176 PASS, 16 платформенных SKIP из 192 selected. Localhost M1 пройден. GnuPG candidate
  Python regression требует Linux. Отдельно passed 3 canonical export controls.
- R100 по 100 ID: **1 PASS, 20 PARTIAL, 9 NOT IMPLEMENTED, 70 NOT RUN**.
  A02 прошёл синтетический acquisition через Docker. Для A01/A03–A10,
  B01/C01/D01, F01/F02/F03/F05–F08 и J07 есть только частичные доказательства.
  `r100-usecase2-e2e.json` в `.runtime/usecase2-20261127/` составлен из
  Surefire XML, предыдущей оценки и результата Docker. Реальные источники
  не использованы.
- [R200 Stage A](../research/r200/README.md) прошёл. Валидатор принял полный
  список из 200 целей и отклонил контрольный дубликат без сети и без создания
  архива. Выполнение batch и real-source R200 — **NOT RUN**, выполнено
  0 целей. Адаптер MCP не реализован.
- C200 **NOT RUN**. Двухпроцессный spike подтвердил занятый H2 archive:
  второй JVM отклонён с exit 6, отдельный архив и повтор после закрытия
  владельца успешны. Saturation point не измерен. Scope процесса worker
  привязан к PID namespace; перенос старого ownership при смене контейнера
  остаётся fail-closed. Нужен проверяемый service-owned run/cancel и явная
  приёмка recovery перед увеличением concurrency.

## Docker evidence

[Первый Linux run](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/46050190644)
на `93e9fb7` прошёл container build, JVM 426/426 и Frontier 7/7.
Остановился до deployment на Linux-only Python fixture, которая искала trace и
Release в прежнем cwd. `05b48c0` задаёт fixture paths явно.

[Повторный Linux run](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/46051847568)
на `05b48c0` завершён **PASS**. JVM 444/444, Frontier 7/7, Python tooling
43/43 и 3 export controls. Docker fixture сохранил durable research session,
3 страницы глубин 0/1/2, 9 рёбер и 5 reservations. Зафиксированы 6 HTTP
запросов, retry 503 → 200 и redirect 302. Архив, сессия, граф, Frontier
и экспорты JSON/Markdown сверены после пересоздания контейнеров.
При повторном чтении новых HTTP-запросов нет. Результат
`PAUSED / INCOMPLETE`, `crawl_completed_verified=false`, `real_tor=NOT_RUN`.

[Canonical result artifact](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/46051847568/artifacts/15225711260)
SHA-256 ZIP `a261b7f8304aa3ea5cc91c8bcf1f39346d135026dc6f962c8ae408405de3c942`. Санитизированный результат
`docker-e2e-46051847568.json` сохранён локально. Linux run относится к
`05b48c0`. Финальный Windows пакет и JVM verify включают последующие
F06, batch и CLI commits.


[Docker A02](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/46054294481/attempts/2) на `28af077`
завершён **PASS** со второй попытки. Первая остановилась до тестов на
скачивании закреплённого Maven 3.10.0. Повтор того же SHA прошёл сборку,
JVM 458/458, Frontier 7/7
и Python tooling 46/46.
На внутренних SOCKS fixtures `search-local` нашёл совпадения обеих редакций,
`research search` вернул одну страницу JDK 25. Source kind `CRAWLER`.
В `research document` сверены маркер содержимого, snapshot ID и SHA-256.
Аналогичный результат получен после пересоздания контейнеров без новых
HTTP-запросов. Frontier вернул `UNKNOWN / INCOMPLETE`.

[Артефакт Docker A02](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/46054294481/artifacts/15226638077)
SHA-256 ZIP `00ec6bdab6fe30c2e190eb2ae5e41450b30cf4422b3eefe271577c8323f63706`. Снимок A02 `14dc2ffc-e457-4301-a51a-bcb14a3cffdf`,
SHA-256 HTML `60151c557669f1003ffe337da52ee5e67148615506ca7c2577565de63bf4d19b`.

## Воспроизведение

На Windows из корня проекта с локальным JDK 25 и закреплёнными кешами:

```powershell
$env:JAVA_HOME = (Resolve-Path '.runtime/security-stage23/tools/jdk-25.0.5+8').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$mvn = (Resolve-Path '.runtime/security-stage23/tools/apache-maven-3.10.0/bin/mvn.cmd').Path
$cache = (Resolve-Path '.runtime/security-stage23/maven-repository').Path
& $mvn -B -o "-Dmaven.repo.local=$cache" verify
& $mvn -B -o "-Dmaven.repo.local=$cache" -f frontier-server/pom.xml verify
python -B scripts/runners/verify.py --links-only
```

Для отдельного R100 baseline укажите `-Donionall.r100.report=<новый JSON-путь>`.
Evidence отдельных A/B/C/D/F тестов сохраняется в `target/surefire-reports/`.
Операторские команды описаны в [M6 research](../m6-research.md) и
[forum research](../m61-forum-research.md). Повтор Docker gate через
`gh workflow run portable.yml --ref feat/astra-usecase`.

Следующая runtime-проверка Wave 4 использует существующий Compose-сервис
`crawler` на отдельном чистом SOCKS fixture. Запустить одобренную сессию
в detached one-off контейнере и после выхода submitter сверить точный ID
контейнера, последующий трафик и повторно открываемый архив. Отдельно проверить
фактический exit, подтверждённую остановку worker и сохранность экспортов.
Нужен Linux daemon. Отмена, повторный запуск через новый PID namespace и
service-owned batch этим экспериментом не подтверждаются. H2 при занятости
остаётся fail-closed. C200, file downloads, MCP и real-source R200 требуют
отдельной приёмки.
