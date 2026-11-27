package dev.onionall.brawler.research

/**
 * Infers a bounded route from persisted same-job link observations.
 *
 * A resolved target was archived by the same job. The graph does not establish
 * which links the crawler actually followed or the order it fetched pages.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
internal object StoredResearchRoute {
    private const val MAX_ROWS = 100
    private val crawlerDispositions = setOf("RESERVED", "ALREADY_RESERVED")
    private const val MANUAL_LINK = "MANUAL_UNSCHEDULED"

    private data class Edge(
        val from: String,
        val to: String,
        val snapshot: String,
        val ordinal: Int,
        val row: Map<String, Any?>
    )

    /** Returns original edge observations and latest page provenance, with any bounds exposed. */
    @JvmStatic
    fun find(
        jobId: String,
        fromPageId: String,
        toPageId: String,
        maxHops: Int,
        pages: Map<String, Any?>,
        graph: Map<String, Any?>
    ): Map<String, Any?> {
        require(maxHops in 0..MAX_ROWS) { "Route hop limit must be between 0 and 100" }
        require(graph["job_id"] == jobId) { "Archive graph belongs to a different job" }
        val pageLimit = readLimit(pages)
        val edgeLimit = readLimit(graph)
        val pageRows = rows(pages, "pages", pageLimit)
        val edgeRows = rows(graph, "links", edgeLimit)
        val byId = pageRows.filter { it["job_id"] == jobId }.mapNotNull { page ->
            text(page, "page_id")?.let { it to page }
        }.toMap()
        val endpointsObserved = fromPageId in byId && toPageId in byId
        val pageCapReached = pageRows.size == pageLimit
        val edgeCapReached = edgeRows.size == edgeLimit

        val depths = mutableMapOf<String, Int>()
        val incoming = mutableMapOf<String, Edge>()
        var hopLimitReached = false
        if (endpointsObserved) {
            val adjacent = edgeRows.mapNotNull { edge(it, jobId, byId) }
                .sortedWith(compareBy<Edge>(
                    { text(it.row, "target_url") },
                    { it.snapshot != byId.getValue(it.from)["snapshot_id"] },
                    { it.snapshot },
                    { it.ordinal }
                ))
                .groupBy { it.from }

            val queue = ArrayDeque<String>()
            depths[fromPageId] = 0
            queue.addLast(fromPageId)
            while (queue.isNotEmpty() && toPageId !in depths) {
                val from = queue.removeFirst()
                val depth = depths.getValue(from)
                val outgoing = adjacent[from].orEmpty()
                if (depth == maxHops) {
                    if (outgoing.any { it.to !in depths }) hopLimitReached = true
                    continue
                }
                for (edge in outgoing) {
                    if (edge.to in depths) continue
                    depths[edge.to] = depth + 1
                    incoming[edge.to] = edge
                    queue.addLast(edge.to)
                }
            }
        }

        val route = if (toPageId in depths) {
            buildList {
                var cursor = toPageId
                while (cursor != fromPageId) {
                    val edge = incoming.getValue(cursor)
                    add(edge)
                    cursor = edge.from
                }
            }.asReversed()
        } else null
        val pageIds = if (route == null) emptyList() else listOf(fromPageId) + route.map { it.to }
        val evidence = pageIds.map { byId.getValue(it) }

        val status = when {
            pageCapReached || edgeCapReached || hopLimitReached -> "LIMITED"
            route != null -> "FOUND"
            !endpointsObserved -> "ENDPOINT_NOT_OBSERVED"
            else -> "NO_ROUTE_IN_STORED_GRAPH"
        }
        return mapOf(
            "job_id" to jobId,
            "from_page_id" to fromPageId,
            "to_page_id" to toPageId,
            "status" to status,
            "route_basis" to "SAME_JOB_ARCHIVE_LINK_OBSERVATIONS",
            "path_optimality" to (if (route == null) null else "SHORTEST_IN_LOADED_GRAPH"),
            "hop_count" to route?.size,
            "page_ids" to pageIds,
            "page_evidence" to evidence.map { page ->
                page.filterKeys { it in pageEvidenceFields }
            },
            "path" to route.orEmpty().map { it.row },
            "provenance" to route?.let { provenance(evidence, it, byId) },
            "limits" to mapOf(
                "max_hops" to maxHops,
                "page_rows" to pageRows.size,
                "page_limit" to pageLimit,
                "edge_rows" to edgeRows.size,
                "edge_limit" to edgeLimit,
                "page_cap_reached" to pageCapReached,
                "edge_cap_reached" to edgeCapReached,
                "hop_limit_reached" to hopLimitReached
            ),
            "network_requests" to 0,
            "traversal_verified" to false
        )
    }

    private val pageEvidenceFields = setOf(
        "page_id", "canonical_url", "snapshot_id", "source_kind", "fetched_at", "http_status", "content_hash"
    )

    private fun edge(
        row: Map<String, Any?>,
        jobId: String,
        pages: Map<String, Map<String, Any?>>
    ): Edge? {
        if (row["job_id"] != jobId) return null
        val disposition = text(row, "disposition")
        if (disposition !in crawlerDispositions && disposition != MANUAL_LINK) return null
        val from = text(row, "source_page_id") ?: return null
        val to = text(row, "target_page_id") ?: return null
        val source = pages[from] ?: return null
        val target = pages[to] ?: return null
        val sourceUrl = text(source, "canonical_url") ?: return null
        val targetUrl = text(target, "canonical_url") ?: return null
        if (row["source_url"] != sourceUrl || row["target_url"] != targetUrl) return null

        val snapshot = text(row, "snapshot_id") ?: return null
        val ordinal = (row["ordinal"] as? Int)?.takeIf { it >= 0 } ?: return null
        if (text(row, "discovered_at") == null || text(row, "discovery_time_basis") == null) return null
        return Edge(from, to, snapshot, ordinal, row)
    }

    private fun provenance(
        evidence: List<Map<String, Any?>>,
        route: List<Edge>,
        pages: Map<String, Map<String, Any?>>
    ): String {
        if (route.any { it.snapshot != pages.getValue(it.from)["snapshot_id"] }) return "MIXED_OR_HISTORICAL"
        if (evidence.all { it["source_kind"] == "MANUAL_IMPORT" } &&
            route.all { it.row["disposition"] == MANUAL_LINK }) return "MANUAL_IMPORT"
        if (evidence.all {
                it["source_kind"] == "CRAWLER" && text(it, "fetched_at") != null &&
                    ((it["http_status"] as? Int) ?: 0) in 200..299
            } && route.all { text(it.row, "disposition") in crawlerDispositions }) return "CRAWLER"
        return "MIXED_OR_HISTORICAL"
    }

    private fun text(row: Map<String, Any?>, key: String): String? =
        (row[key] as? String)?.takeIf { it.isNotBlank() }

    private fun readLimit(document: Map<String, Any?>): Int =
        (document["limit"] as? Int)?.takeIf { it in 1..MAX_ROWS }
            ?: throw IllegalArgumentException("Invalid archive read limit")

    @Suppress("UNCHECKED_CAST")
    private fun rows(document: Map<String, Any?>, key: String, limit: Int): List<Map<String, Any?>> {
        val rows = document[key] as? List<*> ?: throw IllegalArgumentException("Missing archive rows")
        require(rows.size <= limit && rows.all { it is Map<*, *> }) { "Invalid bounded archive rows" }
        return rows as List<Map<String, Any?>>
    }
}
