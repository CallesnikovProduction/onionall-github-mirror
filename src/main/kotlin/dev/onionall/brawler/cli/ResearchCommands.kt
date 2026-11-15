package dev.onionall.brawler.cli

import java.nio.file.Path
import dev.onionall.brawler.research.ResearchWorkflow
import java.util.concurrent.Callable
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand

/**
 * **Назначение:** Объединить ограниченные research-команды над существующими планами и Frontier.
 *
 * Основные обязанности:
 * - Связать legacy research ID с plan ID, а M6 session — с отдельными immutable child plans.
 * - Получать существующие Java-сервисы через [Backend]: Kotlin компилируется первым.
 *
 * @property backend Адаптер существующих сервисов и вывода CLI.
 * @property workflow Необязательный workflow research-сессий.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6 Astra
 * @author GPT-6.1 Sol
 */
@Command(
    name = "research",
    mixinStandardHelpOptions = true,
    description = ["Plan, explicitly approve/run and inspect bounded research."],
    subcommands = [
        ResearchCommands.Plan::class,
        ResearchCommands.Run::class,
        ResearchCommands.Results::class,
        ResearchCommands.Links::class,
        ResearchCommands.Files::class,
        ResearchCommands.Search::class,
        SessionCommands.Discover::class,
        SessionCommands.Import::class,
        SessionCommands.Candidates::class,
        SessionCommands.Select::class,
        SessionCommands.Approve::class,
        SessionCommands.Status::class,
        SessionCommands.Pages::class,
        SessionCommands.Export::class,
        ThreadCommands::class
    ]
)
class ResearchCommands @JvmOverloads constructor(
    private val backend: Backend,
    private val workflow: ResearchWorkflow? = null
) : Callable<Int> {

    /**
     * **Назначение:** Вернуть подключённый research workflow или отклонить вызов при его отсутствии.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    fun sessions(): ResearchWorkflow = requireNotNull(workflow) { "Research session backend unavailable" }

    /**
     * **Назначение:** Определить, существует ли идентификатор в подключённом workflow сессий.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    private fun isSession(id: String): Boolean = workflow?.exists(id) == true

    /**
     * **Назначение:** Изолировать Kotlin CLI от Java-сервисов через узкую границу компиляции.
     *
     * Реализация делегирует вызовы существующим Java services.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    interface Backend {

        /**
         * **Назначение:** Создать и сохранить план для URL с переданными ограничениями глубины и страниц.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Throws(Exception::class)
        fun plan(url: String, depth: Int, pages: Int): Map<String, Any?>

        /**
         * **Назначение:** Прочитать существующий план по его идентификатору.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Throws(Exception::class)
        fun existing(id: String): Map<String, Any?>

        /**
         * **Назначение:** Одобрить сохранённый план по его идентификатору.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Throws(Exception::class)
        fun approve(id: String): Map<String, Any?>

        /**
         * **Назначение:** Запустить план через существующие сервисы с runtime descriptor и capability-файлом.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Throws(Exception::class)
        fun start(id: String, runtime: Path, token: Path): Map<String, Any?>

        /**
         * **Назначение:** Получить состояние плана с доступными runtime-наблюдениями; сервис может сохранять reconciliation.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Throws(Exception::class)
        fun status(id: String, runtime: Path?, token: Path?): Map<String, Any?>

        /**
         * **Назначение:** Получить архивные страницы указанного плана.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Throws(Exception::class)
        fun pages(id: String): Map<String, Any?>

        /**
         * **Назначение:** Получить наблюдавшиеся ссылки указанного плана.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Throws(Exception::class)
        fun links(id: String): Map<String, Any?>

        /**
         * **Назначение:** Найти архивные страницы по запросу с необязательными фильтрами job и host.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Throws(Exception::class)
        fun search(query: String, id: String?, host: String?, limit: Int): Map<String, Any?>

        /**
         * **Назначение:** Передать структурированный результат подключённому механизму вывода CLI.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Throws(Exception::class)
        fun print(value: Map<String, Any?>)
    }

    /**
     * **Назначение:** Вывести доступные подкоманды и признак обязательного одобрения.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    override fun call(): Int {
        backend.print(
            mapOf(
                "commands" to
                    listOf(
                        "discover",
                        "import",
                        "candidates",
                        "select",
                        "plan",
                        "approve",
                        "run",
                        "status",
                        "pages",
                        "results",
                        "links",
                        "files",
                        "search",
                        "export",
                        "thread"),
                "approval_required" to true,
                "scheduling_owner" to "URLFrontier"))
        return 0
    }

    /**
     * **Назначение:** Создать ограниченный план для одного URL или выбранных источников сессии без запуска crawl.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "plan",
        mixinStandardHelpOptions = true,
        description = ["Validate one onion URL and persist its scope; no network or approval."]
    )
    class Plan : Callable<Int> {

        /**
         * **Назначение:** Получить родительскую команду, назначенную Picocli.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @ParentCommand
        lateinit var parent: ResearchCommands

        /**
         * **Назначение:** Задать исходный URL для создания плана.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--url"])
        var url: String? = null

        /**
         * **Назначение:** Выбрать сессию вместо отдельного URL или legacy-плана.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--session"])
        var session: String? = null

        /**
         * **Назначение:** Ограничить число страниц в каждом дочернем плане сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--pages-per-seed"], defaultValue = "5")
        var perSeed = 5

        /**
         * **Назначение:** Задать общий бюджет страниц для планирования сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--global-pages"], defaultValue = "100")
        var globalPages = 100

        /**
         * **Назначение:** Задать глубину создаваемого плана в пределах выбранного workflow.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--depth"], defaultValue = "2")
        var depth = 2

        /**
         * **Назначение:** Ограничить число страниц создаваемого плана.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--max-pages"], defaultValue = "20")
        var pages = 20

        /**
         * **Назначение:** Проверить выбор URL или сессии, сохранить план и вывести результат.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            require((url == null) != (session == null)) { "Choose --url or --session" }
            if (session != null) {
                parent.backend.print(parent.sessions().plan(session!!, depth, perSeed, globalPages))
                return 0
            }
            require(depth in 0..2 && pages in 1..20) { "Research requires depth 0..2 and max-pages 1..20" }
            val plan = parent.backend.plan(url!!, depth, pages)
            parent.backend.print(
                plan +
                    mapOf(
                        "research_id" to plan["plan_id"],
                        "next" to "Inspect scope, then research run --plan ${plan["plan_id"]} --approve",
                        "network_requests" to 0))
            return 0
        }
    }

    /**
     * **Назначение:** Запустить план или сессию через существующий workflow с проверкой явного одобрения.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = ["Run an approved plan through existing Admission/Storm; --approve grants explicit approval."]
    )
    class Run : Callable<Int> {

        /**
         * **Назначение:** Получить родительскую команду, назначенную Picocli.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @ParentCommand
        lateinit var parent: ResearchCommands

        /**
         * **Назначение:** Указать идентификатор legacy-плана для запуска.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--plan"])
        var id: String? = null

        /**
         * **Назначение:** Выбрать сессию вместо отдельного URL или legacy-плана.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--session"])
        var session: String? = null

        /**
         * **Назначение:** Ограничить общее время исполнения сессии в секундах.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--total-seconds"], defaultValue = "600")
        var totalSeconds = 600

        /**
         * **Назначение:** Задать продолжительность одного интервала исполнения сессии в секундах.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--slice-seconds"], defaultValue = "45")
        var sliceSeconds = 45

        /**
         * **Назначение:** Задать timeout сетевого запроса сессии в миллисекундах.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--request-timeout-ms"], defaultValue = "30000")
        var requestTimeout = 30000

        /**
         * **Назначение:** Запросить продолжение исполнения существующей сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--resume"])
        var resume = false

        /**
         * **Назначение:** Разрешить явное одобрение сохранённого scope перед запуском.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--approve"])
        var approve = false

        /**
         * **Назначение:** Указать путь к существующему runtime descriptor.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--runtime"], required = true)
        lateinit var runtime: Path

        /**
         * **Назначение:** Указать путь к файлу capability для обращения к Admission/Frontier.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--admission-token-file"], required = true)
        lateinit var token: Path

        /**
         * **Назначение:** Проверить scope и одобрение, запустить исполнение и вывести результат.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            require((id == null) != (session == null)) { "Choose --plan or --session" }
            if (session != null) {
                val workflow = parent.sessions()
                if (approve) workflow.approve(session!!)
                val result = workflow.run(session!!, runtime, token, totalSeconds, sliceSeconds, requestTimeout, resume)
                parent.backend.print(result)
                val failures =
                    ((result["metrics"] as? Map<*, *>)?.get("seeds_execution_failed") as? Number)?.toInt() ?: 0
                return if (result["session_status"] == "CANCELLED" || failures > 0) 6 else 0
            }
            val backend = parent.backend
            val actualId = id!!
            val plan = backend.existing(actualId)
            // Не расширяем уже сохранённый scope и не пересоздаём план при resume.
            require((plan["depth"] as Number).toInt() in 0..2 && (plan["max_pages"] as Number).toInt() in 1..20) {
                "Plan exceeds research bounds"
            }

            require(plan["approved"] == true || approve) { "Explicit approval required; inspect the stored plan first" }
            if (plan["approved"] != true) backend.approve(actualId)
            val execution = backend.start(actualId, runtime, token)
            backend.print(
                execution +
                    mapOf(
                        "research_id" to id,
                        "next" to "research results $id",
                        "completion_basis" to "USE_AUTHORITATIVE_STATUS"))
            return if (execution["passed"] == true) 0 else 6
        }
    }

    /**
     * **Назначение:** Показать состояние, архивные страницы, ссылки и ограниченные метрики исследования.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "results",
        mixinStandardHelpOptions = true,
        description = ["Read actual scheduler observations, archived pages and bounded graph metrics."]
    )
    class Results : Callable<Int> {

        /**
         * **Назначение:** Получить родительскую команду, назначенную Picocli.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @ParentCommand
        lateinit var parent: ResearchCommands

        /**
         * **Назначение:** Указать идентификатор research-плана или сессии.
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
         * **Назначение:** Собрать и вывести результаты сессии либо наблюдения и метрики legacy-плана.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            if (parent.isSession(id)) {
                val result = parent.sessions().results(id, runtime, token)
                parent.backend.print(result)
                return 0
            }
            val b = parent.backend
            val status = b.status(id, runtime, token)
            val pages = rows(b.pages(id), "pages")
            val links = rows(b.links(id), "links")
            val files = links.filter { it["disposition"] == "METADATA_ONLY" }
            val counts = status["counts"] as? Map<*, *>
            b.print(
                mapOf(
                    "research_id" to id,
                    "status" to status,
                    "pages" to pages,
                    "links" to links,
                    "file_candidates" to files,
                    "metrics" to
                        mapOf(
                            "archived_pages" to status["archived_pages"],
                            "listed_html_bytes" to pages.sumOf { (it["content_size"] as Number).toLong() },
                            "listed_links" to links.size,
                            "listed_file_candidates" to files.size,
                            "admitted_urls" to counts?.get("reserved"),
                            "pending_urls" to counts?.get("pending"),
                            "failure_observations" to status["failure_observation_count"],
                            "fetch_attempts" to null,
                            "retry_attempts" to null,
                            "fetch_duration_ms" to null),
                    "read_limit" to 100,
                    "pages_may_be_truncated" to (pages.size == 100),
                    "links_may_be_truncated" to (links.size == 100),
                    "binary_downloads" to 0))
            return if (runtime != null && status["authoritative_readback_current"] != true) 6 else 0
        }
    }

    /**
     * **Назначение:** Показать наблюдавшиеся ссылки и результаты admission.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "links",
        mixinStandardHelpOptions = true,
        description = ["Read observed links and admission dispositions."]
    )
    class Links : Callable<Int> {

        /**
         * **Назначение:** Получить родительскую команду, назначенную Picocli.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @ParentCommand
        lateinit var parent: ResearchCommands

        /**
         * **Назначение:** Указать идентификатор research-плана или сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var id: String

        /**
         * **Назначение:** Вывести сохранённые ссылки с признаком возможного усечения.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            if (parent.isSession(id)) {
                val result = parent.sessions().results(id, null, null)
                parent.backend.print(
                    mapOf(
                        "session_id" to id,
                        "links" to result["links"],
                        "may_be_truncated" to result["links_may_be_truncated"]))
                return 0
            }
            val links = rows(parent.backend.links(id), "links")
            parent.backend.print(
                mapOf("research_id" to id, "links" to links, "limit" to 100, "may_be_truncated" to (links.size == 100)))
            return 0
        }
    }

    /**
     * **Назначение:** Показать кандидаты файловых ссылок без скачивания бинарных данных.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "files",
        mixinStandardHelpOptions = true,
        description = ["Read file-link candidates; never downloads binaries."]
    )
    class Files : Callable<Int> {

        /**
         * **Назначение:** Получить родительскую команду, назначенную Picocli.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @ParentCommand
        lateinit var parent: ResearchCommands

        /**
         * **Назначение:** Указать идентификатор research-плана или сессии.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Parameters(index = "0")
        lateinit var id: String

        /**
         * **Назначение:** Вывести метаданные файловых кандидатов без загрузки файлов.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            if (parent.isSession(id)) {
                val result = parent.sessions().results(id, null, null)
                parent.backend.print(
                    mapOf(
                        "session_id" to id,
                        "file_candidates" to result["file_candidates"],
                        "binary_downloads" to 0,
                        "downloads_implemented" to false))
                return 0
            }
            val links = rows(parent.backend.links(id), "links")
            parent.backend.print(
                mapOf(
                    "research_id" to id,
                    "file_candidates" to links.filter { it["disposition"] == "METADATA_ONLY" },
                    "source_link_limit" to 100,
                    "may_be_truncated" to (links.size == 100),
                    "binary_downloads" to 0))
            return 0
        }
    }

    /**
     * **Назначение:** Выполнить локальный поиск по URL, заголовку и тексту архивных страниц.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    @Command(
        name = "search",
        mixinStandardHelpOptions = true,
        description = ["Search locally archived URL/title/text; optional exact host and research job filters."]
    )
    class Search : Callable<Int> {

        /**
         * **Назначение:** Получить родительскую команду, назначенную Picocli.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @ParentCommand
        lateinit var parent: ResearchCommands

        /**
         * **Назначение:** Задать поисковый запрос для выбранного источника поиска.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--query"], required = true)
        lateinit var query: String

        /**
         * **Назначение:** Ограничить локальный поиск указанным crawl job.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--job"])
        var job: String? = null

        /**
         * **Назначение:** Ограничить локальный поиск точным host.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--host"])
        var host: String? = null

        /**
         * **Назначение:** Ограничить число возвращаемых результатов поиска.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--limit"], defaultValue = "20")
        var limit = 20

        /**
         * **Назначение:** Выбрать поддерживаемый источник поиска.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Option(names = ["--provider"], defaultValue = "local")
        lateinit var provider: String

        /**
         * **Назначение:** Проверить локальный provider и вывести результаты архивного поиска.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        override fun call(): Int {
            require(provider == "local") {
                "Automated provider search is unavailable; use search-provider for manual discovery and crawl plan --import for selected seeds"
            }
            val result = parent.backend.search(query, job, host, limit)
            parent.backend.print(
                result +
                    mapOf(
                        "network_requests" to 0,
                        "search_fields" to listOf("url", "title", "text"),
                        "may_be_truncated" to (rows(result, "results").size == limit)))
            return 0
        }
    }

    /**
     * **Назначение:** Сгруппировать общие служебные механизмы команд.
     *
     * @author Stephan Kolesnikov
     * @author GPT-6.1 Sol
     */
    companion object {

        /**
         * **Назначение:** Прочитать список строк из поля документа с ожидаемой структурой.
         *
         * @author Stephan Kolesnikov
         * @author GPT-6.1 Sol
         */
        @Suppress("UNCHECKED_CAST")
        private fun rows(document: Map<String, Any?>, key: String): List<Map<String, Any?>> =
            document[key] as List<Map<String, Any?>>
    }
}
