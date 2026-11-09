package dev.onionall.brawler.cli

import java.nio.file.Path
import java.util.concurrent.Callable
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand

/**
 * Ограниченный research workflow над существующими планами и Frontier.
 * Research ID равен plan ID; второй scheduler или каталог не создаётся.
 * Kotlin компилируется первым: Java wiring передаёт существующие сервисы через Backend.
 *
 * @author Стефан Колесников
 * @author GPT-6 Astra
 */
@Command(name = "research", mixinStandardHelpOptions = true,
    description = ["Plan, explicitly approve/run and inspect bounded research."],
    subcommands = [ResearchCommands.Plan::class, ResearchCommands.Run::class,
        ResearchCommands.Results::class, ResearchCommands.Links::class, ResearchCommands.Files::class,
        ResearchCommands.Search::class])
class ResearchCommands(private val backend: Backend) : Callable<Int> {
    /** Узкая граница компиляции: реализация делегирует существующим Java services. */
    interface Backend {
        @Throws(Exception::class) fun plan(url: String, depth: Int, pages: Int): Map<String, Any?>
        @Throws(Exception::class) fun existing(id: String): Map<String, Any?>
        @Throws(Exception::class) fun approve(id: String): Map<String, Any?>
        @Throws(Exception::class) fun start(id: String, runtime: Path, token: Path): Map<String, Any?>
        @Throws(Exception::class) fun status(id: String, runtime: Path?, token: Path?): Map<String, Any?>
        @Throws(Exception::class) fun pages(id: String): Map<String, Any?>
        @Throws(Exception::class) fun links(id: String): Map<String, Any?>
        @Throws(Exception::class) fun search(query: String, id: String?, host: String?, limit: Int): Map<String, Any?>
        @Throws(Exception::class) fun print(value: Map<String, Any?>)
    }

    override fun call(): Int {
        backend.print(mapOf("commands" to listOf("plan", "run", "results", "links", "files", "search"),
            "approval_required" to true, "scheduling_owner" to "URLFrontier"))
        return 0
    }

    @Command(name = "plan", mixinStandardHelpOptions = true,
        description = ["Validate one onion URL and persist its scope; no network or approval."])
    class Plan : Callable<Int> {
        @ParentCommand lateinit var parent: ResearchCommands
        @Option(names = ["--url"], required = true) lateinit var url: String
        @Option(names = ["--depth"], defaultValue = "2") var depth = 2
        @Option(names = ["--max-pages"], defaultValue = "20") var pages = 20
        override fun call(): Int {
            require(depth in 0..2 && pages in 1..20) { "Research requires depth 0..2 and max-pages 1..20" }
            val plan = parent.backend.plan(url, depth, pages)
            parent.backend.print(plan + mapOf("research_id" to plan["plan_id"],
                "next" to "Inspect scope, then research run --plan ${plan["plan_id"]} --approve",
                "network_requests" to 0))
            return 0
        }
    }

    @Command(name = "run", mixinStandardHelpOptions = true,
        description = ["Run an approved plan through existing Admission/Storm; --approve grants explicit approval."])
    class Run : Callable<Int> {
        @ParentCommand lateinit var parent: ResearchCommands
        @Option(names = ["--plan"], required = true) lateinit var id: String
        @Option(names = ["--approve"]) var approve = false
        @Option(names = ["--runtime"], required = true) lateinit var runtime: Path
        @Option(names = ["--admission-token-file"], required = true) lateinit var token: Path
        override fun call(): Int {
            val backend = parent.backend
            val plan = backend.existing(id)
            // Не расширяем уже сохранённый scope и не пересоздаём план при resume.
            require((plan["depth"] as Number).toInt() in 0..2 &&
                (plan["max_pages"] as Number).toInt() in 1..20) { "Plan exceeds research bounds" }
            require(plan["approved"] == true || approve) { "Explicit approval required; inspect the stored plan first" }
            if (plan["approved"] != true) backend.approve(id)
            val execution = backend.start(id, runtime, token)
            backend.print(execution + mapOf("research_id" to id,
                "next" to "research results $id", "completion_basis" to "USE_AUTHORITATIVE_STATUS"))
            return if (execution["passed"] == true) 0 else 6
        }
    }

