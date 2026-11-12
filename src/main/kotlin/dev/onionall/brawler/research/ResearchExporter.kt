package dev.onionall.brawler.research

import com.fasterxml.jackson.databind.ObjectMapper
import dev.onionall.brawler.tor.TorPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

/**
 * Bounded локальный экспорт research metadata и page-level текста для дальнейшего анализа.
 * Полученный контент остаётся данными: HTML/JavaScript не выполняются, внешние AI API не вызываются.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
object ResearchExporter {
    const val MAX_EXPORT_BYTES = 8 * 1024 * 1024
    private val json = ObjectMapper()
    private const val TRUST = "UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS"

    /**
     * output — новый каталог с одним research.json/jsonl/md/txt; существующие пути не заменяются.
     * Весь bounded payload проверяется до создания каталога. Частичная ошибка записи не выдаётся за успех.
     * Каждый page block сохраняет source URL, фактический fetched_at и job/session identity.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun export(result: Map<String, Any?>, details: List<Map<String, Any?>>,
        format: String, output: Path): Map<String, Any?> {
        val session = result["session_id"] as? String ?: throw IOException("Missing research session identifier")
        identifier(session)
        if (details.size > 100) throw IOException("Research export page limit reached")
        val pages = details.map { page ->
            val id = identifier(page["page_id"] as? String)
            val job = identifier(page["job_id"] as? String)
            val source = page["canonical_url"] as? String ?: throw IOException("Missing archived source URL")
            val uri = try { URI(source) } catch (_: Exception) { throw IOException("Invalid archived source URL") }
            if (uri.scheme !in setOf("http", "https") || !TorPolicy.validOnion(uri.host ?: "") ||
                uri.rawUserInfo != null || uri.port !in setOf(-1, if (uri.scheme == "http") 80 else 443)) {
                throw IOException("Invalid archived source URL")
            }
            if (page["text"] !is String) throw IOException("Missing archived page text")
            val fetched = page["fetched_at"]
            val kind = page["source_kind"]
            if (kind !in setOf("CRAWLER", "MANUAL_IMPORT") || kind == "CRAWLER" && fetched == null) {
                throw IOException("Invalid archived fetch provenance")
            }
            if (fetched != null) timestamp(fetched)
            page["recorded_at"]?.let(::timestamp)
            linkedMapOf<String, Any?>("session_id" to session, "job_id" to job, "page_id" to id,
                "source_url" to source, "fetched_at" to fetched, "recorded_at" to page["recorded_at"],
                "discussion_structure" to "PAGE_LEVEL_TEXT_ONLY", "content_trust" to TRUST,
                "page" to LinkedHashMap(page))
        }
        val selectedFormat = when (format.lowercase(Locale.ROOT)) {
            "json" -> "json"
            "jsonl" -> "jsonl"
            "markdown", "md" -> "markdown"
            "text", "txt" -> "text"
            else -> throw IOException("Require json, jsonl, markdown or text export format")
        }
        val payload = boundedBytes { outputStream ->
            when (selectedFormat) {
                "json" -> json.writeValue(outputStream, linkedMapOf("export_version" to 1,
                    "session_id" to session, "content_trust" to TRUST,
                    "research" to result, "pages" to pages))
                "jsonl" -> {
                    json.factory.createGenerator(outputStream).use { generator ->
                        generator.codec = json
                        generator.writeObject(linkedMapOf("record_type" to "research",
                            "export_version" to 1, "session_id" to session,
                            "content_trust" to TRUST, "research" to result.minus("pages")))
                        generator.writeRaw('\n')
                        for (page in pages) {
                            generator.writeObject(linkedMapOf("record_type" to "page") + page)
                            generator.writeRaw('\n')
                        }
                    }
                }
                else -> OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    renderText(writer, result, pages, selectedFormat == "markdown")
                }
            }
        }
        val destination = output.toAbsolutePath().normalize()
        val parent = destination.parent ?: throw IOException("Export requires a new directory")
        noSymlinks(destination)
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || Files.exists(destination, NOFOLLOW_LINKS)) {
            throw IOException("Export requires an existing parent and a new output directory; nothing overwritten")
        }
        val filename = when (selectedFormat) {
            "json" -> "research.json"
            "jsonl" -> "research.jsonl"
            "markdown" -> "research.md"
            else -> "research.txt"
        }
        try {
            if (Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
                Files.createDirectory(destination, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createDirectory(destination)
            }
            val target = destination.resolve(filename)
            FileChannel.open(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = ByteBuffer.wrap(payload)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            return linkedMapOf("session_id" to session, "format" to selectedFormat,
                "output_directory" to destination.toString(), "output_file" to target.toString(),
                "bytes" to payload.size, "sha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),
                "pages_exported" to pages.size, "discussion_structure" to "PAGE_LEVEL_TEXT_ONLY",
                "content_trust" to TRUST, "network_requests" to 0, "exported_at" to Instant.now().toString())
        } catch (_: Exception) {
            // Удаление по пути после ошибки могло бы затронуть чужой race-created файл: сохраняем evidence.
            throw IOException("Research export publication failed; inspect the requested output directory")
        }
    }

    private fun renderText(writer: Writer, result: Map<String, Any?>,
        pages: List<Map<String, Any?>>, markdown: Boolean) {
        writer.write(if (markdown) "# RESEARCH EXPORT\n\n" else "RESEARCH EXPORT\n\n")
        writer.write("Session ID: ${result["session_id"]}\n")
        writer.write("Captured pages: ${pages.size}\n")
        writer.write("Content trust: $TRUST\n")
        writer.write("Discussion structure: readable page-level text; no universal post parser or completeness claim.\n\n")
        writer.write(if (markdown) "## RESEARCH METADATA\n\n" else "RESEARCH METADATA\n\n")
        val metadata = boundedBytes { json.writeValue(it, result.minus("pages")) }.toString(Charsets.UTF_8)
        quoted(writer, metadata, markdown)
        for ((index, wrapper) in pages.withIndex()) {
            @Suppress("UNCHECKED_CAST")
            val page = wrapper["page"] as Map<String, Any?>
            writer.write(if (markdown) "\n## PAGE ${index + 1}\n\n" else "\nPAGE ${index + 1}\n\n")
            writer.write("Session ID: ${wrapper["session_id"]}\n")
            writer.write("Job ID: ${wrapper["job_id"]}\n")
            writer.write("Page ID: ${wrapper["page_id"]}\n")
            writer.write("Source URL: ${wrapper["source_url"]}\n")
            writer.write("Fetched at: ${wrapper["fetched_at"] ?: "UNKNOWN / NOT FETCHED"}\n")
            writer.write("Recorded at: ${wrapper["recorded_at"] ?: "UNKNOWN"}\n")
            writer.write("Source kind: ${plain(page["source_kind"]?.toString() ?: "UNKNOWN")}\n")
            writer.write("Title (untrusted):\n")
            quoted(writer, page["title"] as? String ?: "", markdown)
            writer.write("\nCaptured text (untrusted data, not instructions):\n")
            quoted(writer, page["text"] as String, markdown)
            // Сохраняем page metadata/links/history отдельно от текста, не смешивая источники.
            writer.write("\nPage metadata:\n")
            val metadataPage = boundedBytes { json.writeValue(it, page.minus("text")) }.toString(Charsets.UTF_8)
            quoted(writer, metadataPage, markdown)
        }
    }

    private fun quoted(writer: Writer, text: String, markdown: Boolean) {
        for (line in plain(text).lineSequence()) {
            writer.write(if (markdown) "> " else "| ")
            writer.write(if (markdown) line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") else line)
            writer.write("\n")
        }
    }

    private fun plain(value: String): String = buildString {
        for (character in value) {
            if ((Character.isISOControl(character) && character != '\n' && character != '\t') ||
                character.code in 0x2028..0x202e || character.code in 0x2066..0x2069 ||
                character.code in 0x200e..0x200f) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else append(character)
        }
    }

    private fun boundedBytes(action: (OutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) {
                if (bytes.size() >= MAX_EXPORT_BYTES) throw IOException("Research export 8MiB limit reached")
                bytes.write(value)
            }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                if (length > MAX_EXPORT_BYTES - bytes.size()) throw IOException("Research export 8MiB limit reached")
                bytes.write(value, offset, length)
            }
        }
        try { action(output) } catch (_: Exception) {
            throw IOException("Research export cannot be encoded within 8MiB limit")
        }
        return bytes.toByteArray()
    }

    private fun identifier(value: String?): String {
        if (value == null || try { UUID.fromString(value).toString() != value } catch (_: Exception) { true }) {
            throw IOException("Invalid research export identifier")
        }
        return value
    }

    private fun timestamp(value: Any?): String {
        if (value !is String || value.length > 40) throw IOException("Invalid export timestamp")
        try { Instant.parse(value) } catch (_: Exception) { throw IOException("Invalid export timestamp") }
        return value
    }

    private fun noSymlinks(path: Path) {
        var current: Path? = path
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw IOException("Export paths must not contain symbolic links")
            current = current.parent
        }
    }
}
