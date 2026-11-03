package dev.onionall.brawler.discovery

import dev.onionall.brawler.tor.TorPolicy
import java.net.URI
import java.io.IOException
import java.time.Instant

/** One bounded discovery session, owned by one parser instance. No network I/O. */
class DiscoveryPlanner(config: Map<String, Any>, private val maxPages: Int,
                       private val maxDepth: Int, private val maxLinks: Int) {
    private val policy = TorPolicy(config)
    private val admitted = linkedSetOf<String>()
    init {
        require(maxPages in 1..10000 && maxDepth in 0..100 && maxLinks in 1..1000)
    }
    data class Edge(val source: String, val target: String?, val discoveredAt: String,
                    val depth: Int, val result: String)

    @Synchronized
    fun discover(source: String, depth: Int, references: List<String>): List<Edge> {
        require(depth in 0..100)
        val base = policy.normalize(source)
        if (base !in admitted) {
            check(admitted.size < maxPages) { "Source outside session admission budget" }
            admitted.add(base)
        }
        val now = Instant.now().toString()
        return references.take(maxLinks).map { reference ->
            val target = try {
                if (reference.length > 2048) throw IOException("URL_LIMIT")
                policy.normalize(URI(base).resolve(reference).toString())
            } catch (_: IOException) { null }
              catch (_: IllegalArgumentException) { null }
            val result = when {
                target == null -> "REJECTED"
                metadataOnly(target) -> "METADATA_ONLY"
                target in admitted -> "DUPLICATE"
                depth >= maxDepth -> "DEPTH_LIMIT"
                admitted.size >= maxPages -> "PAGE_LIMIT"
                else -> { admitted.add(target); "ADMITTED" }
            }
            Edge(base, target, now, depth + 1, result)
        }
    }
    companion object {
    @JvmStatic fun metadataOnly(url: String): Boolean {
        val path = URI(url).path.lowercase()
        // Conservative extension gate; extensionless binary responses need a
        // separate pre-body MIME contract before live M3 can be accepted.
        val leaf = path.substringAfterLast('/')
        return '.' in leaf && !leaf.endsWith(".html") && !leaf.endsWith(".htm")
    }
}
}
