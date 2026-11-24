# Модернизация инструментов Onionall Brawler

23 ноября 2026 года. Исходная ревизия `0f66d5901e4b68e8b43aa1b0ee5a4a3daaedd39d`.
Аудит охватил все 18 скриптов в `scripts/patches`, `scripts/runners` и
`scripts/security`, связанные Docker-скрипты и M2 preflight. Рабочее дерево
уже содержало перенос recovery и отдельные правки Kotlin Research.
`scripts/recovery` и `scripts/cli` исключены из изменений.
Контракты сверены с [ADR 0004](../adr/0004-canonical-product-topology.md)
и [контрольной точкой Astra](astra-checkpoint.md).

## Инвентарь и решения

| Файл | Решение | Вызовы, контракты и обоснование |
| --- | --- | --- |
| `scripts/security/verify_tor_candidate.py` | **MIGRATE** | Ручной пакет APT, GnuPG, закреплённые ключ, версия, SHA-256, срок и размер. Публикует новый контекст под `.runtime`. Rust проверяет и сохраняет одни и те же байты. Команда работает в Linux и Git Bash с Docker Desktop. |
| `scripts/security/check-tor-connectivity.sh` | **KEEP** | Linux/Git Bash, передаёт `PYTHON` и аргументы общему preflight через `exec`. Помощь проверяется без Tor. |
| `scripts/security/check-tor-connectivity.ps1` | **KEEP** | Windows, передаёт `-Python`, `-Seconds` и код завершения общего preflight. |
| `scripts/runners/runtime.py` | **MIGRATE** | Канонический Compose lifecycle и передача JVM аргументов. Сохраняет проекты, профили, health-гейты, коды, fixture, каталог данных и порядок остановки. Rust атомарно публикует токен и descriptor и проверяет ID crawler. |
| `scripts/runners/verify.py` | **KEEP** | Linux/Windows и CI. Собирает свежие Maven XML, Python suites, семь Frontier drivers, Markdown и opt-in E2E. Коды 0/1/2 означают PASS/FAIL/NOT RUN. Миграция потребовала бы повторной квалификации всей цепочки при небольшой инженерной отдаче. |
| `scripts/runners/tor_acceptance_runner.py` | **REFACTOR** | Linux/Windows, также используется admission/archive fixtures. Выбирает JDK25, образ и артефакты, вызывает preflight, Maven, Compose и M1. Сбор логов ограничен по времени и при ошибке пропускает управление в scoped cleanup. Полный перенос требует живой приёмки. |
| `scripts/runners/run-tor-acceptance.sh` | **KEEP** | Linux/Git Bash, тонкая передача аргументов общему M2 Runner и выбранному Python. |
| `scripts/runners/run-tor-acceptance.ps1` | **KEEP** | Windows, адаптер `-CachedImages`, `-Python`, `-Help` и exit code того же Runner. |
| `scripts/runners/run-controlled-crawl.sh` | **BLOCKED** | M1 Linux: изолированный Compose-проект, JAR, HTTP-фикстура, перезапуск и readback RocksDB, retained volume. Тесты обрабатывают exit и cleanup; для удаления нужен сравнительный системный прогон. |
| `scripts/runners/run-controlled-crawl.ps1` | **BLOCKED** | M1 Windows: тот же сценарий с отдельным JDK и семантикой quoting/ошибок PowerShell. Консолидация требует сравнить реальные Linux/Windows прогоны с теми же артефактами. |
| `scripts/runners/run-startup-check.sh` | **KEEP** | Legacy M0 Linux, фиксированный проект и логи, JAR и Compose pull/up/logs/down. Shell-тест проверяет приоритет основной ошибки. |
| `scripts/runners/run-startup-check.ps1` | **KEEP** | Legacy M0 Windows, те же фиксированные логи и teardown с разделением основной и вторичной ошибки. |
| `scripts/runners/run-startup-container.sh` | **KEEP** | Compose M0, до 60 с ждёт локальный TCP Frontier и запускает Storm Flux с TTL 30 с. TCP готовность не подменяет RPC. |
| `scripts/runners/run-controlled-crawl-container.sh` | **KEEP** | Compose M1, фиксирует precondition `M1_RUN_REL` и передаёт управление Storm Flux с TTL 45 с. |
| `scripts/runners/run-tor-crawl-container.sh` | **KEEP** | Compose M2, выполняет существующий JVM probe в нужном контейнере и запускает Storm с ограниченным TTL. Сохраняется exec-семантика сигналов. |
| `scripts/runners/frontier-volume-init.sh` | **KEEP** | M1/M2 Linux, UID/GID из существующего образа, mkdir/chown двух фиксированных путей. База RocksDB не меняется. |
| `scripts/runners/check-onion-fetch.sh` | **KEEP** | Исторический opt-in JVM smoke, JDK25, Maven offline и существующий SOCKS. Пишет в `.runtime/downloads`. При аудите запускался только help. |
| `scripts/patches/build-storm-lifecycle.sh` | **KEEP** | Linux/CI и portable Docker build. Верифицирует SHA исходника/JAR, применяет патчи без fuzz, компилирует классы, выполняет Nimbus lock/drain. Shell напрямую связывает существующие инструменты. |
| `docker/toolchain/build.sh` | **KEEP** | Linux-контейнер, общая Maven/Frontier сборка и тесты. Добавлены вызовы CLI-регрессий для Rust. |
| `docker/m2/entrypoint.py` | **BLOCKED** | Tor PID 1, ограниченные argv и bridge input, проверка private torrc и передача сигналов. Перенос требует реальной проверки Tor/obfs4 и прав mounted secrets. |
| `docker/m2/health.py` | **BLOCKED** | Linux healthcheck и управляемый статус. Проверяет obfs4 и Unix control с cookie, bootstrap и UseBridges. Для порта нужна реальная совместимость с Tor. |
| `docker/m2/state_init.py` | **KEEP** | Привилегированный Compose init создаёт фиксированные каталоги и задаёт режимы/владельца. Ключи сохраняются в томах. |
| `test/m2/tor_preflight.py` | **REFACTOR** | Общее ядро обеих проверок связности и M2 Runner. Готовность требует ATYP 3, HTTP 200 и явные подтверждения. Неизвестные поля дочернего JSON не копируются в evidence. |

