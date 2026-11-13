package dev.onionall.brawler.research

import java.io.ByteArrayInputStream
import java.net.URI
import dev.onionall.brawler.tor.UriReferences
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Консервативная extraction из уже полученного HTML: два контролируемых DOM-контракта
 * и readable-page fallback. Ни network, ни JS, ни универсальная поддержка forum engines не подразумеваются.
 * Author/date/id берутся только из явных DOM-полей, неизвестные значения остаются null.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
object ForumExtractor {
    const val MAX_HTML_BYTES = 131072
    const val MAX_POSTS = 100
    const val MAX_POST_TEXT = 16384
    const val MAX_PAGE_TEXT = 65536
    private const val MAX_LINKS = 100
    private const val MAX_QUOTES = 100
    private const val SCHEMA = "https://schema.org/DiscussionForumPosting"
    private const val TRADITIONAL = "CONTROLLED_POST_BODY_V1"
    private const val SEMANTIC = "SCHEMA_DISCUSSION_ARTICLE_V1"
    private const val TRUST = "UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS"
    private val removed = setOf("script", "style", "noscript", "template", "iframe", "object", "embed", "svg", "canvas")
    private val formats = setOf("pdf", "epub", "txt", "md", "markdown", "docx", "odt", "rtf")
    private val documentMedia = setOf("application/pdf", "application/epub+zip", "text/plain", "text/markdown",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text", "application/rtf", "text/rtf")

    /**
     * Неизвестный layout возвращает page_text и пустые posts; FAILED не уничтожает исходный архив.
     * source_post_id — только фактический DOM id, ordinal задаёт порядок контейнеров в этой странице.
     */
    @JvmStatic
    fun extract(url: String, html: ByteArray): Map<String, Any?> {
        val source = safeSource(url) ?: return failed("INVALID_SOURCE_URL")
        if (html.isEmpty() || html.size > MAX_HTML_BYTES) return failed("HTML_SIZE_LIMIT", source)
        return try {
            parse(source, Jsoup.parse(ByteArrayInputStream(html), null, source))
        } catch (_: Exception) {
            failed("HTML_EXTRACTION_FAILED", source)
        }
    }

    private fun parse(source: String, document: Document): Map<String, Any?> {
        val page = pageText(document)
        val title = document.title().take(2048)
        val rawCandidates = document.select("div.post[id],article[itemscope][itemtype=\"$SCHEMA\"]")
        val candidates = rawCandidates.filter { candidate ->
            candidate.parents().none { ancestor -> ancestor.tagName() == "blockquote" || rawCandidates.contains(ancestor) }
        }
        val idCounts = document.select("[id]").map { it.id() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val posts = mutableListOf<Map<String, Any?>>()
        val limitations = linkedSetOf<String>()
        var unsupported = 0
        for ((index, container) in candidates.take(MAX_POSTS).withIndex()) {
            val layout = layout(container)
            if (layout == null) {
                unsupported++
                continue
            }
            val post = post(source, document, container, layout, index + 1, idCounts)
            if (post == null) unsupported++ else posts += post
        }
        if (unsupported > 0) limitations += "UNSUPPORTED_OR_EMPTY_POST_CONTAINERS"
        if (candidates.size > MAX_POSTS) limitations += "POST_LIMIT_REACHED"
        if (page.truncated) limitations += "PAGE_TEXT_TRUNCATED"
        if (posts.any { (it["evidence"] as Map<*, *>)["partial"] == true }) limitations += "PARTIAL_POST_FIELDS"
        val duplicates = posts.mapNotNull { it["source_post_id"] as? String }.distinct().filter { (idCounts[it] ?: 0) > 1 }
        if (duplicates.isNotEmpty()) limitations += "DUPLICATE_DOM_POST_IDS"
        val methods = posts.map { it["extraction_method"] as String }.distinct()
        val dynamic = posts.isEmpty() && dynamicShell(document, page.text)
        val status = when {
            posts.isNotEmpty() && limitations.isEmpty() -> "STRUCTURED_POSTS"
            posts.isNotEmpty() -> "PARTIAL"
            dynamic -> "UNSUPPORTED_DYNAMIC_CONTENT"
            unsupported > 0 && page.text.isNotBlank() -> "PARTIAL"
            page.text.isNotBlank() -> "PAGE_TEXT_ONLY"
            else -> "FAILED"
        }
        if (dynamic) limitations += "STATIC_HTML_DOES_NOT_EXPOSE_DISCUSSION_MESSAGES"
        if (posts.isEmpty() && !dynamic) limitations += "POST_BOUNDARIES_NOT_ESTABLISHED"
        return linkedMapOf("source_url" to source, "title" to title, "page_text" to page.text,
            "extraction_status" to status,
            "extraction_method" to when {
                methods.size == 1 -> methods.single()
                methods.size > 1 -> "MIXED_CONTROLLED_DOM_V1"
                dynamic -> "STATIC_DYNAMIC_SHELL_HEURISTIC_V1"
                else -> "GENERIC_DOM_BLOCK_TEXT_V1"
            }, "posts" to posts, "content_trust" to TRUST, "limitations" to limitations.toList(),
            "evidence" to mapOf("post_containers_observed" to candidates.size, "posts_extracted" to posts.size,
                "unsupported_containers" to unsupported, "post_limit" to MAX_POSTS,
                "page_text_truncated" to page.truncated, "duplicate_source_post_ids" to duplicates,
                "engine_compatibility" to "CONTROLLED_DOM_CONTRACTS_ONLY"))
    }

    private data class Layout(val method: String, val body: Element, val bodySelector: String,
        val authorNodes: List<Element>, val authorSelector: String,
        val timeNodes: List<Element>, val timeSelector: String)

    private fun layout(container: Element): Layout? {
        if (container.tagName() == "div" && container.hasClass("post")) {
            if (!container.id().matches(Regex("(?:p|post-)[0-9]{1,20}"))) return null
            val body = container.children().filter { it.tagName() == "div" && it.hasClass("postbody") }.singleOrNull()
                ?: return null
            val content = body.children().filter { it.tagName() == "div" && it.hasClass("content") }.singleOrNull()
                ?: return null
            val headers = body.children().filter { it.tagName() == "p" && it.hasClass("author") }
            return Layout(TRADITIONAL, content, ":scope > div.postbody > div.content",
                headers.flatMap { it.children().filter { node -> node.tagName() == "a" && node.hasClass("username") } },
                ":scope > div.postbody > p.author > a.username",
                headers.flatMap { it.children().filter { node -> node.tagName() == "time" && node.hasAttr("datetime") } },
                ":scope > div.postbody > p.author > time[datetime]")
        }
        if (container.tagName() == "article" && container.hasAttr("itemscope") && container.attr("itemtype") == SCHEMA) {
            val content = container.children().filter { it.tagName() == "div" && item(it, "text") }.singleOrNull() ?: return null
            val headers = container.children().filter { it.tagName() == "header" }
            val authorNames = headers.flatMap { header -> header.children().filter { item(it, "author") }
                .flatMap { author -> author.children().filter { item(it, "name") } } }
            return Layout(SEMANTIC, content, ":scope > div[itemprop=text]", authorNames,
                ":scope > header > [itemprop=author] > [itemprop=name]",
                headers.flatMap { it.children().filter { node -> node.tagName() == "time" && item(node, "datePublished") && node.hasAttr("datetime") } },
                ":scope > header > time[itemprop=datePublished][datetime]")
        }
        return null
    }

    private fun post(source: String, document: Document, container: Element, layout: Layout,
        ordinal: Int, idCounts: Map<String, Int>): Map<String, Any?>? {
        if (hidden(container) || hidden(layout.body)) return null
        val ownBody = layout.body.clone()
        ownBody.select("blockquote").remove()
        val text = readable(ownBody, MAX_POST_TEXT)
        val quoteNodes = layout.body.select("blockquote")
        val quotes = quoteNodes.take(MAX_QUOTES).map { quote ->
            val copy = quote.clone()
            copy.select("blockquote").filter { it !== copy }.forEach { it.remove() }
            val value = readable(copy, MAX_POST_TEXT)
            mapOf("depth" to quote.parents().takeWhile { it !== layout.body }.count { it.tagName() == "blockquote" },
                "text" to value.text, "text_truncated" to value.truncated,
                "citation_text" to quote.children().filter { it.tagName() == "cite" }.singleOrNull()?.text()?.take(512))
        }
        val links = referencedLinks(source, document, layout.body)
        val id = container.id().takeIf { it.isNotBlank() && it.length <= 128 &&
            it.none { character -> character.isWhitespace() || Character.isISOControl(character) } }
        val attachments = links.values.mapNotNull { link -> attachment(link, source, id, ordinal) }
            .distinctBy { it["target_url"] }
        if (text.text.isBlank() && quotes.all { (it["text"] as String).isBlank() } && links.values.isEmpty()) return null
        val authorNodes = layout.authorNodes.filterNot(::hidden)
        val timeNodes = layout.timeNodes.filterNot(::hidden)
        val author = authorNodes.singleOrNull()?.let { readable(it, 256) }
        val rawAuthor = author?.text?.takeIf { it.isNotEmpty() }
        val rawTime = timeNodes.singleOrNull()?.attr("datetime")?.trim()?.takeIf { it.isNotEmpty() }
        val partial = text.truncated || quotes.any { it["text_truncated"] == true } ||
            quoteNodes.size > MAX_QUOTES || links.truncated || authorNodes.size > 1 ||
            timeNodes.size > 1 || author?.truncated == true || (rawTime?.length ?: 0) > 128
        val duplicate = id != null && (idCounts[id] ?: 0) > 1
        return linkedMapOf("source_post_id" to id, "ordinal" to ordinal, "source_page_url" to source,
            "author" to rawAuthor?.take(256), "posted_at" to rawTime?.take(128), "text" to text.text,
            "quoted_text" to quotes.map { it["text"] as String }.filter { it.isNotBlank() },
            "quotes" to quotes, "referenced_links" to links.values, "attachments" to attachments,
            "extraction_method" to layout.method, "confidence" to "DOM_BOUNDARIES_IDENTIFIED",
            "content_trust" to TRUST,
            "evidence" to linkedMapOf("container_selector" to if (layout.method == TRADITIONAL)
                "div.post[id=pDIGITS|post-DIGITS]" else "article[itemscope][itemtype=$SCHEMA]",
                "body_selector" to layout.bodySelector,
                "author_selector" to layout.authorSelector.takeIf { authorNodes.size == 1 },
                "time_selector" to layout.timeSelector.takeIf { timeNodes.size == 1 },
                "posted_at_basis" to if (rawTime != null) "RAW_TIME_DATETIME_ATTRIBUTE" else null,
                "ambiguous_author" to (authorNodes.size > 1), "ambiguous_datetime" to (timeNodes.size > 1),
                "quote_selector" to "blockquote", "quote_text_excluded_from_main_text" to true,
                "source_dom_id" to container.id().takeIf { it.isNotEmpty() }?.take(128),
                "source_post_id_unique_on_page" to (id != null && !duplicate), "duplicate_dom_id" to duplicate,
                "text_truncated" to text.truncated, "quotes_truncated" to (quoteNodes.size > MAX_QUOTES),
                "links_truncated" to links.truncated, "partial" to partial))
    }

    private data class Links(val values: List<Map<String, Any?>>, val truncated: Boolean)

    private fun referencedLinks(source: String, document: Document, body: Element): Links {
        val base = try { UriReferences.resolve(source, document.selectFirst("base[href]")?.attr("href")?.trim() ?: source) }
            catch (_: Exception) { URI(source) }
        val result = mutableListOf<Map<String, Any?>>()
        var truncated = false
        for (anchor in body.select("a[href]")) {
            if (hidden(anchor)) continue
            val raw = anchor.attr("href").trim()
            val target = try {
                val reference = URI(raw)
                if (reference.rawUserInfo != null || reference.scheme?.let { it !in setOf("http", "https") } == true) continue
                val resolved = UriReferences.resolve(base.toASCIIString(), raw)
                if (resolved.scheme !in setOf("http", "https") || resolved.rawUserInfo != null ||
                    resolved.host == null || resolved.toASCIIString().length > 2048) continue
                resolved.toASCIIString()
            } catch (_: Exception) { continue }
            if (result.size >= MAX_LINKS) { truncated = true; break }
            result += linkedMapOf("target_url" to target, "raw_href" to raw.take(2048),
                "text" to anchor.text().take(512), "quoted" to anchor.parents().takeWhile { it !== body }.any { it.tagName() == "blockquote" },
                "download_attribute" to anchor.attr("download").takeIf { anchor.hasAttr("download") }?.take(1024),
                "declared_content_type" to anchor.attr("type").trim().takeIf { it.isNotEmpty() }?.take(256))
        }
        return Links(result, truncated)
    }

    private fun attachment(link: Map<String, Any?>, source: String, postId: String?, ordinal: Int): Map<String, Any?>? {
        val uri = URI(link["target_url"] as String)
        val name = (link["download_attribute"] as? String)?.takeIf { it.isNotBlank() }
            ?: uri.path.substringAfterLast('/').takeIf { it.isNotBlank() }
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        val mime = (link["declared_content_type"] as? String)?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (ext !in formats && mime !in documentMedia) return null
        val target = uri.toASCIIString().substringBefore('#')
        return linkedMapOf("target_url" to target, "source_page_url" to source,
            "source_post_id" to postId, "post_ordinal" to ordinal, "filename" to name?.take(1024),
            "extension" to ext, "declared_content_type" to link["declared_content_type"],
            "content_type" to null, "content_length" to null, "status" to "OBSERVED_LINK",
            "verified_resource" to false, "downloaded" to false, "quoted" to link["quoted"])
    }

    private data class Text(val text: String, val truncated: Boolean)
    private data class Visit(val node: Node, val leave: Boolean, val depth: Int)

    private fun readable(root: Element, limit: Int): Text {
        val result = StringBuilder()
        val work = ArrayDeque<Visit>()
        work.add(Visit(root, false, 0))
        var truncated = false
        while (work.isNotEmpty() && result.length <= limit) {
            val visit = work.removeLast()
            val node = visit.node
            if (visit.depth > 256) { truncated = true; continue }
            if (node is TextNode && !visit.leave) result.append(node.wholeText)
            if (node !is Element) continue
            if (node.tagName() in removed || node.hasAttr("hidden") || node.attr("aria-hidden") == "true") continue
            if (visit.leave) {
                if (node.isBlock || node.tagName() == "br") result.append('\n')
                if (node.tagName() in setOf("td", "th")) result.append('\t')
            } else {
                if (node.isBlock && result.isNotEmpty() && result.last() != '\n') result.append('\n')
                if (node.tagName() == "br") result.append('\n')
                work.add(Visit(node, true, visit.depth))
                for (child in node.childNodes().asReversed()) work.add(Visit(child, false, visit.depth + 1))
            }
        }
        if (work.isNotEmpty() || result.length > limit) truncated = true
        var end = minOf(result.length, limit)
        if (end > 0 && Character.isHighSurrogate(result[end - 1])) end--
        val text = result.substring(0, end).lineSequence().map { it.trimEnd() }.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n").trim()
        return Text(text, truncated)
    }

    private fun pageText(document: Document): Text {
        val copy = document.clone()
        copy.select("nav,header,footer,aside,form,button").remove()
        val main = copy.select("main").singleOrNull() ?: copy.body()
        return readable(main, MAX_PAGE_TEXT)
    }

    private fun dynamicShell(document: Document, text: String): Boolean {
        if (document.select("script").isEmpty() || text.length >= 256) return false
        val app = document.select("#app,#root,#__next,[data-reactroot]").isNotEmpty()
        val label = text + " " + document.select("noscript").text()
        return app && text.length < 80 || Regex("(?i)enable\\s+javascript|javascript\\s+required|requires\\s+javascript")
            .containsMatchIn(label)
    }

    private fun safeSource(url: String): String? {
        if (url.length !in 1..2048) return null
        return try {
            val uri = URI(url)
            if (uri.scheme !in setOf("http", "https") || uri.host == null || uri.rawUserInfo != null) null else uri.toASCIIString()
        } catch (_: Exception) { null }
    }

    private fun item(element: Element, name: String) = element.attr("itemprop").split(Regex("\\s+")).contains(name)

    private fun hidden(element: Element): Boolean = generateSequence(element) { it.parent() }
        .any { it.hasAttr("hidden") || it.attr("aria-hidden") == "true" }

    private fun failed(reason: String, source: String? = null): Map<String, Any?> = linkedMapOf("source_url" to source,
        "title" to "", "page_text" to "", "posts" to emptyList<Any>(), "extraction_status" to "FAILED",
        "extraction_method" to "INPUT_OR_PARSE_FAILURE_V1", "limitations" to listOf(reason), "content_trust" to TRUST)
}
