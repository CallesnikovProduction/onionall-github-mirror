package dev.onionall.brawler.archive

import dev.onionall.brawler.tor.UriReferences
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter

/**
 * Передаёт проверенное DOM-представление и пакеты текстовых проекций в Rust.
 * JVM сохраняет charset, HTML recovery и URL policy. Native код получает только
 * используемую структуру, текст и уже проверенные цели ссылок.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
internal object NativeContent {
    private const val MAX_FRAME = 2 * 1024 * 1024
    private const val MAX_OUTPUT = 131072
    private val magic = byteArrayOf(79, 67, 77, 49) // OCM1
    private val textMagic = byteArrayOf(79, 67, 84, 49) // OCT1
    private val removed = setOf("script", "style", "noscript", "template", "iframe", "object", "embed", "svg", "canvas")
    private val binding by lazy {
        try {
            Result.success(load())
        } catch (failure: RuntimeException) {
            Result.failure(failure)
        } catch (failure: LinkageError) {
            Result.failure(failure)
        }
    }

    private fun load(): java.lang.invoke.MethodHandle {
        val configured = System.getProperty("onionall.content.library") ?: System.getenv("ONIONALL_CONTENT_LIBRARY")
        val path = if (configured != null) Path.of(configured) else {
            val directory = System.getProperty("onionall.content.directory")
                ?: throw IllegalArgumentException("An explicit native library path is required")
            Path.of(directory, System.mapLibraryName("onionall_content"))
        }
        require(path.isAbsolute) { "Native library path must be absolute" }
        val arena = Arena.ofShared()
        try {
            val symbol = SymbolLookup.libraryLookup(path.normalize(), arena)
                .find("onionall_content_render_v1").orElseThrow()
            // The bound symbol holds the shared arena live for the JVM lifetime.
            return Linker.nativeLinker().downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
        } catch (failure: RuntimeException) {
            arena.close()
            throw failure
        } catch (failure: LinkageError) {
            arena.close()
            throw failure
        }
    }

    data class Rendered(val markdown: String, val truncated: Boolean)

    /** Batch page/post roots. Independently bounded roots may require separate frames. */
    fun renderAll(roots: List<Element>, sourceUrl: String, limit: Int = MAX_OUTPUT): List<Rendered> {
        require(roots.size in 1..101 && limit in 0..MAX_OUTPUT)
        val base = try {
            val declared = roots.first().ownerDocument()?.selectFirst("base[href]")?.attr("href")?.trim()
            if (declared == null) sourceUrl else UriReferences.resolve(sourceUrl, declared).toASCIIString()
        } catch (_: IllegalArgumentException) { sourceUrl }
        return boundedBatch(roots) { batch -> renderBatch(batch, base, limit) }
    }

    private fun renderBatch(roots: List<Element>, base: String, limit: Int): List<Rendered> {
        val input = ByteArrayOutputStream()
        val writer = DataOutputStream(input)
        writer.write(magic)
        writer.writeByte(roots.size)
        writer.writeInt(Integer.reverseBytes(limit))
        for (root in roots) Projection(writer, base).node(root, 0, null)
        return exchange(input.toByteArray()) { decodeMarkdown(it, roots.size, limit) }
    }

    /** Scope-local bounded text slices from a single parsed document. */
    fun normalizeText(values: List<String>): List<String> {
        require(values.size in 1..10201)
        return boundedBatch(values, ::normalizeBatch)
    }

    private fun normalizeBatch(values: List<String>): List<String> {
        val input = ByteArrayOutputStream()
        val writer = DataOutputStream(input)
        writer.write(textMagic)
        writer.writeInt(Integer.reverseBytes(values.size))
        for (value in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_FRAME - writer.size() - 4) throw FrameLimit()
            writer.writeInt(Integer.reverseBytes(bytes.size))
            writer.write(bytes)
        }
        return exchange(input.toByteArray()) { response ->
            val frame = response.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
            if (frame.remaining() < 8 || textMagic.any { frame.get() != it } || frame.int != values.size) invalid()
            val decoder = Charsets.UTF_8.newDecoder()
            List(values.size) { index ->
                readText(frame, decoder, values[index].length)
            }.also { if (frame.hasRemaining()) invalid() }
        }
    }

    private class FrameLimit : IOException()

    private fun <I, O> boundedBatch(input: List<I>, run: (List<I>) -> List<O>): List<O> =
        try {
            run(input)
        } catch (_: FrameLimit) {
            if (input.size == 1) throw NativeContentException("Onionall content projection exceeds its frame limit")
            val midpoint = input.size / 2
            boundedBatch(input.subList(0, midpoint), run) + boundedBatch(input.subList(midpoint, input.size), run)
        }

    private class Projection(private val writer: DataOutputStream, private val base: String) {
        // Resolved links can be much larger than hrefs when a long HTML base
        // repeats. Reserve room for the visible text and structural tokens.
        private var linkBytes = MAX_FRAME / 2
        private fun hidden(node: Element): Boolean =
            node.normalName() in removed || node.hasAttr("hidden") || node.attr("aria-hidden") == "true"

        private fun codeText(element: Element): String = buildString {
            element.filter(object : NodeFilter {
                override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                    if (node is Element && hidden(node)) return NodeFilter.FilterResult.SKIP_ENTIRELY
                    if (node is TextNode) append(node.wholeText)
                    if (node is Element && node.normalName() == "br") append('\n')
                    return NodeFilter.FilterResult.CONTINUE
                }
            })
        }

        private fun tag(value: Char) {
            if (writer.size() >= MAX_FRAME) throw FrameLimit()
            writer.writeByte(value.code)
        }

        private fun leaf(kind: Char, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_FRAME - writer.size() - 5) {
                throw FrameLimit()
            }
            tag(kind)
            writer.writeInt(Integer.reverseBytes(bytes.size))
            writer.write(bytes)
        }

        private fun link(raw: String): String? = try {
            val target = UriReferences.resolve(base, raw.trim())
            if ((target.scheme != "http" && target.scheme != "https") || target.host == null ||
                target.rawUserInfo != null || raw.any { Character.isISOControl(it) }) null
            else target.toASCIIString().takeIf { it.length <= 2048 }
        } catch (_: Exception) { null }

        fun node(node: Node, depth: Int, ordinal: Long?) {
            if (depth > 128) {
                tag('x')
                return
            }
            if (node is TextNode) {
                leaf('t', node.wholeText)
                return
            }
            if (node !is Element) return
            val name = node.normalName()
            if (hidden(node)) return
            when (name) {
                "pre", "code" -> return leaf(if (name == "pre") 'p' else 'c', codeText(node))
                "img" -> return leaf('j', node.attr("alt").ifBlank { "[image omitted]" })
                "br" -> return tag('n')
                "hr" -> return tag('r')
                "a" -> {
                    val url = node.attr("href").takeIf { node.hasAttr("href") }?.let(::link)
                    if (url == null) {
                        tag('N')
                    } else if (url.length <= linkBytes) {
                        linkBytes -= url.length
                        leaf('a', url)
                    } else {
                        tag('N')
                        tag('f') // Preserve text while forcing the inert bounded representation.
                    }
                }
                "li" -> {
                    if (ordinal == null) tag('l') else leaf('o', ordinal.toString())
                }
                else -> tag(when (name) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> name.last()
                    "blockquote" -> 'q'
                    "b", "strong" -> 's'
                    "em", "i" -> 'e'
                    "table" -> 'T'
                    "tr" -> 'R'
                    "th" -> 'H'
                    "td" -> 'D'
                    "caption" -> 'C'
                    else -> if (node.isBlock) 'B' else 'N'
                })
            }
            var nextOrdinal = if (name == "ol") (node.attr("start").toIntOrNull() ?: 1).toLong() else null
            for (i in 0 until node.childNodeSize()) {
                val child = node.childNode(i)
                val observedOrdinal = if (nextOrdinal != null && child is Element && child.normalName() == "li") {
                    val current = nextOrdinal
                    nextOrdinal = current + 1
                    child.attr("value").toIntOrNull()?.toLong() ?: current
                } else null
                node(child, depth + 1, observedOrdinal)
            }
            tag('\u0000')
        }
    }

    private fun <T> exchange(input: ByteArray, decode: (MemorySegment) -> T): T =
        Arena.ofConfined().use { arena ->
            val source = arena.allocate(input.size.toLong())
            source.copyFrom(MemorySegment.ofArray(input))
            var destination = arena.allocate((input.size * 4).coerceIn(256, MAX_FRAME).toLong())
            val render = binding.getOrElse {
                throw NativeContentException("Required Onionall content library is unavailable or incompatible", it)
            }
            fun invoke(): Int {
                val size = render.invoke(source, input.size, destination, destination.byteSize().toInt()) as Int
                if (size == -4) throw FrameLimit()
                if (size !in 5..MAX_FRAME) throw NativeContentException(when (size) {
                    -1 -> "Onionall content library rejected the document frame"
                    -2 -> "Incompatible Onionall content protocol version"
                    -3 -> "Onionall content library failed to render the document"
                    else -> "Invalid Onionall content response size"
                })
                return size
            }
            val size = invoke()
            if (size > destination.byteSize()) {
                destination = arena.allocate(size.toLong())
                if (invoke() != size) throw NativeContentException("Inconsistent Onionall content response size")
            }
            decode(destination.asSlice(0, size.toLong()))
        }

    private fun invalid(): Nothing = throw NativeContentException("Invalid Onionall content response")

    private fun readText(frame: java.nio.ByteBuffer, decoder: java.nio.charset.CharsetDecoder, limit: Int): String {
        if (frame.remaining() < 4) invalid()
        val size = frame.int
        if (size !in 0..frame.remaining() || size > limit * 3) invalid()
        val text = try {
            decoder.decode(frame.slice(frame.position(), size)).toString()
        } catch (_: CharacterCodingException) { invalid() }
        frame.position(frame.position() + size)
        if (text.length > limit) invalid()
        return text
    }

    private fun decodeMarkdown(response: MemorySegment, count: Int, limit: Int): List<Rendered> {
        val frame = response.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
        if (magic.any { frame.get() != it } || frame.get().toInt() != count) invalid()
        val decoder = Charsets.UTF_8.newDecoder()
        val rendered = ArrayList<Rendered>(count)
        repeat(count) {
            if (frame.remaining() < 5) invalid()
            val flag = frame.get().toInt()
            if (flag !in 0..1) invalid()
            rendered.add(Rendered(readText(frame, decoder, limit), flag == 1))
        }
        if (frame.hasRemaining()) invalid()
        return rendered
    }
}

internal class NativeContentException(message: String, cause: Throwable? = null) : IOException(message, cause)
