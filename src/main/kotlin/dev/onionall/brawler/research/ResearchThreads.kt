package dev.onionall.brawler.research

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.file.Path
import java.util.UUID

/**
 * Сводит только проверенные архивные snapshots; сетевых запросов и crawler здесь нет.
 * Internal IDs явно отделены от наблюдаемых post IDs. Coverage описывает только видимые ссылки.
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
object ResearchThreads {
    private const val MAX_POSTS = 1000
    private const val MAX_BYTES = 1572864
    private val json = ObjectMapper()

    fun extract(session: Map<String, Any?>, details: List<Map<String, Any?>>, root: Path): List<Map<String, Any?>> {
        val explicit = if (session["mode"] == "THREAD") session["thread_seed_url"] as? String else null
        val groups = linkedMapOf<String, MutableList<Pair<Map<String, Any?>, Map<String, Any?>>>>()
        for (page in details) {
            val source = page["canonical_url"] as String
            if (explicit != null && ForumPagination.identity(source) != ForumPagination.identity(explicit)) continue
            val html = ResearchFiles.verifiedHtml(page, root)
            val parsed = ForumExtractor.extract(source, html)
            val identity = ForumPagination.identity(explicit ?: source)
            val pagination = ForumPagination.observations(source, html)
            groups.getOrPut(identity) { mutableListOf() }.add(page to (parsed + mapOf("pagination" to pagination)))
        }
        var used = 0
        var count = 0
        // Сначала определяем discussion identity; затем сохраняем и generic/dynamic captures
        // той же темы. Home/category без доказанных post boundaries темами не становятся.
        val identified = groups.filterValues { captures -> explicit != null ||
            captures.any { ResearchWorkflow.maps(it.second["posts"]).isNotEmpty() } }
        return identified.entries.take(100).map { (identity, observations) ->
            val kinds = observations.flatMap { ForumPagination.counterKinds(it.first["canonical_url"] as String) }.toSet()
            val ambiguousOrder = kinds.size > 1
            val captures = if (ambiguousOrder || kinds.isEmpty()) observations else
                observations.sortedBy { ForumPagination.position(it.first["canonical_url"] as String) }
            val threadId = identifier("${session["session_id"]}|$identity")
            val pages = captures.map { (page, parsed) -> mapOf("page_id" to page["page_id"],
                "snapshot_id" to page["snapshot_id"], "url" to page["canonical_url"],
                "fetched_at" to page["fetched_at"], "recorded_at" to page["recorded_at"],
                "source_kind" to page["source_kind"], "job_id" to page["job_id"],
                "extraction_status" to parsed["extraction_status"], "extraction_method" to parsed["extraction_method"],
                "page_text_truncated" to (parsed["evidence"] as? Map<*, *>)?.get("page_text_truncated"),
                "limitations" to parsed["limitations"]) }
            val captured = pages.map { ForumPagination.canonical(it["url"] as String) }.toSet()
            val posts = linkedMapOf<String, Map<String, Any?>>()
            val conflicts = mutableListOf<String>()
            var limited = false
            for ((page, parsed) in captures) for (post in ResearchWorkflow.maps(parsed["posts"])) {
                val observedId = post["source_post_id"] as? String
                val source = page["canonical_url"] as String
                val provenance = mapOf("source_page_url" to source, "source_page_id" to page["page_id"],
                    "job_id" to page["job_id"],
                    "snapshot_id" to page["snapshot_id"], "fetched_at" to page["fetched_at"],
                    "recorded_at" to page["recorded_at"], "source_kind" to page["source_kind"])
                val unique = (post["evidence"] as? Map<*, *>)?.get("source_post_id_unique_on_page") == true
                val key = observedId?.takeIf { unique }?.let { "observed:$it" } ?: "page:$source:${page["snapshot_id"]}:${post["ordinal"]}"
                val previous = posts[key]
                if (previous != null && semantics(previous) == semantics(post)) {
                    val updated = previous + mapOf("provenance" to (ResearchWorkflow.maps(previous["provenance"]) + provenance).distinct())
                    val delta = json.writeValueAsBytes(updated).size - json.writeValueAsBytes(previous).size
                    if (used + delta > MAX_BYTES) limited = true
                    else { posts[key] = updated; used += delta }
                    continue
                }
                if (previous != null) conflicts.add(observedId ?: key)
                val distinctKey = if (previous == null) key else "$key|$source|${post["ordinal"]}"
                val value = post + provenance + mapOf("post_id" to identifier("$threadId|$distinctKey"),
                    "post_id_basis" to "INTERNAL_OBSERVATION_ID", "thread_id" to threadId,
                    "provenance" to listOf(provenance))
                val size = json.writeValueAsBytes(value).size
                if (count >= MAX_POSTS || used + size > MAX_BYTES) { limited = true; continue }
                posts[distinctKey] = value
                used += size
                count++
            }
            val pagination = captures.flatMap { (_, parsed) -> ResearchWorkflow.maps(parsed["pagination"]) }
                .distinctBy { listOf(it["source_url"], it["target_url"]) }.map { edge ->
                    val target = edge["target_url"] as String
                    val persisted = captures.flatMap { ResearchWorkflow.maps(it.first["links"]) }
                        .firstOrNull { it["source_url"] == edge["source_url"] && it["target_url"] == target }
                    edge + mapOf("captured" to (target in captured), "disposition" to (persisted?.get("disposition") ?: "OBSERVED_ONLY"))
                }
            val partial = ambiguousOrder || limited || conflicts.isNotEmpty() || captures.any { it.second["extraction_status"] in setOf("PARTIAL", "FAILED") } ||
                (posts.isNotEmpty() && captures.any { it.second["extraction_status"] != "STRUCTURED_POSTS" })
            val status = when {
                captures.all { it.second["extraction_status"] == "FAILED" } -> "FAILED"
                partial -> "PARTIAL"
                posts.isNotEmpty() -> "STRUCTURED_POSTS"
                captures.any { it.second["extraction_status"] == "UNSUPPORTED_DYNAMIC_CONTENT" } -> "UNSUPPORTED_DYNAMIC_CONTENT"
                else -> "PAGE_TEXT_ONLY"
            }
            val truncated = captures.any { ResearchWorkflow.maps(it.first["links"]).size >= 100 }
            val complete = pagination.isNotEmpty() && pagination.all { it["captured"] == true } && !truncated && !partial
            mapOf("thread_id" to threadId, "source_url" to (explicit ?: pages.first()["url"]),
                "canonical_thread_identity" to identity,
                "source_host" to URI(identity).host, "title" to captures.first().second["title"],
                "captured_pages" to pages, "observed_pagination" to pagination, "posts" to posts.values.toList(),
                "ordering_status" to if (ambiguousOrder) "AMBIGUOUS_PAGINATION_COUNTERS" else if (kinds.isEmpty()) "CAPTURE_ORDER_ONLY" else "VERIFIED_NUMERIC_PAGINATION",
                "post_order_basis" to if (ambiguousOrder || kinds.isEmpty()) "CAPTURE_ORDER_AND_DOM_ORDER" else "NUMERIC_PAGINATION_AND_DOM_ORDER",
                "extraction_status" to status, "completeness_status" to if (complete) "OBSERVED_PAGINATION_CAPTURED" else "PARTIAL_OR_UNKNOWN",
                "completeness_basis" to "ARCHIVED_HTML_LINKS_ONLY_NOT_SITE_EXHAUSTION", "extraction_limited" to limited,
                "conflicting_source_post_ids" to conflicts.distinct(), "full_thread_coverage_verified" to false)
        }
    }

    fun attachPostSources(files: List<Map<String, Any?>>, threads: List<Map<String, Any?>>): List<Map<String, Any?>> = files.map { file ->
        val matches = threads.flatMap { ResearchWorkflow.maps(it["posts"]) }.filter { post ->
            ResearchWorkflow.maps(post["provenance"]).any { it["source_page_id"] == file["source_page_id"] } &&
                ResearchWorkflow.maps(post["attachments"]).any { comparable(it["target_url"]) != null && comparable(it["target_url"]) == comparable(file["target_url"]) }
        }
        file + mapOf("source_post_id" to matches.singleOrNull()?.get("source_post_id"),
            "source_post_observation_ids" to matches.map { it["post_id"] }.distinct())
    }

    private fun identifier(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    private fun comparable(value: Any?): String? = try {
        val uri = URI(value as String)
        require(uri.rawUserInfo == null)
        ResearchWorkflow.canonical("${uri.scheme.lowercase()}://${uri.host.lowercase()}" +
            (if (uri.port == -1) "" else ":${uri.port}") + uri.rawPath + uri.rawQuery?.let { "?$it" }.orEmpty())
    } catch (_: Exception) { null }
    private fun semantics(post: Map<String, Any?>): List<Any?> {
        fun records(value: Any?) = (value as? Iterable<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        return listOf(post["author"], post["posted_at"], post["text"], post["quoted_text"], post["quotes"],
            records(post["referenced_links"]).map { listOf(it["target_url"], it["text"], it["quoted"], it["download"], it["declared_content_type"]) },
            records(post["attachments"]).map { listOf(it["target_url"], it["filename"], it["extension"], it["declared_content_type"], it["quoted"]) })
    }
}
