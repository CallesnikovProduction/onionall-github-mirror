package dev.onionall.brawler.research

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import dev.onionall.brawler.tor.TorProtocol
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import org.apache.storm.Config
import org.apache.stormcrawler.Metadata

/**
 * Явная разрешённая provider-операция поверх существующего TorProtocol.
 * Robots обычного crawler не меняются; targets результатов здесь не запрашиваются.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
class ResearchTorTransport(runtime: Path) : ProviderTransport, Closeable {
    private val protocol = TorProtocol()
    init {
        require(Files.isRegularFile(runtime, NOFOLLOW_LINKS) && Files.size(runtime) <= 32768) {
            "Require regular runtime descriptor <=32KiB"
        }
        val values = ObjectMapper().readValue(Files.readAllBytes(runtime),
            object : TypeReference<Map<String, Any?>>() {})
        val host = values["socks_host"] as? String ?: throw IOException("Missing SOCKS endpoint")
        val port = values["socks_port"] as? Number ?: throw IOException("Missing SOCKS port")
        require(host.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) && !host.endsWith(".onion", true))
        require(port.toDouble() == port.toInt().toDouble() && port.toInt() in 1..65535)
        val config = Config()
        config.putAll(mapOf("tor.mode" to "ONLY", "tor.socks.host" to host,
            "tor.socks.port" to port.toInt(), "tor.allowed.hosts" to listOf(AhmiaDiscoveryProvider.HOST),
            "protocol.md.prefix" to "protocol.", "http.agent.name" to "OnionallResearch",
            "http.timeout" to 30000, "http.content.limit" to 1048576,
            "http.robots.file.skip" to false, "http.allow.redirects" to false,
            "http.trust.everything" to false, "tor.discovery.html.only" to true))
        try { protocol.configure(config) } catch (failure: Exception) { protocol.cleanup(); throw failure }
    }
    override fun get(url: String): ProviderResponse {
        val response = protocol.getProtocolOutput(url, Metadata())
        return ProviderResponse(response.statusCode, response.metadata.getFirstValue("content-type"),
            response.content, url)
    }
    override fun close() = protocol.cleanup()
}
