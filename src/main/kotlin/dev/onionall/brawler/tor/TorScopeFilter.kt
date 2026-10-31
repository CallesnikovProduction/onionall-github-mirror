package dev.onionall.brawler.tor

import com.fasterxml.jackson.databind.JsonNode
import org.apache.stormcrawler.Metadata
import org.apache.stormcrawler.filtering.URLFilter
import java.net.URL

class TorScopeFilter : URLFilter() {
    private lateinit var policy: TorPolicy
    override fun configure(stormConf: MutableMap<String, Any>, filterParams: JsonNode, name: String) {
        super.configure(stormConf,filterParams,name)
        policy=TorPolicy(stormConf)
    }
    override fun filter(source: URL?, metadata: Metadata?, target: String): String? =
        try { metadata?.let { policy.checkMetadata(it) }; policy.normalize(target) } catch (_: java.io.IOException) { null }
}
