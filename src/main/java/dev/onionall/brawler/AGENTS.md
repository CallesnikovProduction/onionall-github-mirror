# Java production: навигатор

Корневой [AGENTS.md](../../../../../../AGENTS.md) и [src/AGENTS.md](../../../../../AGENTS.md) продолжают действовать.
Здесь описан Java-код; transport и scope policy реализованы также в Kotlin.

## Карта пакетов

| Пакет | Фактическая ответственность |
| --- | --- |
| [crawl](crawl/AGENTS.md) | Прикладной сервис планов, runtime-карта, конфигурация и reconciliation статуса |
| [admission](admission/AGENTS.md) | Frontier RPC/credentials/discovery; здесь же остаются координатор запуска, генерация worker-ресурсов и ownership |
| [archive](archive/AGENTS.md) | Embedded H2, HTML-объекты, provenance, граф и failure observations |
| [cli](cli/AGENTS.md) | Picocli, offline-команды, меню и terminal rendering |
| [tools](tools/AGENTS.md) | Фиксированный диагностический GET |
| [org.apache.stormcrawler.urlfrontier](../../../org/apache/stormcrawler/urlfrontier/AGENTS.md) | Source-pinned capability-aware Spout/status updater |

Путь исполнения: CLI → CrawlService → CrawlExecution → Frontier admission →
CrawlWorker/Storm. Parser и status callbacks пишут архивные наблюдения.
Frontier владеет reservations, leases, quota и расписанием; H2 хранит каталог и наблюдения.

## Имена, форматирование и Javadoc

Java-типы используют PascalCase, методы и поля camelCase, константы UPPER_SNAKE_CASE.
Существующие JSON/config keys и имена компонентов records, включая snake_case
в DiscoveryReport, являются отдельными контрактами и не нормализуются вместе с Java-именами.

В прикладных пакетах: четыре пробела, один statement на строку, блоки управления
со скобками, пробелы после запятых и вокруг операторов; длинные сигнатуры раскрыты по параметрам.
Длинные SQL, ANSI-строки и другие литералы местами превышают 120 символов:
содержимое литералов сохраняется. Upstream-копии сохраняют собственный стиль.

Javadoc значимых API содержит краткое русское назначение в `<b>…</b>`,
описание ограничений/эффектов с `<p>`, релевантные `@param`, `@return`, `@throws`.
Существующие author metadata и содержательные upstream-комментарии сохраняются.
Наличие метода чтения в названии не делает его read-only: например,
JobStatusService.reconcileStatus сохраняет наблюдения в H2.

## Границы изменений

Публичные entrypoints, class-name строки Storm/ProcessBuilder, JSON-поля,
capability и checked exceptions связывают пакеты с внешними callers.
Package-private worker API используется также probes/tests из пакета admission.

В графе уже есть обратные зависимости crawl ↔ admission и контур
admission → archive → upstream adapter → admission. Это описание текущей
структуры, не требование создавать такие зависимости в новых компонентах.

Maven компилирует Kotlin перед Java; обе части используют Java 25.
Unit/fixture-проверки, отдельный thin CLI и real Tor acceptance имеют разные границы.
Команда общей проверки: `mvn verify`; окружение и локальный Maven cache выбираются снаружи кода.
