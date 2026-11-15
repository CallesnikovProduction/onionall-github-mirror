package dev.onionall.brawler.archive

import java.io.IOException
import java.net.URI
import dev.onionall.brawler.tor.UriReferences
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * <b>Lossless UTF-8 document decoding и безопасная research projection.</b>
 *
 * Raw bytes сохраняются persistence отдельно. Формат определяется только явным
 * поддерживаемым media type: расширение URL не превращает plain text в Markdown.
 * Link extraction консервативна; source_text остаётся полным источником истины.
 * Здесь нет HTML parser, сетевого доступа или выполнения document content.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
object ArchiveTextDocuments {
    private val inlineLink = Regex("!?\\[([^]\\n]*)]\\(\\s*(<[^>\\n]+>|[^\\s)]+)(?:\\s+\"[^\"\\n]*\")?\\s*\\)")
    private val reference = Regex("(?m)^ {0,3}\\[([^]\\n]+)]:[ \\t]*(<[^>\\n]+>|[^\\s]+)")
    private val autoLink = Regex("<(https?://[^>\\s]+)>")

    /**
     * Проверяет size, media type, UTF-8 и отсутствие binary controls.
     * @param url canonical source URL, используется только для разрешения ссылок
     * @param contentType явный text/markdown или text/plain с UTF-8 charset
     * @param bytes исходные bounded bytes, не изменяются
     * @return versioned lossless projection; ссылки не являются admission решениями
     * @throws IOException при неподдерживаемом формате, charset или binary/invalid UTF-8
     */
    @JvmStatic @Throws(IOException::class)
    fun decode(url: String, contentType: String?, bytes: ByteArray): Map<String, Any?> {
        if (bytes.isEmpty() || bytes.size > 131072) throw IOException("Require bounded text document")
        val parts = contentType?.split(';') ?: throw IOException("Require explicit document Content-Type")
        val type = parts.first().trim().lowercase(Locale.ROOT)
        val format = when (type) {
            "text/markdown" -> "MARKDOWN"
            "text/plain" -> "PLAIN_TEXT"
            else -> throw IOException("Unsupported text document Content-Type")
        }
        val parameters = mutableSetOf<String>()
        val token = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
        for (part in parts.drop(1)) {
            val parameter = part.trim().split('=', limit = 2)
            if (parameter.size != 2 || !token.matches(parameter[0].trim()) ||
                !parameters.add(parameter[0].trim().lowercase(Locale.ROOT))) {
                throw IOException("Malformed or duplicate document Content-Type parameter")
            }
            val raw = parameter[1].trim()
            val value = if (raw.startsWith('"') && raw.endsWith('"') && raw.length >= 2)
                raw.substring(1, raw.length - 1).takeIf { it.none { c -> c == '"' || c == '\\' || Character.isISOControl(c) } }
                else raw.takeIf { token.matches(it) }
            if (value == null) throw IOException("Malformed document Content-Type parameter")
            if (parameter[0].trim().equals("charset", true) && value.lowercase(Locale.ROOT) !in setOf("utf-8", "utf8")) {
                throw IOException("Text document requires UTF-8 charset")
            }
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw IOException("Invalid UTF-8 document") }
        if (text.any { it.code < 32 && it !in "\n\r\t" || it == '\u007f' }) {
            throw IOException("Binary controls in text document")
        }
        val links = mutableListOf<Map<String, Any?>>()
        var fenced: Char? = null
        var fenceLength = 0
        val prose = StringBuilder()
        for (line in text.lineSequence()) {
            val marker = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").find(line)
            if (marker != null) {
                val delimiter = marker.groupValues[1]
                if (fenced == null) { fenced = delimiter.first(); fenceLength = delimiter.length }
                else if (delimiter.first() == fenced && delimiter.length >= fenceLength && marker.groupValues[2].isBlank()) fenced = null
                continue
            }
            if (fenced == null) prose.append(line).append('\n')
        }
        fun link(raw: String, label: String) {
            if (links.size >= 100) return
            val href = raw.removeSurrounding("<", ">")
            try {
                val resolved = UriReferences.resolve(url, href)
                if (resolved.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
                    resolved.rawUserInfo != null || resolved.host == null || resolved.toASCIIString().length > 2048) return
                links += linkedMapOf("raw_href" to href, "target_url" to resolved.toASCIIString(),
                    "text" to label, "disposition" to "OBSERVED_DOCUMENT_LINK", "network_requested" to false)
            } catch (_: Exception) { /* Raw source is retained; no fabricated URL. */ }
        }
        if (format == "MARKDOWN") {
            inlineLink.findAll(prose).forEach { link(it.groupValues[2], it.groupValues[1]) }
            reference.findAll(prose).forEach { link(it.groupValues[2], it.groupValues[1]) }
            autoLink.findAll(prose).forEach { link(it.groupValues[1], it.groupValues[1]) }
        }
        val title = if (format == "MARKDOWN" && !hasAmbiguousCode(text)) prose.toString().lineSequence().firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim()?.take(2048) else null
        return linkedMapOf("schema_version" to 1, "document_format" to format, "content_type" to type,
            "charset" to "UTF-8", "source_text" to text, "text" to text, "title" to title.orEmpty(),
            "body_markdown" to if (format == "MARKDOWN") inertMarkdown(text, url) else fencedText(text),
            "links" to links.distinctBy { it["target_url"] }, "content_trust" to "UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS",
            "extraction_status" to "SOURCE_PRESERVED", "limitations" to if (format == "MARKDOWN")
                listOf("CONSERVATIVE_LINK_EXTRACTION_NOT_A_FULL_COMMONMARK_PARSER", "LINKS_BOUNDED_TO_100") +
                    (if (hasUnsupportedDestination(text)) listOf("UNSUPPORTED_LINK_SCHEME_RENDERED_AS_LITERAL_SOURCE") else emptyList()) +
                    (if (hasAmbiguousCode(text)) listOf("AMBIGUOUS_CODE_DELIMITERS_RENDERED_AS_LITERAL_SOURCE") else emptyList())
                else emptyList<String>())
    }

