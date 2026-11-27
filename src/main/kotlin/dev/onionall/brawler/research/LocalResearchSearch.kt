package dev.onionall.brawler.research

import java.net.URI
import java.util.Locale

/**
 * Ranks documents for a local query qualified by a JVM major release.
 *
 * Text and URLs supply a bounded pool of candidates. A versioned result needs a
 * topic match in the title and consistent release evidence from the title or
 * a Java/JDK documentation path. An incidental mention in body text does not
 * identify the document's release. Evidence describes stored metadata.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
internal object LocalResearchSearch {
    private const val MAX_CANDIDATES = 100
    private const val MAX_QUERY_CHARS = 256
    private val releasePattern = Regex(
        """(?<![\p{L}\p{N}])(?:java(?:\s+se)?|jdk)\s+([1-9][0-9]{0,2})(?![\p{L}\p{N}._])""",
        RegexOption.IGNORE_CASE
    )
    private val releasePath = Regex(
        """/(?:(?:java|jdk)[-_]|(?:java|javase|jdk)/)([1-9][0-9]{0,2})(?=/|$)""",
        RegexOption.IGNORE_CASE
    )
    private val trailingKind = Regex("""\s+(?:documentation|docs|guide|reference)$""", RegexOption.IGNORE_CASE)
    private val word = Regex("""[\p{L}\p{N}]+""")

    private data class Query(val release: Int, val topic: String, val words: String)
    private data class Match(val row: Map<String, Any?>, val versionEvidence: List<String>)

    fun search(
        query: String,
        limit: Int,
        retrieve: (String, Int) -> Map<String, Any?>
    ): Map<String, Any?> {
        val request = releaseQuery(query)
        if (request == null) {
            val result = retrieve(query, limit)
            return result + metadata(rows(result).size == limit)
        }

        require(limit in 1..MAX_CANDIDATES) { "Search limit must be between 1 and 100" }
        val result = retrieve(request.topic, MAX_CANDIDATES)
        val candidates = rows(result)
        val matches = candidates.mapNotNull { match(it, request) }
            .sortedByDescending { "title" in it.versionEvidence }

        val ranked = matches.take(limit).map { match ->
            match.row + ("match" to mapOf(
                "jvm_release" to request.release,
                "version_evidence" to match.versionEvidence,
                "topic_evidence" to "title"
            ))
        }
        return result + metadata(candidates.size == MAX_CANDIDATES || matches.size > limit) + mapOf(
            "query" to query,
            "limit" to limit,
            "results" to ranked,
            "search_mode" to "jvm-release",
            "candidate_limit" to MAX_CANDIDATES,
            "examined_candidates" to candidates.size
        )
    }

    private fun releaseQuery(value: String): Query? {
        if (value.length > MAX_QUERY_CHARS) return null
        val query = value.trim()
        val releases = releasePattern.findAll(query).take(2).toList()
        if (releases.size != 1 || releases.first().range.first != 0) return null
        val marker = releases.first()
        val remainder = query.substring(marker.range.last + 1)
        if (remainder.firstOrNull()?.isWhitespace() != true) return null
        val topic = remainder.trim().replace(trailingKind, "")
        val words = normalizedWords(topic)
        if (words.isBlank()) return null
        return Query(marker.groupValues[1].toInt(), topic, words)
    }

    private fun match(row: Map<String, Any?>, request: Query): Match? {
        val title = row["title"] as? String ?: return null
        if (!(" " + normalizedWords(title) + " ").contains(" " + request.words + " ")) return null
        val fromTitle = releases(title, releasePattern)
        val path = (row["canonical_url"] as? String)?.let {
            runCatching { URI.create(it).rawPath }.getOrNull()
        }.orEmpty()
        val fromPath = releases(path, releasePath)
        if ((fromTitle + fromPath) != setOf(request.release)) return null
        return Match(row, buildList {
            if (fromTitle.isNotEmpty()) add("title")
            if (fromPath.isNotEmpty()) add("url_path")
        })
    }

    private fun releases(value: String, pattern: Regex): Set<Int> =
        pattern.findAll(value).map { it.groupValues[1].toInt() }.toSet()

    private fun normalizedWords(value: String): String =
        word.findAll(value).joinToString(" ") { it.value.lowercase(Locale.ROOT) }

    private fun metadata(truncated: Boolean): Map<String, Any> = mapOf(
        "network_requests" to 0,
        "search_fields" to listOf("url", "title", "text"),
        "may_be_truncated" to truncated
    )

    @Suppress("UNCHECKED_CAST")
    private fun rows(result: Map<String, Any?>): List<Map<String, Any?>> =
        result.getValue("results") as List<Map<String, Any?>>
}
