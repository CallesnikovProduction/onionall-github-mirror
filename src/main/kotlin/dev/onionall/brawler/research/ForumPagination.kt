package dev.onionall.brawler.research

import java.net.URI
import dev.onionall.brawler.tor.UriReferences

/**
 * Узкая идентичность HTML pagination; не меняет общую URL canonicalization.
 * Перестановка только уникальных известных query keys; неоднозначные параметры сохраняются.
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
object ForumPagination {
    private val keys = setOf("t", "f", "id", "topic", "thread", "page", "start", "offset")
    private val counters = setOf("page", "start", "offset")
    private val pathPage = Regex("/page/[1-9][0-9]{0,5}/?$")

    @JvmStatic fun canonical(value: String): String {
        val canonical = ResearchWorkflow.canonical(value)
        val uri = URI(canonical)
        val pairs = query(uri)
        val ordered = if (pairs != null && known(pairs)) pairs.sortedBy { it.first } else null
        return origin(uri) + uri.rawPath + (ordered?.takeIf { it.isNotEmpty() }
            ?.joinToString("&", "?") { it.first + "=" + it.second }
            ?: uri.rawQuery?.let { "?$it" }.orEmpty())
    }

    @JvmStatic fun identity(value: String): String {
        val uri = URI(canonical(value))
        val pairs = query(uri)
        val known = pairs != null && known(pairs)
        val stripped = if (pathPage.containsMatchIn(uri.rawPath)) uri.rawPath.replace(pathPage, "/") else uri.rawPath
        val base = stripped.trimEnd('/').ifEmpty { "/" }
        val retained = if (known) pairs!!.filter { it.first !in counters } else null
        return origin(uri) + base + (retained?.takeIf { it.isNotEmpty() }
            ?.joinToString("&", "?") { it.first + "=" + it.second }
            ?: if (known) "" else uri.rawQuery?.let { "?$it" }.orEmpty())
    }

    @JvmStatic fun configuration(plan: Map<String, Any?>): Map<String, Any?> {
        if (plan["provider"] != "m61:thread") return emptyMap()
        val seeds = plan["seeds"] as? List<*> ?: error("Missing thread seed")
        val pages = (plan["max_pages"] as? Number)?.toInt() ?: 0
        require(seeds.size == 1 && pages in 1..5 && (plan["depth"] as? Number)?.toInt() == pages - 1) {
            "Thread requires one seed, 1..5 pages and bounded pagination hops"
        }
        val seed = canonical(seeds.single() as String)
        require(seed == seeds.single()) { "Thread seed must use canonical pagination identity" }
        return mapOf("research.thread.seed" to seed, "research.thread.max.pages" to pages)
    }

    /** Только настоящая числовая pagination того же thread; rel=next не расширяет origin/path. */
    @JvmStatic fun eligible(seed: String, source: String, target: String): String? = try {
        val normalized = canonical(target)
        val candidate = URI(normalized)
        val current = URI(canonical(source))
        val start = canonical(seed)
        val pairs = query(candidate)
        val numeric = pairs?.any { (key, value) -> key in counters &&
            value.matches(Regex("[0-9]{1,6}")) && (key != "page" || value.toInt() >= 1) } == true
        if (origin(candidate) == origin(current) && identity(normalized) == identity(start) &&
            (normalized == start || numeric || pathPage.containsMatchIn(candidate.rawPath))) normalized else null
    } catch (_: Exception) { null }

    @JvmStatic fun observations(source: String, html: ByteArray): List<Map<String, Any?>> {
        val doc = org.jsoup.Jsoup.parse(html.toString(Charsets.UTF_8), source)
        doc.select("base").remove()
        return doc.select("a[href],link[rel=next][href]").take(100).mapNotNull { anchor ->
            val raw = anchor.attr("href")
            if (raw.length > 2048) return@mapNotNull null
            val target = try { UriReferences.resolve(source, raw).toString() } catch (_: Exception) { return@mapNotNull null }
            val permitted = eligible(source, source, target) ?: return@mapNotNull null
            if (permitted == canonical(source)) return@mapNotNull null
            mapOf("source_url" to source, "target_url" to permitted, "rel" to anchor.attr("rel"),
                "evidence" to "SAME_THREAD_NUMERIC_PAGINATION_ANCHOR")
        }.distinctBy { it["target_url"] }
    }

    @JvmStatic fun position(value: String): Int {
        val uri = URI(canonical(value))
        val queryCounter = query(uri)?.firstOrNull { it.first in counters && it.second.matches(Regex("[0-9]{1,6}")) }?.second
        return queryCounter?.toInt() ?: pathPage.find(uri.rawPath)?.value?.trim('/')?.substringAfterLast('/')?.toInt() ?: 0
    }

    /** Номер страницы и offset/start имеют разные единицы; без page size их нельзя сортировать вместе. */
    @JvmStatic fun counterKinds(value: String): Set<String> {
        val uri = URI(canonical(value))
        val kinds = query(uri).orEmpty().filter { it.first in counters && it.second.matches(Regex("[0-9]{1,6}")) }
            .map { it.first }.toMutableSet()
        if (pathPage.containsMatchIn(uri.rawPath)) kinds.add("page")
        return kinds
    }

    private fun query(uri: URI): List<Pair<String, String>>? {
        val raw = uri.rawQuery ?: return emptyList()
        val pairs = raw.split('&').map { token ->
            val parts = token.split('=', limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) return null
            parts[0] to parts[1]
        }
        return pairs.takeIf { it.map { pair -> pair.first }.distinct().size == it.size }
    }
    private fun origin(uri: URI) = "${uri.scheme}://${uri.host}"
    private fun known(pairs: List<Pair<String, String>>) = pairs.all { it.first in keys && (it.first !in counters ||
        it.second.matches(Regex("[0-9]{1,6}")) && (it.first != "page" || it.second.toInt() >= 1)) }
}