## Реализация, CI и границы безопасности

[Манифест tooling](../../scripts/tooling/Cargo.toml) и
[`Cargo.lock`](../../scripts/tooling/Cargo.lock) собираются в
[существующем Dockerfile](../../docker/toolchain/Dockerfile) с Rust 1.93.0
из закреплённого образа. [`onionall`](../../onionall) направляет канонические
`up`, `status`, `down`, `cli` в [`runtime.rs`](../../scripts/tooling/src/runtime.rs)
и офлайн-проверку кандидата в [`candidate.rs`](../../scripts/tooling/src/candidate.rs).
Обе команды используют [`process.rs`](../../scripts/tooling/src/process.rs)
для ограниченного ожидания и reaping собственных дочерних процессов.
[CI](../../.github/workflows/verify.yml) получает проверенный бинарник из
Docker. Maven/Compose сохраняют свои интерфейсы.

Удалены Python-реализации `scripts/runners/runtime.py` и
`scripts/security/verify_tor_candidate.py`. Изменены launcher, Dockerfile,
build runner, workflow, M2 runner и preflight, их тесты и эксплуатационные
инструкции. Регрессии CLI проверяются в
[`check_runtime_cli.py`](../../test/scripts/check_runtime_cli.py) и
[`test_tor_candidate.py`](../../test/security/test_tor_candidate.py).

- Офлайн-контейнер использует `--network none`, read-only root, сброшенные
  capabilities и запуск без Docker socket. Bundle и шаблон Dockerfile
  смонтированы read-only. GnuPG использует отдельный временный home с
  закреплённым первичным ключом. Для индекса и пакета сверяются размеры и
  SHA-256. Дополнительно проверяются сроки, кодовое имя, версия,
  архитектура и ожидаемый путь.
- Снимки ограничены по размеру. Symlink-назначения и дубли полей
  отклоняются. Публикация резервирует каталог эксклюзивно и пишет
  Dockerfile последним. После ошибки записи возможен неполный каталог без
  Dockerfile. Проверка предполагает отсутствие процесса с теми же
  правами, способного подменять родительские каталоги во время записи.
- Канонический runtime использует существующий socket управляющего
  контейнера. Рабочие контейнеры его не получают. Labels и валидный ID
  обязательны для остановки crawler. Reaping Docker CLI при прерывании
  не доказывает завершение работы на стороне daemon.
- Preflight передаёт hostname с ATYP 3 через SOCKS на буквальном loopback,
  проверяет TLS-имя доверенным CA и читает bootstrap по локальному
  cookie-authenticated control. Регрессия подтверждает строгий verdict
  при неверном доказательстве, а не внешний Tor/HTTPS маршрут.

