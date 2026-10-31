package dev.onionall.brawler.tor

import crawlercommons.robots.BaseRobotRules
import crawlercommons.robots.SimpleRobotRulesParser
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.storm.Config
import org.apache.stormcrawler.Metadata
import org.apache.stormcrawler.protocol.Protocol
import org.apache.stormcrawler.protocol.ProtocolResponse
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit

/** Fixed SOCKS only. No dynamic proxy manager, system ProxySelector or trust-all TLS. */
class TorProtocol : Protocol {
    private lateinit var policy: TorPolicy
    private lateinit var client: OkHttpClient
    private lateinit var agent: String
    private var limit=65536
    private val robots=LinkedHashMap<String, Pair<Long, BaseRobotRules>>()
    override fun configure(conf: Config) {
        check(!this::client.isInitialized) { "Protocol already configured" }
        policy=TorPolicy(conf)
        val timeout=if (conf.containsKey("http.timeout")) conf["http.timeout"].toString().toLongOrNull() ?: error("Invalid timeout") else 10000
        limit=if (conf.containsKey("http.content.limit")) conf["http.content.limit"].toString().toIntOrNull() ?: error("Invalid content limit") else 65536
        require(timeout in 1..120000 && limit in 1..1048576) { "Invalid bounded timeout/content limit" }
        require(conf["http.robots.file.skip"] != true && conf["http.allow.redirects"] != true && conf["http.trust.everything"] != true) { "Unsafe HTTP configuration" }
        agent=conf["http.agent.name"] as? String ?: error("Missing http.agent.name")
        require(agent.matches(Regex("[A-Za-z_-]+"))) { "Invalid crawler agent" }
        // Resolve only the gateway. OkHttp's SOCKS route uses createUnresolved for destinations.
        val endpoint=InetSocketAddress(policy.proxyHost, policy.proxyPort)
        require(!endpoint.isUnresolved) { "Invalid SOCKS endpoint" }
        client=OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS, endpoint))
            .dns(Dns { throw UnknownHostException("LOCAL_DNS_FORBIDDEN") })
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(timeout,TimeUnit.MILLISECONDS).readTimeout(timeout,TimeUnit.MILLISECONDS)
            .writeTimeout(timeout,TimeUnit.MILLISECONDS).callTimeout(timeout,TimeUnit.MILLISECONDS).build()
    }
    override fun getProtocolOutput(url: String, metadata: Metadata): ProtocolResponse {
        policy.checkMetadata(metadata)
        val safe=policy.normalize(url)
        try {
            client.newCall(Request.Builder().url(safe).header("User-Agent",agent).build()).execute().use { response ->
                val md=Metadata()
                response.headers.forEach { (name,value) -> md.addValue(name.lowercase(),value) }
                val location=response.header("Location")
                if (response.code in 300..399 && location != null) {
                    try { policy.normalize(URI(safe).resolve(location).toString()) }
                    catch (e: IllegalArgumentException) { throw IOException("SCOPE_REJECTED", e) }
                }
                md.setValue("tor.category", if (response.code>=400) "HTTP_ERROR" else if(response.code in 300..399) "REDIRECT" else "HTTP_OK")
                val content=response.body?.byteStream()?.readNBytes(limit+1) ?: byteArrayOf()
                if (content.size>limit) throw IOException("BODY_LIMIT_EXCEEDED")
                return ProtocolResponse(content,response.code,md)
            }
        } catch (e: SocketTimeoutException) { throw IOException("REQUEST_TIMEOUT", e)
        } catch (e: java.io.InterruptedIOException) { throw IOException("REQUEST_TIMEOUT", e)
        } catch (e: ConnectException) { throw IOException("SOCKS_FAILURE", e)
        } catch (e: IOException) {
            if (e.message in setOf("SCOPE_REJECTED","BODY_LIMIT_EXCEEDED")) throw e
            throw IOException("ONION_UNREACHABLE",e)
        }
    }
    @Synchronized override fun getRobotRules(url: String): BaseRobotRules {
        val safe=URI(policy.normalize(url)); val base="${safe.scheme}://${safe.rawAuthority}"
        robots[base]?.let { if(System.nanoTime()-it.first < TimeUnit.MINUTES.toNanos(10)) return it.second }
        val response=try { getProtocolOutput("$base/robots.txt",Metadata()) }
            catch (e: IOException) { throw IOException("ROBOTS_UNAVAILABLE",e) }
        // Missing, redirected or failed robots is not permission to crawl.
        if(response.statusCode != 200) throw IOException("ROBOTS_UNAVAILABLE")
        val rules=SimpleRobotRulesParser().parseContent("$base/robots.txt",response.content,"text/plain",agent)
        if(robots.size>=16) robots.remove(robots.keys.first())
        robots[base]=Pair(System.nanoTime(),rules)
        return rules
    }
    override fun cleanup() {
        if(this::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        synchronized(this) { robots.clear() }
    }
}
