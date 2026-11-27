# Astra Use Case — базовая линия R100

27 ноября 2026. Phase A и Iteration 0. Исходная ревизия
`b1fd3ac6a006b40aca340ee8970202dcaf453c54`. Локальная `main` обновлена
fast-forward, работа ведётся в `feat/astra-usecase`.

## Проверенная архитектура

| Путь | Ответственность |
| --- | --- |
| [Onionall](../../src/main/java/dev/onionall/brawler/cli/Onionall.java) → [ResearchCommands](../../src/main/kotlin/dev/onionall/brawler/cli/ResearchCommands.kt) | CLI, явное одобрение, поиск в локальном каталоге и работа с результатами |
| [ResearchWorkflow](../../src/main/kotlin/dev/onionall/brawler/research/ResearchWorkflow.kt) → CrawlService | Источники, CAS сессии, ограниченные дочерние планы и запуск через текущий worker |
| [Frontier](../../frontier-server/README.md) → Storm → Tor | Frontier владеет admission, reservation, quota, leases и retry. Storm выполняет fetch только в одобренной области. H2 хранит ссылки, сессии и каталог архива |
| [NativeContent](../../src/main/kotlin/dev/onionall/brawler/archive/NativeContent.kt) → [Rust content](../../src/native/content/Cargo.toml) | FFM вызывает пакетную обработку текста и Markdown. Jsoup, источник наблюдения и сохранение архива остаются в JVM |
| [Rust tooling](rust-tooling-modernization.md) | Текущая база уже содержит Rust-лаунчер и предыдущий миграционный эксперимент. Отдельного `docs/audits/python-rust/` нет |

[Граф Graphify](../../graphify-out/GRAPH_REPORT.md) помечен `31ce1d7`
(1 702 узла, 4 274 связи). Свежая генерация **NOT RUN**, Graphify отсутствует
в PATH. Сведения о текущем HEAD сверены по исходникам; результатов из старого
графа или CI в счёт текущих проверок нет.

## R100 Iteration 0

[Каталог](../../src/test/resources/research/r100/scenarios.json) содержит ровно
100 исходных ID A01–J10. [Два локальных workload](../../src/test/resources/research/r100/local-search.json)
задают независимый ground truth, разметку документов, два варианта синтетического
корпуса, лимит 4 страницы и выдачу до 10 результатов. Варианты меняют разметку
HTML, порядок, идентификаторы и тексты distractors. В остальных записях пока
задан критерий приёмки, корпус и workload предстоит добавить.

[Runner](../../src/test/java/dev/onionall/brawler/cli/R100BaselineTest.java)
использует действующие `research plan`, `crawl approve`,
`archive import`, `research results` и `research search`. На каждый
вариант и сценарий создаётся отдельный временный архив. Runner сверяет полный
набор URL, job, `MANUAL_IMPORT`, хэш исходных байтов и сохранённый
`snapshot_id`. Сетевой fetch запрещён. Java JUnit adapter использует текущий
test lifecycle обоих POM, продукционная исследовательская логика остаётся в Kotlin.

| R100 | Результат |
| --- | --- |
| A02 | **FAIL** в обоих вариантах. Рядом с нужной документацией найдено старое издание, где запрос встречается целиком. Precision среди возвращённых строк 1/2, recall размеченных релевантных документов 1/1 |
| J07 | **PARTIAL**. Оба локальных запроса возвращают 0 результатов после чтения всех трёх импортов. Search intent и полный research workflow не проверены. Метрики с пустым знаменателем равны null |
| H01, H03–H10 | **NOT IMPLEMENTED**. Нужен lifecycle одобрения и скачивания файлов на уровне research. H02 остаётся NOT RUN |
| Остальные 89 | **NOT RUN**. Подходящие end-to-end workloads ещё не созданы |
| PASS | **0 / 100** |

Актуальный [локальный поиск](../../src/main/java/dev/onionall/brawler/archive/internal/ArchiveQueryService.java)
использует регистронезависимую подстроку по URL, заголовку и тексту. Выдача
упорядочена по времени записи и page ID. В коротком POC на отдельном Java CLI
тот же запрос вернул обе редакции. По этой базовой линии можно измерять результат
добавления учёта версии и релевантности.

Отчёт сохраняет все 100 статусов, результаты вариантов, source URL, хэши,
snapshot ID, причины пропусков и несоответствий. `latencyNanos` включает
один локальный вызов CLI, открытие каталога и декодирование результата. Здесь
effective concurrency = 1. В финальном запуске четыре поиска заняли 26,9–29,1 мс каждый. Двух вариантов недостаточно для оценки p95 или
выбора Rust ускорения. Нагрузочные тесты начнутся после исследовательских сценариев.

## Как повторить

Из корня проекта с JDK 25, Maven 3.10.0 и Rust 1.93+ в PATH:

```powershell
New-Item -ItemType Directory -Force .runtime/astra-usecase-replay | Out-Null
$report = Join-Path $PWD.Path '.runtime/astra-usecase-replay/r100-baseline.json'
mvn -B -Dtest=R100BaselineTest "-Donionall.r100.report=$report" test
mvn -B verify
mvn -B -f frontier-server/pom.xml verify
```

Отчёт создаётся под новым именем; при повторном запуске укажите другой путь.
При подготовленном кеше можно использовать `-o`. Для Frontier предварительно
подготовьте закреплённую базовую JAR по инструкции сервера. Maven сам собирает
нативную библиотеку для JVM.

## Свежие проверки

- Windows, Temurin 25.0.5+8, Maven 3.10.0, Rust 1.99.1 и Python 3.12.10.
  Maven verify на исходном HEAD: 420 прошли, 4 платформенных пропуска, 0 ошибок.
  С R100: 421 прошёл, 4 пропуска, 0 ошибок. Проверен тот же тест через crawler POM.
- Frontier: 7 conformance suites прошли с SHA256 закреплённой базы
  `ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df`.
- Rust: `cargo fmt --check` и Clippy `-D warnings` прошли на всех трёх
  crate. Content: 7/7. Recovery: 12/12. Tooling: 8/9. OpenPGP fixture падает
  на Windows: GnuPG из Git for Windows трактует Windows `--homedir` как
  относительный POSIX путь. Проверка Linux здесь не запускалась.
- Python: выбранные 17 fixture/tooling tests прошли. Общий Windows discovery
  прерван на POSIX SIGINT тесте после ошибок shell fixtures. Это отдельный
  неуспешный запуск.
- Отчёты и журналы в `.runtime/astra-usecase-20261127/`. Итоговый JSON
  `r100-baseline-final.json`. Docker E2E, real Tor, нагрузка и новый
  migration pilot **NOT RUN**.

Следующий этап по дополнительной спецификации — документационный поиск
A01–A10. Сначала добавить измеримые цели и корпуса A01–A10, затем улучшить
bounded Kotlin planning и ranking с отдельными критериями для нужной версии,
структуры и источника. Новый pipeline должен сохранять действующие границы
Frontier, Tor и архива. Параллельная приёмка всего R100 относится к Iteration 10.