Справочные контракты [GnuPG gpgv](https://www.gnupg.org/documentation/manuals/gnupg/gpgv.html)
и [Debian control fields](https://www.debian.org/doc/debian-policy/ch-controlfields.html).

## Выполненные проверки

| Проверка | Результат |
| --- | --- |
| Rust 1.93.0 в Linux Docker | `cargo fmt --check`, Clippy с `-D warnings` и `cargo test --offline --locked` прошли. 13 тестов. В тесте OpenPGP использованы настоящие GnuPG, временный ключ и тестовый хеш пакета. Проверены успешная публикация, чужой ключ, испорченная подпись, срок, неоднозначные метаданные, размер и symlink. |
| `rust-anything` | Прочитана установленная версия skill, выполнены `doctor` и `analyze` для Python. Skill предлагает PyO3 для модулей; в CLI-порте использованы его `app record` и `app replay` с изолированными fixtures. Совпали 7/7 сценариев верификатора и 10/10 сценариев runtime по выходам, вызовам и наблюдаемым файловым эффектам. `gate` для сгенерированного языкового bridge здесь не используется. |
| Дополнительные случаи runtime | Ведущие JVM-флаги теперь передаются JVM. Некорректный ID контейнера блокирует stop/down. По SIGTERM собственный дочерний Docker CLI завершён и reap подтверждён. Все 13 сценариев CLI прошли после удаления Python-оригинала. |
| Существующие тесты | После удаления прошли 43 tooling, 98 M2, 2 archive и 1 security unittest с семью подслучаями. Тесты запускаются без внешней сети. Проверены help трёх native harnesses после удаления их импорта из runtime. Общий генератор синтетических имён теперь в [SOCKS-фикстуре](../../test/archive/socks_fixture.py). |
| Compose и Storm | Обе канонические конфигурации с tools и исторические M0/M1/M2 прошли `docker compose config --quiet`. Portable Storm собран, оба реальных Nimbus lock/drain probe прошли. |
| Canonical E2E | Чистый JVM checkout исходной ревизии, с запускаемым Rust-лаунчером из рабочего дерева. Реальные Frontier, Storm и JVM CLI. 3 страницы на глубинах 0/1/2, 9 рёбер, 5 reservations, 6 внутренних HTTP-запросов. Наблюдались 503 → 200 и 302. Контейнеры пересоздались, архив, граф и Frontier readback сохранились. Итог crawl остался INCOMPLETE. Тестовый том сохранён. |
| Maven и Frontier | В изолированном исходном checkout сборка прошла: 416 JVM-тестов, 0 ошибок и 1 пропуск. Семь конформанс-проверок Frontier прошли через `frontier-server/build.sh --test`. В текущем дереве из 56 целевых JVM-тестов 55 прошли. `ResearchExporterTest` обнаружил пропущенный перевод строки в существующей незакоммиченной правке Kotlin. |
| Windows | Выполнена GNU cross-сборка бинарника и тестов. На Windows прошли запуск `--help` и все четыре переносимых теста runtime. Полный нативный прогон дал 8/9: MSYS GnuPG из Git for Windows не обработал путь временного home. Поддерживаемый путь в Docker Desktop проверен из Git Bash с путём, содержащим пробел и запятую. Неверный ключ отвергнут и выходные файлы не созданы. |

После удаления Python-источника из checkout стенда повторены `up`,
`status`, чтение сохранённого архива из трёх страниц и `down`.
Локальный JSON полной приёмки сохранён в
`.runtime/tooling-fixture-1f402efbf9/canonical-result.json`.

Тесты с подставными Docker/GnuPG доказывают контракты процесса и отказов.
OpenPGP-тест доказывает работу криптографической цепочки с тестовым ключом.
Изолированный canonical E2E подтверждает рабочий путь Rust до JVM/Frontier.
Скорость до и после миграции не измерялась.

## Ограничения и откат

Остаются Python suites, `verify.py`, legacy M1/M2 и контейнерные Tor/fixture
компоненты, тонкие Bash/PowerShell entrypoints, JDK/Maven, Docker Compose и
GnuPG. Одновременные запуски канонических режимов требуют координации
оператора. Проверка наблюдаемого Docker-состояния не сериализует старты.
В задаче не квалифицировались живой Tor и актуальный upstream bundle.

Для отката сначала проверьте остановку управляемой работы и сохраните
состояния, `.runtime`, журналы, тома и ключи. Из исходной ревизии
возвратите согласованный набор из двух Python CLI, маршрутизации launcher,
toolchain/CI и соответствующих тестов. Пересоберите общий toolchain тем же
тегом. Для верификатора выберите новое пустое назначение. Форматы
данных совместимы, миграция баз и смена ключей для отката не нужны.
