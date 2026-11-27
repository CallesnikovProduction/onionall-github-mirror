package dev.onionall.brawler.archive

import dev.onionall.brawler.research.ForumExtractor
import dev.onionall.brawler.tor.UriReferences
import java.io.IOException
import java.net.URI
import java.util.UUID

/**
 * Archive-owned чтение discussion structure из проверенного immutable snapshot.
 * Переиспользует conservative ForumExtractor, добавляя readable Markdown и только
 * явно наблюдаемые reply references. Контролируемые DOM contracts не означают
 * универсальную поддержку phpBB, Discourse или XenForo; site completeness неизвестна.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
object ArchiveForumExtractor {
    /**
     * Не fetch-ит документы; исходные bytes и snapshot identity предоставляет Archive.
     * Source post ID отделён от deterministic internal observation ID. Неизвестные
     * author/date/thread/reply fields остаются nullable, неоднозначные IDs не связываются.
     *
     * @param url canonical URL immutable snapshot
     * @param html проверенные исходные HTML bytes, максимум 131072
     * @param snapshotId UUID snapshot, являющийся частью observation identity
     * @return bounded extraction с provenance, posts, limitations и inert Markdown
     * @throws IOException при недопустимом snapshot identifier
     */
    @JvmStatic
    @Throws(IOException::class)
    fun extract(url: String, html: ByteArray, snapshotId: String): Map<String, Any?> {
        if (try { UUID.fromString(snapshotId).toString() != snapshotId } catch (_: Exception) { true }) {
            throw IOException("Invalid archive snapshot identifier")
        }
        val capture = ForumExtractor.capture(url, html, includeRoots = true)
        val baseline = capture.result
        if (baseline["extraction_status"] == "FAILED") return baseline + mapOf("schema_version" to 1,
            "snapshot_id" to snapshotId, "body_markdown" to null, "thread_id" to null, "forum" to null,
            "completeness_status" to "PARTIAL_OR_UNKNOWN", "full_thread_coverage_verified" to false)
        val document = checkNotNull(capture.document)
        val linkBase = try {
            val declared = document.selectFirst("base[href]")?.attr("href")?.trim()
            if (declared == null) url else UriReferences.resolve(url, declared).toASCIIString()
        } catch (_: Exception) { url }
        @Suppress("UNCHECKED_CAST")
        val originalPosts = (baseline["posts"] as? List<Map<String, Any?>>).orEmpty()
        val uniqueIds = originalPosts.mapNotNull { it["source_post_id"] as? String }.filter { capture.idCounts[it] == 1 }.toSet()
        val limitations = (baseline["limitations"] as? List<*>)?.filterIsInstance<String>()?.toMutableSet() ?: linkedSetOf()
        fun explicit(selector: String, attribute: String): String? {
            val values = document.select(selector).map { it.attr(attribute).trim() }.filter { it.isNotEmpty() }.distinct()
            return values.singleOrNull()?.takeIf { it.length <= 256 && it.none { character -> Character.isISOControl(character) } }
        }
        val threadId = explicit("[data-thread-id]", "data-thread-id")
        val forum = explicit("[data-forum-name]", "data-forum-name")
        fun target(rawReference: String, directId: Boolean): String? {
            val reference = rawReference.trim()
            if (directId && reference in uniqueIds) return reference
            return try {
                val resolved = UriReferences.resolve(linkBase, reference)
                val source = URI(url)
                if (resolved.scheme != source.scheme || resolved.rawAuthority != source.rawAuthority ||
                    resolved.rawPath != source.rawPath || resolved.rawQuery != source.rawQuery) null
                else resolved.fragment?.takeIf { it in uniqueIds }
            } catch (_: Exception) { null }
        }
        val roots = originalPosts.map { post -> capture.roots.getValue((post["ordinal"] as Number).toInt()) }
        val markdown = NativeContent.renderAll(listOf(document.body()) + roots.map { it.second }, url)
        val posts = originalPosts.mapIndexed { index, post ->
            val ordinal = (post["ordinal"] as Number).toInt()
            val (container, body) = roots[index]
            val rendered = markdown[index + 1]
            if (rendered.truncated) limitations += "ARCHIVE_MARKDOWN_TRUNCATED"
            val rawReply = container.attr("data-reply-to").takeIf { it.isNotBlank() }
            val reply = rawReply?.let { target(it, true) }
            if (rawReply != null && reply == null) limitations += "UNRESOLVED_EXPLICIT_REPLY_REFERENCE"
            val quoted = body.select("blockquote").flatMap { quote ->
                val references = mutableListOf<String>()
                if (quote.hasAttr("data-post-id")) target(quote.attr("data-post-id"), true)?.let { references += it }
                if (quote.hasAttr("cite")) target(quote.attr("cite"), false)?.let { references += it }
                references += quote.select("cite a[href]").mapNotNull { target(it.attr("href"), false) }
                references
            }.distinct()
            val id = post["source_post_id"] as? String
            val canonical = if (id != null && id in uniqueIds) url.substringBefore('#') + "#" +
                URI(null, null, null, -1, null, null, id).rawFragment else null
            post + linkedMapOf("post_id" to id,
                "post_id_basis" to if (id == null) null else "OBSERVED_DOM_ID",
                "observation_id" to UUID.nameUUIDFromBytes("$snapshotId|$ordinal".toByteArray(Charsets.UTF_8)).toString(),
                "snapshot_id" to snapshotId, "thread_id" to threadId,
                "canonical_url" to canonical, "parent_post_id" to reply, "reply_to" to reply,
                "quoted_post_ids" to quoted, "body_text" to post["text"],
                "body_markdown" to rendered.markdown, "body_markdown_includes_quotes" to true,
                "links" to post["referenced_links"],
                "extraction_status" to if (rendered.truncated || rawReply != null && reply == null) "PARTIAL" else "STRUCTURED_POST")
        }
        val page = markdown.first()
        if (page.truncated) limitations += "ARCHIVE_MARKDOWN_TRUNCATED"
        return baseline + linkedMapOf("schema_version" to 1, "snapshot_id" to snapshotId,
            "thread_id" to threadId, "forum" to forum, "posts" to posts,
            "body_markdown" to page.markdown, "limitations" to limitations.toList(),
            "extraction_status" to if (posts.isNotEmpty() && limitations.isNotEmpty()) "PARTIAL" else baseline["extraction_status"],
            "completeness_status" to "PARTIAL_OR_UNKNOWN", "full_thread_coverage_verified" to false)
    }
}
