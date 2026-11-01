package dev.onionall.brawler.discovery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.stormcrawler.Metadata
import org.apache.stormcrawler.parse.ParseFilter
import org.apache.stormcrawler.parse.ParseResult
import org.w3c.dom.DocumentFragment

/** Reuses parser outlinks and its existing status-stream/URLFrontier delivery. */
class DiscoveryParseFilter : ParseFilter() {
    private lateinit var planner: DiscoveryPlanner
    private val json = ObjectMapper()
    override fun configure(conf: MutableMap<String, Any>, params: JsonNode, name: String) {
        super.configure(conf, params, name)
        require(conf["discovery.budget.mode"] == "local-test") {
            "Durable global discovery reservations are unavailable; only explicit local-test mode is supported"
        }
        require((conf["discovery.crawl.session.id"] as? String)?.matches(Regex("[A-Za-z0-9_-]{1,64}")) == true) {
            "Require explicit discovery.crawl.session.id"
        }
        fun required(key: String): Int = conf[key]?.toString()?.toIntOrNull()
            ?: error("Missing M3 integer setting: $key")
        planner = DiscoveryPlanner(conf, required("discovery.max.pages"),
            required("discovery.max.depth"), required("discovery.max.links"))
    }
    override fun needsDOM() = false
    override fun filter(url: String, content: ByteArray, doc: DocumentFragment?, result: ParseResult) {
        val parent = requireNotNull(result.get(url)) { "Missing parent parse result" }.metadata
        val depth = (parent.getFirstValue("discovery.depth") ?: "0").toInt()
        val links = result.outlinks.toList()
        val edges = planner.discover(url, depth, links.map { it.targetURL })
        parent.setValues("discovery.edges", edges.map { json.writeValueAsString(it) }.toTypedArray())
        parent.setValue("discovery.links.truncated", (links.size > edges.size).toString())
        val emitted = mutableSetOf<String>()
        result.outlinks = edges.mapIndexedNotNull { i, edge ->
            if (edge.result !in setOf("ADMITTED", "DUPLICATE") || edge.target == edge.source ||
                edge.target == null || !emitted.add(edge.target)) null else links[i].also {
                val metadata = Metadata()
                it.metadata?.let { old -> metadata.putAll(old) }
                it.metadata = metadata
                it.targetURL = edge.target!!
                it.metadata.setValue("discovery.source", edge.source)
                it.metadata.setValue("discovery.at", edge.discoveredAt)
                it.metadata.setValue("discovery.depth", edge.depth.toString())
                it.metadata.setValue("discovery.result", edge.result)
            }
        }
    }
}
