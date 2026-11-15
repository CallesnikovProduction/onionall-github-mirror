package dev.onionall.brawler.archive

import dev.onionall.brawler.tor.UriReferences
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Структурное представление уже архивированного HTML как inert Markdown.
 * Не выполняет HTML/JavaScript, не загружает изображения и не делает сетевых запросов.
 * Ограничения отражаются отдельно от исходного content-addressed документа.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
internal object ArchiveHtmlMarkdown {
    private val removed = setOf("script", "style", "noscript", "template", "iframe", "object", "embed", "svg", "canvas")
    private val punctuation = "\\`*_{}[]()#+-.!|>"

    /** Результат bounded rendering; truncated запрещает заявлять полную extraction. */
    data class Rendered(val markdown: String, val truncated: Boolean)

    /**
     * Сохраняет headings/code/tables/quotes и разрешённые HTTP links, экранируя remote markup.
     * @param element корень уже разобранного DOM
     * @param sourceUrl provenance URL и base относительных ссылок
     * @param limit максимум UTF-16 символов представления
     * @return Markdown и наблюдаемый признак усечения
     */
    fun render(element: Element, sourceUrl: String, limit: Int = 131072): Rendered {
        var limited = false
        val base = try {
            val declared = element.ownerDocument()?.selectFirst("base[href]")?.attr("href")?.trim()
            if (declared == null) sourceUrl else UriReferences.resolve(sourceUrl, declared).toASCIIString()
        } catch (_: Exception) { sourceUrl }
        fun safeLink(raw: String): String? = try {
            val target = UriReferences.resolve(base, raw.trim())
            if (target.scheme !in setOf("http", "https") || target.host == null || target.rawUserInfo != null ||
                target.toASCIIString().length > 2048 || raw.any { Character.isISOControl(it) }) null
            else target.toASCIIString()
        } catch (_: Exception) { null }
        fun fence(text: String): String = "`".repeat(maxOf(3, Regex("`+").findAll(text).maxOfOrNull { it.value.length + 1 } ?: 3))
        fun walk(node: Node, depth: Int): String {
            if (depth > 128) { limited = true; return "" }
            if (node is TextNode) return escape(node.wholeText)
            if (node !is Element || node.tagName() in removed || node.hasAttr("hidden") || node.attr("aria-hidden") == "true") return ""
            val tag = node.tagName()
            if (tag == "pre") {
                val code = node.wholeText()
                val marker = fence(code)
                return "\n\n$marker\n$code" + (if (code.endsWith('\n')) "" else "\n") + "$marker\n\n"
            }
            if (tag == "code") {
                val code = node.wholeText().replace('\n', ' ')
                val marker = "`".repeat(maxOf(1, Regex("`+").findAll(code).maxOfOrNull { it.value.length + 1 } ?: 1))
                return "$marker $code $marker"
            }
            if (tag == "img") return escape(node.attr("alt").ifBlank { "[image omitted]" })
            if (tag == "table") {
                val rows = node.select("tr").filter { it.closest("table") === node }
                val cells = rows.map { row -> row.children().filter { it.tagName() in setOf("th", "td") } }
                val width = cells.maxOfOrNull { it.size } ?: return ""
                if (width == 0) return ""
                fun row(values: List<String>) = "| " + (values + List(maxOf(0, width - values.size)) { "" }).joinToString(" | ") + " |\n"
                val values = cells.map { line -> line.map { cell -> cell.childNodes().joinToString("") { walk(it, depth + 1) }
                    .trim().replace(Regex("\\s*\n\\s*"), " ⏎ ").replace(Regex("(?<!\\\\)\\|"), "\\\\|") } }
                val headed = cells.firstOrNull()?.any { it.tagName() == "th" } == true
                val caption = node.children().filter { it.tagName() == "caption" }
                    .joinToString("\n") { it.childNodes().joinToString("") { child -> walk(child, depth + 1) } }.trim()
                return "\n\n" + (if (caption.isEmpty()) "" else "$caption\n\n") +
                    (if (headed) row(values.first()) else row(emptyList())) + row(List(width) { "---" }) +
                    values.drop(if (headed) 1 else 0).joinToString("") { row(it) } + "\n"
            }
            val content = node.childNodes().joinToString("") { walk(it, depth + 1) }
            return when {
                tag == "a" -> if (node.hasAttr("href")) safeLink(node.attr("href"))?.let { "[$content](<$it>)" } ?: content else content
                tag == "blockquote" -> "\n\n" + content.trim().lines().joinToString("\n") { "> $it" } + "\n\n"
                tag.matches(Regex("h[1-6]")) -> "\n\n" + "#".repeat(tag.last().digitToInt()) + " " + content.trim() + "\n\n"
                tag == "br" -> "\n"
                tag == "li" -> {
                    val parent = node.parent()
                    val marker = if (parent?.tagName() == "ol") {
                        val start = parent.attr("start").toIntOrNull() ?: 1
                        val position = parent.children().filter { it.tagName() == "li" }.indexOf(node)
                        "${node.attr("value").toIntOrNull() ?: (start.toLong() + position)}. "
                    } else "- "
                    "\n$marker" + content.trim().replace("\n", "\n  ") + "\n"
                }
                tag in setOf("strong", "b") -> "**$content**"
                tag in setOf("em", "i") -> "*$content*"
                tag == "hr" -> "\n\n---\n\n"
                node.isBlock -> "\n\n$content\n\n"
                else -> content
            }
        }
        val rendered = walk(element, 0).trim()
        if (rendered.length <= limit) return Rendered(rendered, limited)
        // Cutting through an inline-code delimiter could expose literal HTML.
        // A bounded escaped-text fallback stays inert even when its final token is cut.
        val fallback = element.clone().also { copy ->
            copy.select(removed.joinToString(",") + ",[hidden],[aria-hidden=true]").remove()
        }.wholeText().let(::escape)
        var end = minOf(fallback.length, limit)
        if (end > 0 && Character.isHighSurrogate(fallback[end - 1])) end--
        return Rendered(fallback.substring(0, end), true)
    }

    /** Remote text не может породить raw HTML, image, heading или Markdown delimiter. */
    private fun escape(value: String): String = buildString {
        for (character in value) when {
            character == '&' -> append("&amp;")
            character == '<' -> append("&lt;")
            character == '>' -> append("&gt;")
            character in punctuation -> { append('\\'); append(character) }
            Character.isISOControl(character) && character !in setOf('\n', '\t', '\r') -> append('\uFFFD')
            else -> append(character)
        }
    }
}
