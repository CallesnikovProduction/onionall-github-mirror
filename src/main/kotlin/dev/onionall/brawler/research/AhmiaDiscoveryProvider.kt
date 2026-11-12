package dev.onionall.brawler.research

import dev.onionall.brawler.tor.TorPolicy
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Обычная разрешённая GET-форма Ahmia: homepage → q + полученные hidden fields → HTML results.
 * Hidden token не вычисляется, JS/CAPTCHA не исполняются. Ссылки результатов не посещаются.
 *
 * Owner grant относится только к этому provider operation, не отключает robots в crawler.
 * Проверенный интерфейс отдаёт одну HTML-страницу; не угадываем неподтверждённые page/API параметры.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
class AhmiaDiscoveryProvider @JvmOverloads constructor(
    private val transport: ProviderTransport,
    private val clock: Clock = Clock.systemUTC()
) : DiscoveryProvider {
    companion object {
        const val HOST = "juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion"
        const val DEFAULT_ORIGIN = "http://$HOST/"
        private const val MAX_RESPONSE_BYTES = 1048576
    }

    @Throws(IOException::class)
    override fun discover(query: String, limit: Int): DiscoveryBatch {
        require(query.length <= 256 && query.none { it.isISOControl() && !it.isWhitespace() }) {
            "Require a bounded plain-text query"
        }
        val normalized = query.trim().replace(Regex("(?U)\\s+"), " ")
        // Ограничения настоящего TorResultsView: 100 символов и максимум 10 слов.
        require(normalized.isNotEmpty() && normalized.length <= 100 &&
            normalized.split(Regex("(?U)[+\\s]+")).count { it.isNotEmpty() } <= 10) {
            "Ahmia requires a nonblank query up to 100 characters and 10 words"
        }
        require(limit in 1..20) { "Discovery limit must be 1..20" }
        val homepage = fetch(DEFAULT_ORIGIN)
        val home = document(homepage)
        val form = home.selectFirst("form#searchForm") ?: throw IOException("PROVIDER_SEARCH_FORM_MISSING")
        val action = try { URI(DEFAULT_ORIGIN).resolve(form.attr("action")) } catch (_: Exception) {
            throw IOException("PROVIDER_FORM_REJECTED")
        }
        if (!sameProvider(action) || action.rawPath != "/search/" || action.rawQuery != null ||
            action.rawFragment != null || !form.attr("method").equals("get", true)) {
            throw IOException("PROVIDER_FORM_REJECTED")
        }
        if (form.select("input[name=q]").size != 1) throw IOException("PROVIDER_FORM_REJECTED")
        val fields = form.select("input[type=hidden]")
        if (fields.isEmpty() || fields.size > 8) throw IOException("PROVIDER_FORM_REJECTED")
        val names = HashSet<String>()
        val parameters = mutableListOf("q=" + encode(normalized))
        for (field in fields) {
            val name = field.attr("name")
            val value = field.attr("value")
            if (!name.matches(Regex("[A-Za-z0-9_-]{1,64}")) || name == "q" || !names.add(name) ||
                value.isEmpty() || value.length > 256 || value.any { it.isISOControl() }) {
                throw IOException("PROVIDER_FORM_REJECTED")
            }
            parameters.add(encode(name) + "=" + encode(value))
        }
        val response = fetch(action.toASCIIString() + "?" + parameters.joinToString("&"))
        val results = document(response)
        if (results.selectFirst("#ahmiaResultsPage") == null ||
            (results.selectFirst("ol.searchResults") == null && results.selectFirst("#noResults") == null)) {
            throw IOException("PROVIDER_RESPONSE_NOT_SEARCH_RESULTS")
        }
        val anchors = results.select("ol.searchResults li.result h4 a[href]")
        if (anchors.size > 5000) throw IOException("PROVIDER_RESULT_LIMIT_EXCEEDED")
        val now = Instant.now(clock).toString()
        val all = linkedMapOf<String, DiscoveryCandidate>()
        val rejected = mutableListOf<Map<String, Any?>>()
        for (anchor in anchors) {
            interrupted()
            val original = anchor.attr("href")
            val canonical = try { canonical(unwrap(original)) } catch (_: IOException) {
                rejected.add(mapOf("original_result_url" to original.take(8192),
                    "validation_status" to "INVALID_URL", "discovered_at" to now))
                continue
            }
            val previous = all[canonical]
            if (previous != null) {
                all[canonical] = previous.copy(provenance = (previous.provenance + original).distinct())
                rejected.add(mapOf("original_result_url" to original,
                    "canonical_url" to canonical, "validation_status" to "DUPLICATE", "discovered_at" to now))
                continue
            }
            val host = URI(canonical).host
            all[canonical] = DiscoveryCandidate(UUID.randomUUID().toString(), original, canonical, host,
                anchor.text().trim().take(2048).ifEmpty { null },
                anchor.closest("li.result")?.selectFirst("p")?.text()?.trim()?.take(4096)?.ifEmpty { null },
                now, "VALID", host, listOf(original))
        }
        return DiscoveryBatch("ahmia", query, normalized,
            // Не сохраняем временные hidden-поля, полученные из публичной формы.
            action.toASCIIString() + "?q=" + encode(normalized), now, anchors.size,
            all.values.take(limit), rejected, 2,
            if (all.size > limit) "SINGLE_PAGE_LIMIT_REACHED" else
                if (results.select("a[rel~=next]").isNotEmpty()) "PAGINATION_NOT_SUPPORTED" else "SINGLE_PAGE",
            all.size)
    }

    private fun fetch(url: String): ProviderResponse {
        interrupted()
        val response = transport.get(url)
        interrupted()
        val actual = try { URI(response.responseUrl) } catch (_: Exception) {
            throw IOException("PROVIDER_INVALID_RESPONSE_URL")
        }
        if (!sameProvider(actual) || actual != URI(url)) throw IOException("PROVIDER_REDIRECT_REJECTED")
        if (response.status != 200) throw IOException("PROVIDER_HTTP_${response.status}")
        if (response.body.isEmpty() || response.body.size > MAX_RESPONSE_BYTES) throw IOException("PROVIDER_BODY_LIMIT")
        if (response.contentType?.substringBefore(';')?.trim()?.lowercase() != "text/html") {
            throw IOException("PROVIDER_UNSUPPORTED_CONTENT_TYPE")
        }
        return response
    }

    private fun document(response: ProviderResponse): Document =
        Jsoup.parse(ByteArrayInputStream(response.body), null, response.responseUrl)

    private fun sameProvider(uri: URI): Boolean = uri.scheme == "http" && uri.host == HOST &&
        uri.rawUserInfo == null && uri.port == -1 && uri.rawAuthority == HOST

    private fun unwrap(value: String): String {
        val raw = value.trim()
        if (raw.length > 8192 || raw.any { it.isISOControl() }) throw IOException("INVALID_RESULT_URL")
        val question = raw.indexOf('?')
        if (question >= 0) {
            val route = try { URI(DEFAULT_ORIGIN).resolve(raw.substring(0, question)) } catch (_: Exception) { null }
            if (route != null && sameProvider(route) && route.rawPath == "/search/redirect" &&
                route.rawFragment == null) {
                val query = raw.substring(question + 1)
                val positions = Regex("(?:^|&)redirect_url=").findAll(query).toList()
                if (positions.size != 1) throw IOException("AMBIGUOUS_RESULT_URL")
                val target = query.substring(positions.single().range.last + 1)
                // Upstream помещает redirect_url последним и не кодирует внутренний '&'.
                // Обычный parseQuery здесь теряет часть подлинного target query.
                if (target.startsWith("http://", true) || target.startsWith("https://", true)) return target
                if (Regex("(?i)^https?%3a%2f%2f").containsMatchIn(target)) {
                    return try { URLDecoder.decode(target, UTF_8) } catch (_: IllegalArgumentException) {
                        throw IOException("INVALID_RESULT_URL")
                    }
                }
                throw IOException("INVALID_RESULT_URL")
            }
        }
        return raw
    }

    private fun canonical(value: String): String {
        if (value.length > 2048) throw IOException("INVALID_RESULT_URL")
        val uri = try { URI(value) } catch (_: Exception) { throw IOException("INVALID_RESULT_URL") }
        val host = uri.host?.lowercase(Locale.ROOT) ?: throw IOException("INVALID_RESULT_URL")
        if (!TorPolicy.validOnion(host) || uri.rawUserInfo != null) throw IOException("INVALID_RESULT_URL")
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: throw IOException("INVALID_RESULT_URL")
        val authority = host + if (uri.port == -1) "" else ":${uri.port}"
        if (!uri.rawAuthority.equals(uri.host + if (uri.port == -1) "" else ":${uri.port}")) {
            throw IOException("INVALID_RESULT_URL")
        }
        val policy = TorPolicy(mapOf("tor.mode" to "ONLY", "tor.socks.host" to "127.0.0.1",
            "tor.socks.port" to 9050, "tor.allowed.hosts" to listOf(host)))
        val normalized = policy.normalize("$scheme://$authority${uri.rawPath.ifEmpty { "/" }}" +
            (uri.rawQuery?.let { "?$it" } ?: ""))
        val valid = URI(normalized)
        return "$scheme://$host${valid.rawPath}" + (valid.rawQuery?.let { "?$it" } ?: "")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, UTF_8)

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("DISCOVERY_CANCELLED")
    }
}