    @Command(name = "results", mixinStandardHelpOptions = true,
        description = ["Read actual scheduler observations, archived pages and bounded graph metrics."])
    class Results : Callable<Int> {
        @ParentCommand lateinit var parent: ResearchCommands
        @Parameters(index = "0") lateinit var id: String
        @Option(names = ["--runtime"]) var runtime: Path? = null
        @Option(names = ["--admission-token-file"]) var token: Path? = null
        override fun call(): Int {
            val b = parent.backend
            val status = b.status(id, runtime, token)
            val pages = rows(b.pages(id), "pages")
            val links = rows(b.links(id), "links")
            val files = links.filter { it["disposition"] == "METADATA_ONLY" }
            val counts = status["counts"] as? Map<*, *>
            b.print(mapOf("research_id" to id, "status" to status, "pages" to pages,
                "links" to links, "file_candidates" to files, "metrics" to mapOf(
                    "archived_pages" to status["archived_pages"],
                    "listed_html_bytes" to pages.sumOf { (it["content_size"] as Number).toLong() },
                    "listed_links" to links.size, "listed_file_candidates" to files.size,
                    "admitted_urls" to counts?.get("reserved"), "pending_urls" to counts?.get("pending"),
                    "failure_observations" to status["failure_observation_count"],
                    "fetch_attempts" to null, "retry_attempts" to null, "fetch_duration_ms" to null),
                "read_limit" to 100, "pages_may_be_truncated" to (pages.size == 100),
                "links_may_be_truncated" to (links.size == 100), "binary_downloads" to 0))
            return if (runtime != null && status["authoritative_readback_current"] != true) 6 else 0
        }
    }

    @Command(name = "links", mixinStandardHelpOptions = true, description = ["Read observed links and admission dispositions."])
    class Links : Callable<Int> {
        @ParentCommand lateinit var parent: ResearchCommands
        @Parameters(index = "0") lateinit var id: String
        override fun call(): Int {
            val links = rows(parent.backend.links(id), "links")
            parent.backend.print(mapOf("research_id" to id, "links" to links,
                "limit" to 100, "may_be_truncated" to (links.size == 100)))
            return 0
        }
    }

    @Command(name = "files", mixinStandardHelpOptions = true, description = ["Read file-link candidates; never downloads binaries."])
    class Files : Callable<Int> {
        @ParentCommand lateinit var parent: ResearchCommands
        @Parameters(index = "0") lateinit var id: String
        override fun call(): Int {
            val links = rows(parent.backend.links(id), "links")
            parent.backend.print(mapOf("research_id" to id,
                "file_candidates" to links.filter { it["disposition"] == "METADATA_ONLY" },
                "source_link_limit" to 100, "may_be_truncated" to (links.size == 100), "binary_downloads" to 0))
            return 0
        }
    }

    @Command(name = "search", mixinStandardHelpOptions = true,
        description = ["Search locally archived URL/title/text; optional exact host and research job filters."])
    class Search : Callable<Int> {
        @ParentCommand lateinit var parent: ResearchCommands
        @Option(names = ["--query"], required = true) lateinit var query: String
        @Option(names = ["--job"]) var job: String? = null
        @Option(names = ["--host"]) var host: String? = null
        @Option(names = ["--limit"], defaultValue = "20") var limit = 20
        @Option(names = ["--provider"], defaultValue = "local") lateinit var provider: String
        override fun call(): Int {
            require(provider == "local") {
                "Automated provider search is unavailable; use search-provider for manual discovery and crawl plan --import for selected seeds"
            }
            val result = parent.backend.search(query, job, host, limit)
            parent.backend.print(result + mapOf("network_requests" to 0,
                "search_fields" to listOf("url", "title", "text"),
                "may_be_truncated" to (rows(result, "results").size == limit)))
            return 0
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun rows(document: Map<String, Any?>, key: String): List<Map<String, Any?>> =
            document[key] as List<Map<String, Any?>>
    }
}
