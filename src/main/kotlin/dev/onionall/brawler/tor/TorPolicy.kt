package dev.onionall.brawler.tor

import org.apache.stormcrawler.Metadata
import java.net.URI
import java.security.MessageDigest
import java.io.IOException

/** Scope validation never asks a resolver about a destination. */
class TorPolicy(config: Map<String, Any>) {
    val proxyHost = config["tor.socks.host"] as? String ?: error("Missing tor.socks.host")
    val proxyPort = config["tor.socks.port"]?.toString()?.toIntOrNull() ?: error("Missing tor.socks.port")
    private val hosts = (config["tor.allowed.hosts"] as? List<*>)?.map {
        require(it is String && validOnion(it)) { "Invalid approved onion hostname" }; it
    }?.toSet() ?: error("Missing tor.allowed.hosts")
    init {
        require(config["tor.mode"] == "ONLY") { "tor.mode must be ONLY" }
        require(proxyHost.matches(Regex("[a-zA-Z0-9.-]+")) && proxyHost.isNotBlank() && !proxyHost.trimEnd('.').lowercase().endsWith(".onion")) { "Invalid SOCKS host" }
        require(proxyPort in 1..65535) { "Invalid SOCKS port" }
        require(hosts.isNotEmpty() && hosts.size <= 16) { "Require 1..16 approved hosts" }
    }
    fun normalize(url: String): String {
        val u = try { URI(url) } catch (_: Exception) { throw IOException("SCOPE_REJECTED") }
        if (u.scheme !in setOf("http", "https") || u.rawUserInfo != null || u.host !in hosts ||
            !validOnion(u.host ?: "") || u.port !in setOf(-1, if (u.scheme == "http") 80 else 443) ||
            u.rawAuthority != u.host + (if (u.port == -1) "" else ":${u.port}")) throw IOException("SCOPE_REJECTED")
        val rawPath = u.rawPath.ifEmpty { "/" }
        val rawQuery = u.rawQuery?.let { "?$it" } ?: ""
        return URI("${u.scheme}://${u.rawAuthority}$rawPath$rawQuery").normalize().toASCIIString()
    }
    fun checkMetadata(metadata: Metadata) {
        if (metadata.keySet().any { val k=it.lowercase(); "proxy" in k || k.startsWith("http.") || "set-header" in k })
            throw IOException("PROXY_POLICY_VIOLATION")
    }
    companion object {
        @JvmStatic fun validOnion(host: String): Boolean {
            if (!host.matches(Regex("[a-z2-7]{56}\\.onion"))) return false
            val alphabet="abcdefghijklmnopqrstuvwxyz234567"; var bits=0; var value=0
            val bytes=ArrayList<Byte>()
            for (c in host.take(56)) { value=(value shl 5) or alphabet.indexOf(c); bits+=5
                if (bits>=8) { bits-=8; bytes.add((value shr bits).toByte()) } }
            val raw=bytes.toByteArray()
            if (raw[34] != 3.toByte()) return false
            val checksum=MessageDigest.getInstance("SHA3-256").digest(".onion checksum".toByteArray(Charsets.US_ASCII)+raw.copyOfRange(0,32)+byteArrayOf(3))
            return raw[32]==checksum[0] && raw[33]==checksum[1]
        }
    }
}
