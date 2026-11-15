package dev.onionall.brawler.research

import dev.onionall.brawler.tor.TorPolicy
import dev.onionall.brawler.tor.UriReferences
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import org.jsoup.Jsoup

/**
 * Извлекает document-кандидаты из проверенных архивных HTML, без сетевых запросов.
 * Расширение и HTML type/download — hints, а не подтверждение MIME/размера ответа файла.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
object ResearchFiles {
    const val MAX_CANDIDATES = 1000
    private const val MAX_HTML_BYTES = 131072
    private val extensions = setOf("pdf", "epub", "txt", "md", "markdown", "docx", "odt", "rtf")
    private val media = setOf("application/pdf", "application/epub+zip", "text/plain", "text/markdown",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text", "application/rtf", "text/rtf")

    /** Общая read-only проверка настоящего content-addressed HTML для discussion extraction. */
    @JvmStatic @Throws(IOException::class)
    fun verifiedHtml(page: Map<String, Any?>, archiveRoot: Path): ByteArray {
        val root = archiveRoot.toAbsolutePath().normalize()
        noSymlinks(root)
        return body(page, root)
    }

    /**
     * loadPage читает действительный ArchiveStore page; archiveRoot задаёт оператор.
     * При несовпадении snapshot/hash завершаемся ошибкой, не выдаём пустой список за успех.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun extract(sessionId: String, pages: List<Map<String, Any?>>,
        loadPage: (String) -> Map<String, Any?>, archiveRoot: Path): List<Map<String, Any?>> {
        identifier(sessionId)
        if (pages.size > 100) throw IOException("Research page inspection limit reached")
        val root = archiveRoot.toAbsolutePath().normalize()
        noSymlinks(root)
        val candidates = LinkedHashMap<String, Map<String, Any?>>()
        for (row in pages) {
            val pageId = identifier(row["page_id"] as? String)
            val page = loadPage(pageId)
            val job = identifier(page["job_id"] as? String)
            if (page["page_id"] != pageId || page["job_id"] != row["job_id"] ||
                page["content_hash"] != row["content_hash"]) {
                throw IOException("Archive snapshot changed; inspect after the worker stops")
            }
            val source = canonical(page["canonical_url"] as? String ?: throw IOException("Missing source URL"))
            val sourceUri = URI(source)
            val html = body(page, root)
            val captured = page["fetched_at"] ?: page["recorded_at"]
            val clock = timestamp(captured)
            val kind = page["source_kind"] as? String
            if (kind !in setOf("CRAWLER", "MANUAL_IMPORT")) throw IOException("Unknown archived source kind")
            val document = Jsoup.parse(ByteArrayInputStream(html), null, source)
            val rawBase = document.selectFirst("base[href]")?.attr("href")?.trim()
            val base = try { if (rawBase == null) sourceUri else UriReferences.resolve(source, rawBase) }
                catch (_: Exception) { sourceUri }
            val links = (page["links"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
            for ((ordinal, anchor) in document.select("a[href],area[href]").withIndex()) {
                val raw = anchor.attr("href")
                // absUrl() может убрать userinfo: проверяем исходный href до нормализации.
                val reference = try { URI(raw.trim()) } catch (_: Exception) { continue }
                if (reference.rawUserInfo != null || reference.scheme?.lowercase(Locale.ROOT)?.let {
                    it !in setOf("http", "https") } == true) continue
                val target = try { canonical(UriReferences.resolve(base.toString(), reference.toString()).toString()) } catch (_: IOException) { continue }
                val targetUri = URI(target)
                val download = anchor.attr("download").takeIf { anchor.hasAttr("download") }
                val declared = anchor.attr("type").trim().takeIf { it.isNotEmpty() }?.take(256)
                val hintedMedia = declared?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                val urlName = targetUri.path.substringAfterLast('/').takeIf { it.isNotEmpty() }
                val filename = download?.takeIf { it.isNotBlank() } ?: urlName
                val extension = extension(filename) ?: extension(urlName)
                if (extension !in extensions && hintedMedia !in media) continue
                val sameHost = targetUri.host == sourceUri.host
                val sameOrigin = sameHost && targetUri.scheme == sourceUri.scheme
                val edge = links.firstOrNull { it["target_url"] == target }
                val discovered = edge?.get("discovered_at")?.let(::timestamp) ?: clock
                val id = digest("$sessionId|$job|$pageId|$target".toByteArray(Charsets.UTF_8))
                val evidence = linkedMapOf<String, Any?>("snapshot_id" to page["snapshot_id"],
                    "anchor_ordinal" to ordinal, "href" to raw.take(2048),
                    "download_attribute" to download?.take(1024), "declared_content_type" to declared,
                    "captured_at" to clock)
                val old = candidates[id]
                if (old != null) {
                    @Suppress("UNCHECKED_CAST")
                    val observations = old["provenance"] as List<Map<String, Any?>>
                    candidates[id] = old + mapOf("provenance" to (observations + evidence).take(100))
                    continue
                }
                if (candidates.size == MAX_CANDIDATES) return candidates.values.toList()
                candidates[id] = linkedMapOf("file_id" to id, "session_id" to sessionId,
                    "job_id" to job, "source_page_id" to pageId, "source_url" to source,
                    "target_url" to target, "original_filename" to filename?.take(1024),
                    "filename_source" to if (!download.isNullOrBlank()) "LINK_DOWNLOAD_ATTRIBUTE" else "URL_PATH_HINT",
                    "extension" to extension, "format_hint_source" to
                        if (extension in extensions) "LINK_FILENAME_OR_URL" else "LINK_TYPE_ATTRIBUTE",
                    "content_type" to null, "content_length" to null,
                    "declared_content_type" to declared, "content_metadata_verified" to false,
                    "discovered_at" to discovered,
                    "discovery_time_basis" to (edge?.get("discovery_time_basis") ?:
                        if (page["fetched_at"] != null) "SNAPSHOT_FETCHED_AT" else "SNAPSHOT_RECORDED_AT"),
                    "source_kind" to kind, "status" to if (sameOrigin) "CANDIDATE" else "OUT_OF_SCOPE",
                    "approved_host" to sameHost, "scope_status" to if (sameOrigin) "SOURCE_ORIGIN" else "OUT_OF_SCOPE",
                    "downloaded" to false, "provenance" to listOf(evidence))
            }
        }
        return candidates.values.toList()
    }

    private fun body(page: Map<String, Any?>, root: Path): ByteArray {
        val hash = page["content_hash"] as? String ?: throw IOException("Missing archive hash")
        if (!hash.matches(Regex("[a-f0-9]{64}"))) throw IOException("Invalid archive hash")
        val expected = root.resolve("objects").resolve("$hash.html")
        val location = page["storage_location"] as? String ?: throw IOException("Missing archive object")
        val actual = try { Path.of(location).toAbsolutePath() } catch (_: Exception) {
            throw IOException("Invalid archive object path")
        }
        if (actual != expected) throw IOException("Archive object outside expected content-addressed location")
        noSymlinks(actual)
        if (!Files.isRegularFile(actual, NOFOLLOW_LINKS)) throw IOException("Archive object is not a regular file")
        val size = Files.size(actual)
        if (size !in 1L..MAX_HTML_BYTES.toLong() || (page["content_size"] as? Number)?.toLong() != size) {
            throw IOException("Archive object size mismatch")
        }
        val bytes = Files.newInputStream(actual, READ, NOFOLLOW_LINKS).use { it.readNBytes(MAX_HTML_BYTES + 1) }
        if (bytes.size.toLong() != size || digest(bytes) != hash) throw IOException("Archive object hash mismatch")
        return bytes
    }

    private fun extension(filename: String?): String? = filename?.substringAfterLast('/')
        ?.substringAfterLast('\\')?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private fun canonical(value: String): String {
        if (value.length !in 1..2048) throw IOException("Invalid document target")
        val uri = try { URI(value) } catch (_: Exception) { throw IOException("Invalid document target") }
        val host = uri.host?.lowercase(Locale.ROOT) ?: throw IOException("Invalid document target")
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: throw IOException("Invalid document target")
        if (!TorPolicy.validOnion(host) || uri.rawUserInfo != null ||
            uri.rawAuthority != uri.host + (if (uri.port == -1) "" else ":${uri.port}")) {
            throw IOException("Invalid document target")
        }
        val policy = TorPolicy(mapOf("tor.mode" to "ONLY", "tor.socks.host" to "127.0.0.1",
            "tor.socks.port" to 9050, "tor.allowed.hosts" to listOf(host)))
        val normalized = policy.normalize("$scheme://$host" + (if (uri.port == -1) "" else ":${uri.port}") +
            uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: ""))
        val result = URI(normalized)
        return "$scheme://$host${result.rawPath}" + (result.rawQuery?.let { "?$it" } ?: "")
    }

    private fun identifier(value: String?): String {
        if (value == null || try { UUID.fromString(value).toString() != value } catch (_: Exception) { true }) {
            throw IOException("Invalid research identifier")
        }
        return value
    }

    private fun timestamp(value: Any?): String {
        if (value !is String || value.length > 40) throw IOException("Missing archive capture timestamp")
        try { Instant.parse(value) } catch (_: Exception) { throw IOException("Invalid archive capture timestamp") }
        return value
    }

    private fun noSymlinks(path: Path) {
        var current: Path? = path
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw IOException("Archive paths must not contain symbolic links")
            current = current.parent
        }
    }

    private fun digest(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