    /** Экранирует raw HTML без исполнения; unsafe link schemes отображаются как literal source. */
    @JvmStatic @JvmOverloads
    fun inertMarkdown(text: String, sourceUrl: String? = null): String {
        if (hasUnsupportedDestination(text) || hasAmbiguousCode(text)) return fencedText(text)
        // Escaping angle brackets also makes raw HTML/event handlers inert in Markdown readers.
        fun escape(value: String): String {
            val protected = mutableListOf<String>()
            fun protect(value: String): String {
                protected += value
                return "\u0001${protected.lastIndex}\u0002"
            }
            fun absolute(raw: String): String? = try {
                if (sourceUrl == null) null else {
                    val target = UriReferences.resolve(sourceUrl, raw.removeSurrounding("<", ">"))
                    if (target.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && target.host != null &&
                        target.rawUserInfo == null && target.toASCIIString().length <= 2048) target.toASCIIString() else null
                }
            } catch (_: Exception) { null }
            var prepared = inlineLink.replace(value) { match ->
                val raw = match.groups[2]!!
                val target = absolute(raw.value)
                if (target == null) match.value else match.value.replaceRange(
                    raw.range.first - match.range.first, raw.range.last - match.range.first + 1,
                    protect("<$target>"))
            }
            prepared = reference.replace(prepared) { match ->
                val raw = match.groups[2]!!
                val target = absolute(raw.value)
                if (target == null) match.value else match.value.replaceRange(
                    raw.range.first - match.range.first, raw.range.last - match.range.first + 1,
                    protect("<$target>"))
            }
            prepared = autoLink.replace(prepared) { match ->
                absolute(match.groupValues[1])?.let { protect("[$it](<$it>)") } ?: match.value
            }
            var escaped = prepared.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace(Regex("!\\["), "[image: ")
            protected.forEachIndexed { index, text -> escaped = escaped.replace("\u0001${index}\u0002", text) }
            return escaped
        }
        var marker: Char? = null
        var width = 0
        return text.splitToSequence('\n').joinToString("\n") { line ->
            val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").find(line)
            if (fence != null) {
                val run = fence.groupValues[1]
                if (marker == null) { marker = run.first(); width = run.length }
                else if (run.first() == marker && run.length >= width && fence.groupValues[2].isBlank()) marker = null
                line
            } else if (marker != null) line
            else {
                val output = StringBuilder()
                var at = 0
                while (at < line.length) {
                    val open = line.indexOf('`', at)
                    if (open < 0) { output.append(escape(line.substring(at))); break }
                    output.append(escape(line.substring(at, open)))
                    var end = open
                    while (end < line.length && line[end] == '`') end++
                    val run = line.substring(open, end)
                    val close = Regex("(?<!`)" + Regex.escape(run) + "(?!`)").find(line, end)
                    if (close == null) { output.append(escape(line.substring(open))); break }
                    output.append(line.substring(open, close.range.last + 1))
                    at = close.range.last + 1
                }
                output.toString()
            }
        }
    }

    /** Ambiguous/multiline destinations fail inert; raw source remains complete. */
    private fun hasUnsupportedDestination(text: String): Boolean =
        Regex("""(?im)(?:\]\(\s*<?|^ {0,3}\[[^\]\n]+]:\s*<?)([a-z][a-z0-9+.-]*):""")
            .findAll(text).any { it.groupValues[1].lowercase(Locale.ROOT) !in setOf("http", "https") }

    /** CommonMark-incompatible or multiline inline-code tokens use an explicitly literal fallback. */
    private fun hasAmbiguousCode(text: String): Boolean {
        var marker: Char? = null
        var width = 0
        for (line in text.lineSequence()) {
            val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").find(line)
            if (fence != null) {
                val run = fence.groupValues[1]
                if (marker == null) {
                    if (run.first() == '`' && '`' in fence.groupValues[2]) return true
                    marker = run.first(); width = run.length
                } else if (run.first() == marker && run.length >= width && fence.groupValues[2].isBlank()) marker = null
                continue
            }
            if (marker != null) continue
            var at = 0
            while (at < line.length) {
                val open = line.indexOf('`', at)
                if (open < 0) break
                var backslashes = 0
                var before = open - 1
                while (before >= 0 && line[before--] == '\\') backslashes++
                if (backslashes % 2 == 1) return true
                var end = open
                while (end < line.length && line[end] == '`') end++
                val run = line.substring(open, end)
                val close = Regex("(?<!`)" + Regex.escape(run) + "(?!`)").find(line, end) ?: return true
                at = close.range.last + 1
            }
        }
        return false
    }

    /** Подбирает fence длиннее любого backtick run: исходный текст не может закрыть блок. */
    @JvmStatic
    fun fencedText(text: String): String {
        val width = maxOf(3, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1)
        val fence = "`".repeat(width)
        return "$fence\n$text\n$fence"
    }
}
