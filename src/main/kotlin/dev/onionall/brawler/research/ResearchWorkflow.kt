package dev.onionall.brawler.research

import com.fasterxml.jackson.databind.ObjectMapper
import dev.onionall.brawler.tor.TorPolicy
import java.io.IOException
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*

/**
 * Прикладная batch-координация: один неизменяемый Frontier scope на каждый seed.
 * Статические child caps суммарно укладываются в global cap; live quota владеет Frontier.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
class ResearchWorkflow @JvmOverloads constructor(private val backend: Backend,
    private val provider: ProviderCall = ProviderCall { runtime, query, limit ->
        ResearchTorTransport(runtime).use { AhmiaDiscoveryProvider(it).discover(query, limit) }
    }) {
    /** Java adapter только соединяет Kotlin workflow с существующими services. */
    interface Backend {
        fun archiveRoot(): Path
        @Throws(Exception::class) fun create(document: Map<String, Any?>): Map<String, Any?>
        @Throws(Exception::class) fun load(id: String): Map<String, Any?>
        @Throws(Exception::class) fun save(id: String, revision: Long, document: Map<String, Any?>): Map<String, Any?>
        @Throws(Exception::class) fun exists(id: String): Boolean
        @Throws(Exception::class) fun plan(url: String, depth: Int, pages: Int, provider: String): Map<String, Any?>
        @Throws(Exception::class) fun approve(job: String): Map<String, Any?>
        @Throws(Exception::class) fun start(job: String, runtime: Path, token: Path): Map<String, Any?>
        @Throws(Exception::class) fun status(job: String, runtime: Path?, token: Path?): Map<String, Any?>
        @Throws(Exception::class) fun pages(job: String): Map<String, Any?>
        @Throws(Exception::class) fun page(id: String): Map<String, Any?>
        @Throws(Exception::class) fun links(job: String): Map<String, Any?>
        @Throws(Exception::class) fun errors(job: String): Map<String, Any?>
        @Throws(Exception::class) fun print(value: Map<String, Any?>)
    }
    fun interface ProviderCall {
        @Throws(Exception::class) fun discover(runtime: Path, query: String, limit: Int): DiscoveryBatch
    }
    fun exists(id: String) = backend.exists(id)
    fun print(value: Map<String, Any?>) = backend.print(value)
    fun session(id: String): Map<String, Any?> = backend.load(id)

    @Throws(Exception::class)
    fun discover(query: String, limit: Int, runtime: Path): Map<String, Any?> {
        require(query.isNotBlank() && query.length <= 256 && limit in 1..20) { "Require query 1..256 and limit 1..20" }
        var doc = backend.create(base(query, "ahmia") + mapOf("discovery_state" to "RUNNING"))
        try {
            val batch = runBlocking { withTimeout(100_000) {
                runInterruptible(Dispatchers.IO) { provider.discover(runtime, query, limit) }
            } }
            doc = save(doc, batch.toMap() + mapOf("candidates" to batch.candidates.map {
                it.toMap() + mapOf("selected" to false) }, "discovery_state" to "SUCCEEDED",
                "authorization_scope" to "OWNER_AUTHORIZED_AHMIA_DISCOVERY"))
        } catch (failure: Exception) {
            doc = save(doc, mapOf("discovery_state" to "FAILED", "error_category" to
                if (failure is TimeoutCancellationException) "PROVIDER_TIMEOUT" else errorCategory(failure)))
        }
        return doc
    }

    @Throws(Exception::class)
    fun importSeeds(urls: List<String>, label: String): Map<String, Any?> {
        require(urls.isNotEmpty() && urls.size <= 20 && label.length <= 256)
        val candidates = urls.map(::canonical).distinct().map { url -> mapOf<String, Any?>(
            "candidate_id" to UUID.randomUUID().toString(), "original_result_url" to url,
            "canonical_url" to url, "host" to URI(url).host, "provider" to "manual",
            "validation_status" to "VALID", "duplicate_group" to url,
            "title" to null, "snippet" to null, "selected" to false,
            "discovered_at" to Instant.now().toString(), "provenance" to listOf("USER_SUPPLIED")) }
        return backend.create(base(label, "manual") + mapOf("candidates" to candidates,
            "discovery_state" to "MANUAL_INPUT", "network_requests" to 0,
            "actual_result_count" to candidates.size))
    }

    @Throws(Exception::class)
    fun select(id: String, ids: List<String>, all: Boolean, exclude: List<String>): Map<String, Any?> {
        val doc = session(id)
        require(doc["planned"] != true) { "Selection is frozen after planning; create another session" }
        val rows = maps(doc["candidates"])
        val available = rows.map { it["candidate_id"] as String }.toSet()
        require(available.containsAll(ids) && available.containsAll(exclude)) { "Unknown candidate ID" }
        val selected = if (all) available else rows.filter { it["selected"] == true }
            .map { it["candidate_id"] as String }.toSet() + ids
        return save(doc, mapOf("candidates" to rows.map { row ->
            row + mapOf("selected" to (row["candidate_id"] in selected && row["candidate_id"] !in exclude &&
                row["validation_status"] == "VALID")) }))
    }

    @Throws(Exception::class)
    fun plan(id: String, depth: Int, pagesPerSeed: Int, globalPages: Int): Map<String, Any?> {
        var doc = session(id)
        require(depth in 0..(if (doc["mode"] == "THREAD") 4 else 3) && pagesPerSeed in 1..20 && globalPages in 1..100)
        val selected = maps(doc["candidates"]).filter { it["selected"] == true }
        require(selected.isNotEmpty() && selected.size <= 20) { "Select 1..20 valid candidates first" }
        require(selected.size * pagesPerSeed <= globalPages) { "Child page caps exceed separate global page budget" }
        if (doc["planned"] == true) {
            require(number(doc["depth"]) == depth && number(doc["pages_per_seed"]) == pagesPerSeed &&
                number(doc["global_page_budget"]) == globalPages) { "Stored scope is immutable" }
            return if (doc["planning_state"] == "READY") doc else prepareJobs(doc)
        }
        // Публикуем immutable allocation до создания child plans; незавершённое plan можно возобновить.
        doc = save(doc, mapOf("planned" to true, "depth" to depth, "pages_per_seed" to pagesPerSeed,
            "global_page_budget" to globalPages, "planning_state" to "PREPARING"))
        return prepareJobs(doc)
    }

    private fun prepareJobs(initial: Map<String, Any?>): Map<String, Any?> {
        var doc = initial
        for (candidate in maps(doc["candidates"]).filter { it["selected"] == true }) {
            if (maps(doc["seeds"]).any { it["candidate_id"] == candidate["candidate_id"] }) continue
            val url = canonical(candidate["canonical_url"] as String)
            val job = backend.plan(url, number(doc["depth"]), number(doc["pages_per_seed"]),
                if (doc["mode"] == "THREAD") "m61:thread" else "m6:" + doc["provider"])
            doc = save(doc, mapOf("seeds" to maps(doc["seeds"]) + mapOf(
                "candidate_id" to candidate["candidate_id"], "url" to url, "host" to URI(url).host,
                "job_id" to job["plan_id"], "execution_state" to "PENDING")))
        }
        return save(doc, mapOf("planning_state" to "READY"))
    }

    /** Отдельный approved child: обычная navigation запрещена, Frontier depth считает pagination hops. */
    fun threadPlan(url: String, maxPages: Int): Map<String, Any?> {
        require(maxPages in 1..5) { "Thread page budget must be 1..5" }
        val seed = ForumPagination.canonical(url)
        val created = importSeeds(listOf(seed), "Thread capture")
        val id = created["session_id"] as String
        patch(id) { mapOf("mode" to "THREAD", "thread_seed_url" to seed, "thread_max_pages" to maxPages,
            "navigation_depth" to 0, "pagination_budget_basis" to "SEPARATE_IMMUTABLE_FRONTIER_PLAN") }
        select(id, emptyList(), true, emptyList())
        return plan(id, maxPages - 1, maxPages, maxPages)
    }

    @Throws(Exception::class)
    fun approve(id: String): Map<String, Any?> {
        var doc = session(id)
        require(doc["planned"] == true) { "Inspect a stored batch plan first" }
        if (doc["planning_state"] != "READY") doc = prepareJobs(doc)
        // Повтор после crash подтверждает только уже frozen child plans.
        maps(doc["seeds"]).forEach { backend.approve(it["job_id"] as String) }
        return if (doc["approved"] == true) doc else save(doc, mapOf("approved" to true,
            "approved_at" to Instant.now().toString()))
    }

    @Throws(Exception::class)
    fun run(id: String, runtime: Path, token: Path, totalSeconds: Int, sliceSeconds: Int,
        requestTimeoutMs: Int, resume: Boolean): Map<String, Any?> {
        require(totalSeconds in 30..3600 && sliceSeconds in 10..180 && requestTimeoutMs in 1000..30000)
        require(totalSeconds >= sliceSeconds + 180) { "Session budget must include a worker slice and bounded cleanup" }
        val root = backend.archiveRoot().toAbsolutePath().normalize()
        val lockPath = root.resolve("research-run.lock")
        require(!Files.isSymbolicLink(lockPath)) { "Unsafe research lock path" }
        FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            (lock ?: throw IOException("Another research batch is active")).use {
                var doc = session(id)
                require(doc["approved"] == true && doc["planning_state"] == "READY") { "Explicit batch approval required" }
                val configured = runtimeConfig(runtime)
                val runs = root.resolve("research-runs")
                for (path in generateSequence(runs) { it.parent }) require(!Files.isSymbolicLink(path)) { "Unsafe research runtime path" }
                Files.createDirectories(runs)
                val runDir = Files.createDirectory(runs.resolve(UUID.randomUUID().toString()))
                val descriptor = runDir.resolve("runtime.json")
                Files.writeString(descriptor, ObjectMapper().writeValueAsString(configured + mapOf(
                    "seconds" to sliceSeconds, "http_timeout_ms" to requestTimeoutMs)), CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                val deadline = System.nanoTime() + totalSeconds * 1_000_000_000L
                doc = patch(id) { mapOf("run_state" to "RUNNING", "run_started_at" to Instant.now().toString()) }
                var ended = "PAUSED"
                for (seed in maps(doc["seeds"]).sortedBy { if (it["execution_state"] == "PENDING") 0 else 1 }) {
                    if (seed["execution_state"] != "PENDING" && !resume) continue
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining < (sliceSeconds + 180L) * 1000) { ended = "PAUSED_TIME_BUDGET"; break }
                    val job = seed["job_id"] as String
                    doc = updateSeed(doc, job, mapOf("execution_state" to "RUNNING", "attempted_at" to Instant.now().toString()))
                    try {
                        val execution = runBlocking { withTimeout(minOf(remaining, (sliceSeconds + 180L) * 1000)) {
                            runInterruptible(Dispatchers.IO) { backend.start(job, descriptor, token) }
                        } }
                        doc = updateSeed(doc, job, mapOf("execution_state" to if (execution["passed"] == true)
                            "ATTEMPTED" else "EXECUTION_FAILED", "last_worker_exit_code" to execution["worker_exit_code"]))
                    } catch (failure: Exception) {
                        val cancelled = failure is CancellationException || Thread.currentThread().isInterrupted
                        doc = updateSeed(doc, job, mapOf("execution_state" to if (cancelled) "CANCELLED" else "EXECUTION_FAILED",
                            "error_category" to if (failure is TimeoutCancellationException) "EXECUTION_TIMEOUT" else errorCategory(failure)))
                        if (cancelled) { ended = if (failure is TimeoutCancellationException) "PAUSED_TIME_BUDGET" else "CANCELLED"; break }
                    }
                }
                patch(id) { mapOf("run_state" to ended, "run_finished_at" to Instant.now().toString()) }
                return results(id, descriptor, token)
            }
        }
    }

    @Throws(Exception::class)
    fun results(id: String, runtime: Path?, token: Path?): Map<String, Any?> {
        var doc = session(id)
        val seeds = maps(doc["seeds"])
        val hosts = seeds.map { seed ->
            val job = seed["job_id"] as String
            val observation = try { backend.status(job, runtime, token) } catch (failure: Exception) {
                mapOf("authoritative_readback_current" to false, "observation_error" to failure.javaClass.simpleName)
            }
            mapOf("candidate_id" to seed["candidate_id"], "job_id" to job, "url" to seed["url"],
                "host" to seed["host"], "execution_state" to seed["execution_state"], "status" to observation)
        }
        val pages = seeds.flatMap { maps(backend.pages(it["job_id"] as String)["pages"]) }
        val details = readPageDetails(pages).associateBy { it["page_id"] as String }
        val links = details.values.flatMap { maps(it["links"]) }
        val errors = seeds.flatMap { maps(backend.errors(it["job_id"] as String)["rows"]) }
        val threads = ResearchThreads.extract(doc, details.values.toList(), backend.archiveRoot())
        val files = ResearchThreads.attachPostSources(ResearchFiles.extract(id, pages, { details.getValue(it) }, backend.archiveRoot()), threads)
        if (doc["file_candidates"] != files || doc["threads"] != threads) doc = patch(id) { fresh ->
            mapOf("file_candidates" to (maps(fresh["file_candidates"]) + files)
                .associateBy { it["file_id"] }.values.take(ResearchFiles.MAX_CANDIDATES),
                "threads" to threads, "discussion_cache_basis" to "DERIVED_VERIFIED_ARCHIVE_SNAPSHOTS")
        }
        val storedFiles = maps(doc["file_candidates"])
        val current = hosts.isNotEmpty() && hosts.all { (it["status"] as Map<*, *>)["authoritative_readback_current"] == true }
        fun count(key: String): Long? {
            if (!current) return null
            val counts = hosts.map { ((it["status"] as Map<*, *>)["counts"] as? Map<*, *>)?.get(key) as? Number }
            return if (counts.any { it == null }) null else counts.sumOf { it!!.toLong() }
        }
        val attempted = seeds.count { it["execution_state"] != "PENDING" }
        val perHost = hosts.groupBy { it["host"] }.map { (host, scopes) ->
            val observations = scopes.map { it["status"] as Map<*, *> }
            val live = observations.all { it["authoritative_readback_current"] == true }
            val aggregateCounts = listOf("reserved", "pending", "held", "scheduled_wait", "unscheduled", "unknown").associateWith { key ->
                val values = observations.map { (it["counts"] as? Map<*, *>)?.get(key) as? Number }
                if (!live || values.any { it == null }) null else values.sumOf { it!!.toLong() }
            }
            mapOf("host" to host, "seed_scope_count" to scopes.size, "seed_scopes" to scopes,
                "status" to if (scopes.size == 1) scopes.first()["status"] else mapOf(
                    "authoritative_readback_current" to live, "counts" to aggregateCounts,
                    "completion" to "UNKNOWN", "terminal_provenance" to "UNKNOWN_FRONTIER_V1"))
        }
        val state = when {
            doc["approved"] != true -> if (doc["planned"] == true) "AWAITING_APPROVAL" else "DRAFT"
            doc["run_state"] == "CANCELLED" -> "CANCELLED"
            hosts.any { (it["status"] as Map<*, *>)["job_state"] == "RUNNING" } -> "RUNNING"
            seeds.any { it["execution_state"] == "RUNNING" } -> "UNKNOWN"
            attempted < seeds.size -> "PENDING"
            seeds.isNotEmpty() && seeds.all { it["execution_state"] == "EXECUTION_FAILED" } -> "EXECUTION_FAILED"
            seeds.any { it["execution_state"] == "EXECUTION_FAILED" } -> "PARTIAL"
            !current && !(doc["run_state"] in setOf("PAUSED", "PAUSED_TIME_BUDGET") && doc["run_finished_at"] is String) -> "UNKNOWN"
            else -> "PAUSED"
        }
        return mapOf("session_id" to id, "session_status" to state, "session" to doc,
            "status_basis" to if (current) "CURRENT_OBSERVATION" else if (state == "PAUSED") "PERSISTED_BOUNDED_RUN" else "PERSISTED_EXECUTION_AND_OBSERVATIONS",
            "scheduler_readback_current" to current,
            "acquisition_status" to if (pages.isEmpty()) "NO_ARCHIVED_HTML" else if (seeds.any { seed -> pages.none { it["job_id"] == seed["job_id"] } }) "PARTIAL" else "DATA_ACQUIRED",
            "per_host" to perHost, "per_seed" to hosts, "pages" to pages, "links" to links, "file_candidates" to storedFiles,
            "threads" to threads,
            "discussion_scope_rejected_pages" to if (doc["mode"] == "THREAD") pages.filter {
                ForumPagination.identity(it["canonical_url"] as String) != ForumPagination.identity(doc["thread_seed_url"] as String)
            }.map { mapOf("page_id" to it["page_id"], "url" to it["canonical_url"], "reason" to "OUTSIDE_APPROVED_THREAD_IDENTITY") } else emptyList<Map<String, Any?>>(),
            "errors" to errors, "metrics" to mapOf("pages_fetched" to null, "pages_archived" to pages.size,
                "listed_link_observations" to links.size,
                "threads_extracted" to threads.size, "posts_extracted" to threads.sumOf { maps(it["posts"]).size },
                "html_bytes" to pages.sumOf { (it["content_size"] as Number).toLong() },
                "seeds_selected" to maps(doc["candidates"]).count { it["selected"] == true },
                "seeds_planned" to seeds.size, "seeds_attempted" to attempted,
                "seeds_execution_failed" to seeds.count { it["execution_state"] == "EXECUTION_FAILED" },
                "seeds_succeeded" to null, "seeds_failed" to null,
                "seeds_with_archived_html" to seeds.count { seed -> pages.any { it["job_id"] == seed["job_id"] } },
                "pending_urls" to count("pending"), "held_urls" to count("held"),
                "scheduled_wait" to count("scheduled_wait"), "scheduled_retries" to null,
                "terminal_failures" to null, "completed" to null),
            "terminal_provenance" to "UNKNOWN_FRONTIER_V1", "binary_downloads" to 0,
            "files_may_be_truncated" to (storedFiles.size == ResearchFiles.MAX_CANDIDATES),
            "links_view" to "LATEST_SNAPSHOTS", "per_page_link_limit" to 100,
            "links_may_be_truncated" to details.values.any { maps(it["links"]).size == 100 })
    }

    fun pageDetails(result: Map<String, Any?>): List<Map<String, Any?>> = readPageDetails(maps(result["pages"])).map { page ->
        val parsed = ForumExtractor.extract(page["canonical_url"] as String, ResearchFiles.verifiedHtml(page, backend.archiveRoot()))
        page + mapOf("readable_text" to parsed["page_text"], "readable_extraction_status" to parsed["extraction_status"],
            "readable_extraction_method" to parsed["extraction_method"],
            "readable_text_truncated" to (parsed["evidence"] as? Map<*, *>)?.get("page_text_truncated"))
    }
    private fun readPageDetails(pages: List<Map<String, Any?>>): List<Map<String, Any?>> = pages.map { row ->
        val page = backend.page(row["page_id"] as String)
        // Даже одинаковый HTML может принадлежать новому snapshot с другими links/time metadata.
        if (listOf("page_id", "job_id", "snapshot_id", "content_hash").any { page[it] != row[it] }) {
            throw IOException("Archive snapshot changed; inspect after the worker stops")
        }
        page
    }
    private fun updateSeed(doc: Map<String, Any?>, job: String, fields: Map<String, Any?>) = patch(doc["session_id"] as String) { fresh ->
        mapOf("seeds" to maps(fresh["seeds"]).map { if (it["job_id"] == job) it + fields else it }) }
    // Observer может обновить file metadata между child jobs; сливаем только собственные поля.
    private fun patch(id: String, fields: (Map<String, Any?>) -> Map<String, Any?>): Map<String, Any?> {
        repeat(3) {
            val fresh = session(id)
            try { return save(fresh, fields(fresh)) } catch (failure: IOException) {
                if (failure.message != "Research document changed; reload before retrying") throw failure
            }
        }
        throw IOException("Research session is busy; reload before retrying")
    }
    private fun errorCategory(failure: Exception): String = failure.message?.takeIf {
        it.matches(Regex("(?:PROVIDER_[A-Z0-9_]+|REQUEST_TIMEOUT|SOCKS_FAILURE|ONION_UNREACHABLE|PROTOCOL_CLOSED)"))
    } ?: failure.javaClass.simpleName
    private fun save(doc: Map<String, Any?>, fields: Map<String, Any?>): Map<String, Any?> =
        backend.save(doc["session_id"] as String, (doc["revision"] as Number).toLong(), doc + fields)
    private fun base(query: String, provider: String) = mapOf<String, Any?>("schema_version" to 1,
        "session_id" to UUID.randomUUID().toString(), "query" to query, "provider" to provider,
        "created_at" to Instant.now().toString(), "candidates" to emptyList<Any>(),
        "seeds" to emptyList<Any>(), "approved" to false, "planned" to false, "run_state" to "NOT_STARTED")
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun maps(value: Any?): List<Map<String, Any?>> = (value as? List<*>)?.map { it as Map<String, Any?> } ?: emptyList()
        private fun number(value: Any?) = (value as? Number)?.toInt() ?: -1
        fun canonical(value: String): String {
            require(value.length <= 2048)
            val uri = URI(value)
            val host = uri.host ?: throw IllegalArgumentException("Require onion URL")
            require(TorPolicy.validOnion(host)) { "Require valid onion v3 host" }
            val policy = TorPolicy(mapOf("tor.mode" to "ONLY", "tor.socks.host" to "127.0.0.1",
                "tor.socks.port" to 9050, "tor.allowed.hosts" to listOf(host)))
            val normalized = URI(policy.normalize(value))
            return "${normalized.scheme}://${normalized.host}${normalized.rawPath}" +
                (normalized.rawQuery?.let { "?$it" } ?: "")
        }
        private fun runtimeConfig(path: Path): Map<String, Any?> {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= 32768)
            return ObjectMapper().readValue(Files.readAllBytes(path), object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {})
        }
    }
}
