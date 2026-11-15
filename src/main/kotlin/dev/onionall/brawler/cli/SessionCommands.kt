package dev.onionall.brawler.cli

import dev.onionall.brawler.research.ResearchExporter
import dev.onionall.brawler.research.ResearchWorkflow
import java.nio.file.Path
import java.util.concurrent.Callable
import picocli.CommandLine.*

/**
 * **Назначение:** Объединить команды M6 с явным выбором и одобрением источников.
 *
 * Основные обязанности:
 * - Сохранять явные selection и approval.
 * - Разделять результаты discovery, crawl и export.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
object SessionCommands {

    /**
     * **Назначение:** Предоставить вложенным командам доступ к research workflow и выводу результата.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    abstract class Base : Callable<Int> {

        /**
         * **Назначение:** Получить родительскую команду, назначенную Picocli.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @ParentCommand
        lateinit var parent: ResearchCommands

        /**
         * **Назначение:** Получить research workflow из родительской команды.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        fun workflow() = parent.sessions()

        /**
         * **Назначение:** Вывести структурированный результат и вернуть код успешного завершения.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        fun show(value: Map<String, Any?>): Int {
            workflow().print(value);
            return 0
        }
    }

    /**
     * **Назначение:** Выполнить разрешённый поиск Ahmia через Tor без загрузки найденных целей.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "discover",
        mixinStandardHelpOptions = true,
        description = ["Execute authorized Ahmia form search through Tor; no candidate targets are fetched."]
    )
    class Discover : Base() {

        /**
         * **Назначение:** Выбрать поддерживаемый источник поиска.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--provider"], defaultValue = "ahmia")
        lateinit var provider: String

        /**
         * **Назначение:** Задать поисковый запрос для выбранного источника поиска.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--query"], required = true)
        lateinit var query: String

        /**
         * **Назначение:** Ограничить число возвращаемых результатов поиска.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--limit"], defaultValue = "20")
        var limit = 20

        /**
         * **Назначение:** Указать путь к существующему runtime descriptor.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--runtime"], required = true)
        lateinit var runtime: Path

        /**
         * **Назначение:** Проверить provider, выполнить discovery и вернуть код по его результату.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            require(provider == "ahmia") { "Only the verified Ahmia adapter is available" }
            val result = workflow().discover(query, limit, runtime)
            show(result)
            return if (result["discovery_state"] == "FAILED") 6 else 0
        }
    }

    /**
     * **Назначение:** Сохранить явно переданные seed-кандидаты без сетевого поиска.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "import",
        mixinStandardHelpOptions = true,
        description = ["Persist explicitly supplied candidate seeds; no provider/network claim."]
    )
    class Import : Base() {

        /**
         * **Назначение:** Передать список seed URL для локального импорта кандидатов.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--seed"], required = true)
        lateinit var seeds: List<String>

        /**
         * **Назначение:** Задать подпись для импортируемых seed-кандидатов.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--label"], defaultValue = "User supplied seeds")
        lateinit var label: String

        /**
         * **Назначение:** Импортировать переданные seed-кандидаты и вывести результат.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call() = show(workflow().importSeeds(seeds, label))
    }

    /**
     * **Назначение:** Показать сохранённые свидетельства обнаружения и текущий выбор кандидатов.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "candidates",
        mixinStandardHelpOptions = true,
        description = ["Read original discovery evidence and selection without crawling."]
    )
    class Candidates : Base() {

        /**
         * **Назначение:** Указать идентификатор research-сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var id: String

        /**
         * **Назначение:** Прочитать и вывести сохранённую сессию с кандидатами.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call() = show(workflow().session(id))
    }

    /**
     * **Назначение:** Изменить выбор seed-кандидатов без запуска crawl.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "select",
        mixinStandardHelpOptions = true
    )
    class Select : Base() {

        /**
         * **Назначение:** Указать идентификатор research-сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var id: String

        /**
         * **Назначение:** Перечислить идентификаторы выбираемых кандидатов через запятую.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--ids"], split = ",")
        var ids: List<String> = emptyList()

        /**
         * **Назначение:** Запросить выбор всех допустимых кандидатов.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--all-valid"])
        var all = false

        /**
         * **Назначение:** Перечислить идентификаторы исключаемых кандидатов через запятую.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--exclude"], split = ",")
        var exclude: List<String> = emptyList()

        /**
         * **Назначение:** Проверить наличие критериев выбора и сохранить обновлённую selection.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            require(all || ids.isNotEmpty() || exclude.isNotEmpty()) { "Choose --all-valid, --ids or --exclude" }
            return show(workflow().select(id, ids, all, exclude))
        }
    }

    /**
     * **Назначение:** Одобрить точную сохранённую область batch-планов сессии.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "approve",
        mixinStandardHelpOptions = true,
        description = ["Approve the exact stored immutable batch scope."]
    )
    class Approve : Base() {

        /**
         * **Назначение:** Указать идентификатор research-сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var id: String

        /**
         * **Назначение:** Одобрить сохранённую сессию и вывести результат.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call() = show(workflow().approve(id))
    }

    /**
     * **Назначение:** Показать результаты сессии, разделяя acquisition, исполнение и наблюдения Frontier.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "status",
        mixinStandardHelpOptions = true,
        description = ["Separate acquisition, child execution and unknown Frontier terminal provenance."]
    )
    class Status : Base() {

        /**
         * **Назначение:** Указать идентификатор research-сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var id: String

        /**
         * **Назначение:** Указать путь к существующему runtime descriptor.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--runtime"])
        var runtime: Path? = null

        /**
         * **Назначение:** Указать путь к файлу capability для обращения к Admission/Frontier.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--admission-token-file"])
        var token: Path? = null

        /**
         * **Назначение:** Прочитать и вывести результаты сессии с доступными runtime-наблюдениями.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call() = show(workflow().results(id, runtime, token))
    }

    /**
     * **Назначение:** Показать захваченные страницы research-сессии.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "pages",
        mixinStandardHelpOptions = true
    )
    class Pages : Base() {

        /**
         * **Назначение:** Указать идентификатор research-сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var id: String

        /**
         * **Назначение:** Вывести страницы из сохранённых результатов сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            val result = workflow().results(id, null, null)
            return show(mapOf("session_id" to id, "pages" to result["pages"]))
        }
    }

    /**
     * **Назначение:** Выполнить ограниченный локальный экспорт текста и свидетельств источников.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "export",
        mixinStandardHelpOptions = true,
        description = ["Bounded local export of untrusted captured text and source evidence; no AI upload."]
    )
    class Export : Base() {

        /**
         * **Назначение:** Указать идентификатор research-сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var id: String

        /**
         * **Назначение:** Выбрать формат локального экспорта результатов.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--format"], defaultValue = "markdown")
        lateinit var format: String

        /**
         * **Назначение:** Указать целевой путь для локального экспорта.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--output"], required = true)
        lateinit var output: Path

        /**
         * **Назначение:** Собрать результаты и подробности страниц для локального экспорта.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            val result = workflow().results(id, null, null)
            return show(ResearchExporter.export(result, workflow().pageDetails(result), format, output))
        }
    }
}
