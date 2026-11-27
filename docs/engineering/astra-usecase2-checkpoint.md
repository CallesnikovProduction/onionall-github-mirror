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

## Фактические результаты

- Windows JVM 454 PASS, 4 платформенных SKIP. Frontier conformance 7/7.
  Rust 28/28, fmt и Clippy прошли для трёх crate.
- Python verifier suites 176 PASS, 16 платформенных SKIP из 192 selected. Localhost M1 пройден. GnuPG candidate
  Python regression требует Linux. Отдельно passed 3 canonical export controls.
- R100 по 100 ID: **0 PASS, 15 PARTIAL, 9 NOT IMPLEMENTED, 76 NOT RUN**.
  PARTIAL включают A01–A10, B01, C01, D01, F06 и J07. Проверено evidence в
  сохранённых синтетических материалах. Полная acquisition/navigation pipeline
  для этих кейсов не пройдена. Итог `r100-usecase2.json` собран из свежих
  Surefire XML и baseline JSON в `.runtime/usecase2-20261127/`.
- Batch intake проверяет границу 200 заявок. R200, реальные approved sources
  и выполнение batch — **NOT RUN**. Адаптер MCP не реализован.
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

Следующий исполнимый scope — пройти оставшиеся R100 сценарии через
контролируемый acquisition path. Service-owned execution, файл-загрузки
с approval и C200 требуют отдельной сквозной приёмки. Реальный R200 требует
проверенного source manifest и согласованных request budgets.
