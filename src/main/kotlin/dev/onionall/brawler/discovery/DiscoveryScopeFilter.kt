package dev.onionall.brawler.discovery

import com.fasterxml.jackson.databind.JsonNode
import dev.onionall.brawler.tor.TorPolicy
import org.apache.stormcrawler.Metadata
import org.apache.stormcrawler.filtering.URLFilter
import java.io.IOException
import java.net.URL
import java.time.Instant

/** StatusEmitterBolt passes parent metadata before outlink metadata transfer. */
class DiscoveryScopeFilter : URLFilter() {
    private lateinit var policy: TorPolicy
    override fun configure(conf: MutableMap<String, Any>, params: JsonNode, name: String) {
        super.configure(conf, params, name)
        policy = TorPolicy(conf)
    }
    override fun filter(source: URL?, metadata: Metadata?, target: String): String? = try {
        metadata?.let { policy.checkMetadata(it) }
        policy.normalize(target)
    } catch (_: IOException) {
        metadata?.let {
            val count = it.getFirstValue("discovery.scope.rejected")?.toIntOrNull() ?: 0
            it.setValue("discovery.scope.rejected", (count.coerceIn(0, 1000) + 1).coerceAtMost(1000).toString())
            it.setValue("discovery.scope.rejected.at", Instant.now().toString())
        }
        null
    }
}
