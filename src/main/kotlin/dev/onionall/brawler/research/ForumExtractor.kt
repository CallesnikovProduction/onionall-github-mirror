package dev.onionall.brawler.research

import dev.onionall.brawler.archive.NativeContent
import dev.onionall.brawler.archive.NativeContentException

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
 * Консервативный экстрактор содержимого форумных HTML-страниц.
 *
 * Преобразует уже полученный HTML в структурированное представление:
 * - текст страницы;
 * - сообщения форума;
 * - авторов, даты и исходные DOM-идентификаторы;
 * - цитаты и ссылки;
 * - предполагаемые вложения;
 * - статус извлечения и evidence.
 *
 * Поддерживает два контролируемых DOM-контракта:
 * 1. Традиционные контейнеры `div.post` с `div.postbody`.
 * 2. Семантические `article` с `schema.org/DiscussionForumPosting`.
 *
 * Для неизвестных структур используется fallback на читаемый
 * текст страницы без создания искусственных сообщений.
 *
 * Границы ответственности:
 * - не выполняет сетевые запросы;
 * - не исполняет JavaScript;
 * - не поддерживает произвольные forum engines;
 * - не проверяет существование обнаруженных вложений;
 * - не придумывает отсутствующие author/date/post ID;
 * - не подтверждает полноту извлечённой дискуссии.
 *
 * Входной HTML рассматривается как недоверенное содержимое.
 * Проверка архивного snapshot и его provenance выполняется
 * вызывающим компонентом Research.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
object ForumExtractor {

    /** Максимальный размер входного HTML: 128 KiB. */
    const val MAX_HTML_BYTES = 131072

    /** Максимальное количество рассматриваемых post-контейнеров. */
    const val MAX_POSTS = 100

    /** Максимальная длина текста одного сообщения или цитаты. */
    const val MAX_POST_TEXT = 16384

    /** Максимальная длина общего текста страницы. */
    const val MAX_PAGE_TEXT = 65536

    /** Максимальное количество ссылок в одном сообщении. */
    private const val MAX_LINKS = 100

    /** Максимальное количество цитат в одном сообщении. */
    private const val MAX_QUOTES = 100

    /** Бюджет кода общий для основного сообщения и его цитат. */
    private const val MAX_CODE_BLOCKS = 32
    private const val CODE_SELECTOR = "pre > code"

    /** Поддерживаемый schema.org тип форумного сообщения. */
    private const val SCHEMA =
        "https://schema.org/DiscussionForumPosting"

    /** Идентификатор традиционного DOM-контракта. */
    private const val TRADITIONAL = "CONTROLLED_POST_BODY_V1"

    /** Идентификатор семантического DOM-контракта. */
    private const val SEMANTIC = "SCHEMA_DISCUSSION_ARTICLE_V1"

    /** Маркер недоверенного происхождения извлечённого текста. */
    private const val TRUST =
        "UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS"

    // Элементы, содержимое которых исключается из readable text.
    private val removed = setOf(
        "script", "style", "noscript", "template",
        "iframe", "object", "embed", "svg", "canvas"
    )
    private val chrome = setOf("nav", "header", "footer", "aside", "form", "button")

    // Расширения потенциальных документных вложений.
    // Наличие расширения не подтверждает реальный MIME-тип.
    private val formats = setOf(
        "pdf", "epub", "txt", "md",
        "markdown", "docx", "odt", "rtf"
    )

    // MIME-типы, которые могут быть объявлены в HTML-ссылке.
    // Это подсказки, а не проверенные HTTP Content-Type.
    private val documentMedia = setOf(
        "application/pdf",
        "application/epub+zip",
        "text/plain",
        "text/markdown",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text",
        "application/rtf",
        "text/rtf"
    )

    /**
     * Извлекает структурированное содержимое HTML-страницы.
     *
     * Сначала проверяет формат исходного URL и размер HTML,
     * затем разбирает документ через JSoup.
     *
     * При неподдерживаемой структуре HTML возвращает
     * page_text и пустой список posts, если текст доступен.
     *
     * При ошибке входных данных или извлечения возвращает
     * результат со статусом FAILED и причиной в limitations.
     *
     * @param url исходный URL страницы.
     * @param html байты HTML, уже полученные другим компонентом.
     *
     * @return map с результатами извлечения, статусом,
     *   ограничениями и доступными evidence.
     */
    @JvmStatic fun extract(
        url: String,
        html: ByteArray
    ): Map<String, Any?> = capture(url, html).result

    /** DOM roots are scoped to one verified capture and are never persisted. */
    internal data class Capture(
        val result: Map<String, Any?>,
        val document: Document? = null,
        val roots: Map<Int, Pair<Element, Element>> = emptyMap(),
        val idCounts: Map<String, Int> = emptyMap()
    )

    internal fun capture(
        url: String,
        html: ByteArray,
        includeRoots: Boolean = false,
        document: Document? = null
    ): Capture {

        // Проверяем синтаксис source URL до разбора HTML.
        val source = safeSource(url)
            ?: return Capture(failed("INVALID_SOURCE_URL"))

        // Ограничиваем размер входа для bounded extraction.
        if (html.isEmpty() || html.size > MAX_HTML_BYTES) {
            return Capture(failed("HTML_SIZE_LIMIT", source))
        }

        return try {
            parse(
                source,
                document ?: Jsoup.parse(
                    ByteArrayInputStream(html),
                    null,
                    source
                ),
                includeRoots
            )
        } catch (failure: NativeContentException) {
            throw failure
        } catch (_: Exception) {

            // Не возвращаем частично сформированный результат
            // после необработанной ошибки извлечения.
            Capture(failed("HTML_EXTRACTION_FAILED", source))
        }
    }

    /**
     * Анализирует DOM и формирует результат извлечения страницы.
     *
     * Определяет потенциальные контейнеры сообщений,
     * распознаёт поддерживаемые layouts, извлекает posts
     * и рассчитывает общий extraction_status.
     *
     * Отдельно сохраняет evidence, позволяющие отличить
     * полноценное структурное извлечение от частичного.
     *
     * @param source проверенный URL исходной страницы.
     * @param document DOM, созданный JSoup.
     *
     * @return структурированное представление страницы.
     */
    private fun parse(
        source: String,
        document: Document,
        includeRoots: Boolean
    ): Capture {

        // Общий читаемый текст извлекается независимо
        // от успешности распознавания отдельных сообщений.
        val raw = arrayListOf(pageText(document))
        val title = document.title().take(2048)

        // Рассматриваем только два явно поддерживаемых
        // семейства DOM-контейнеров.
        val rawCandidates = document.select(
            "div.post[id],article[itemscope][itemtype=\"$SCHEMA\"]"
        )
        val candidateSet = rawCandidates.toHashSet()

        // Исключаем вложенные post-контейнеры и контейнеры
        // внутри цитат, чтобы не считать их отдельными постами.
        val candidates = rawCandidates.filter { candidate ->
            candidate.parents().none { ancestor ->
                ancestor.tagName() == "blockquote" ||
                        ancestor in candidateSet
            }
        }

        // Считаем DOM ID по всему документу.
        // Это позволяет обнаружить неоднозначные post ID.
        val idCounts = document.select("[id]")
            .map { it.id() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()

        val posts = mutableListOf<Map<String, Any?>>()
        val roots = if (includeRoots) HashMap<Int, Pair<Element, Element>>() else null
        val limitations = linkedSetOf<String>()
        var unsupported = 0
        val pending = ArrayList<PendingPost>()

        // Лимит применяется к кандидатам до проверки layout.
        // Неподдерживаемый контейнер тоже занимает позицию.
        for (
            (index, container) in candidates.take(MAX_POSTS).withIndex()
        ) {
            val layout = layout(container)

            if (layout == null || hidden(container) || hidden(layout.body)) {
                unsupported++
                continue
            }

            val bodyIndex = raw.size
            raw += readable(layout.body, MAX_POST_TEXT, skipQuotes = true)
            val quoteNodes = layout.body.select("blockquote")
            val selectedQuotes = quoteNodes.take(MAX_QUOTES)
            for (quote in selectedQuotes) raw += readable(quote, MAX_POST_TEXT, skipQuotes = true)
            val authors = layout.authorNodes.filterNot(::hidden)
            if (authors.size == 1) raw += readable(authors.single(), 256)
            pending += PendingPost(container, layout, index + 1, bodyIndex,
                selectedQuotes, quoteNodes.size > MAX_QUOTES, authors.size)
        }
        val normalized = NativeContent.normalizeText(raw.map { it.text })
        val text = raw.mapIndexed { i, draft -> draft.copy(text = normalized[i]) }
        val page = text.first()
        for (candidate in pending) {
            val post = post(source, document, candidate, text, idCounts)

            if (post == null) {
                unsupported++
            } else {
                posts += post
                roots?.put(candidate.ordinal, candidate.container to candidate.layout.body)
            }
        }

        // Фиксируем ограничения без подмены фактических данных.
        if (unsupported > 0) {
            limitations += "UNSUPPORTED_OR_EMPTY_POST_CONTAINERS"
        }

        if (candidates.size > MAX_POSTS) {
            limitations += "POST_LIMIT_REACHED"
        }

        if (page.truncated) {
            limitations += "PAGE_TEXT_TRUNCATED"
        }

        if (posts.any {
                (it["evidence"] as Map<*, *>)["partial"] == true
            }
        ) {
            limitations += "PARTIAL_POST_FIELDS"
        }

        // Отмечаем идентификаторы, которые не уникальны
        // в исходном DOM. Не создаём искусственные ID.
        val duplicates = posts
            .mapNotNull { it["source_post_id"] as? String }
            .distinct()
            .filter { (idCounts[it] ?: 0) > 1 }

        if (duplicates.isNotEmpty()) {
            limitations += "DUPLICATE_DOM_POST_IDS"
        }

        val methods = posts
            .map { it["extraction_method"] as String }
            .distinct()

        // Проверяем признаки динамической страницы только
        // тогда, когда структурных сообщений не найдено.
        val dynamic = posts.isEmpty() &&
                dynamicShell(document, page.text)

        // Статус отражает качество извлечения, а не
        // успешность HTTP-fetch или полноту всей дискуссии.
        val status = when {
            posts.isNotEmpty() && limitations.isEmpty() ->
                "STRUCTURED_POSTS"

            posts.isNotEmpty() ->
                "PARTIAL"

            dynamic ->
                "UNSUPPORTED_DYNAMIC_CONTENT"

            unsupported > 0 && page.text.isNotBlank() ->
                "PARTIAL"

            page.text.isNotBlank() ->
                "PAGE_TEXT_ONLY"

            else ->
                "FAILED"
        }

        if (dynamic) {
            limitations +=
                "STATIC_HTML_DOES_NOT_EXPOSE_DISCUSSION_MESSAGES"
        }

        if (posts.isEmpty() && !dynamic) {
            limitations += "POST_BOUNDARIES_NOT_ESTABLISHED"
        }

        val result = linkedMapOf(
            "source_url" to source,
            "title" to title,
            "page_text" to page.text,
            "extraction_status" to status,

            // Сохраняем конкретный метод, если он единственный.
            // При смешанных layouts указываем отдельный маркер.
            "extraction_method" to when {
                methods.size == 1 -> methods.single()
                methods.size > 1  -> "MIXED_CONTROLLED_DOM_V1"
                dynamic           -> "STATIC_DYNAMIC_SHELL_HEURISTIC_V1"
                else              -> "GENERIC_DOM_BLOCK_TEXT_V1"
            },

            "posts" to posts,
            "content_trust" to TRUST,
            "limitations" to limitations.toList(),

            // Evidence фиксирует наблюдаемое покрытие
            // конкретной страницы, а не всего форума.
            "evidence" to mapOf(
                "post_containers_observed" to candidates.size,
                "posts_extracted" to posts.size,
                "unsupported_containers" to unsupported,
                "post_limit" to MAX_POSTS,
                "page_text_truncated" to page.truncated,
                "duplicate_source_post_ids" to duplicates,
                "engine_compatibility" to "CONTROLLED_DOM_CONTRACTS_ONLY"
            )
        )
        return Capture(result, document, roots.orEmpty(), if (includeRoots) idCounts else emptyMap())
    }

    /**
     * Описание распознанного DOM-layout сообщения.
     *
     * Содержит найденные узлы и текстовые селекторы,
     * используемые для provenance/evidence.
     *
     * @property method идентификатор DOM-контракта.
     * @property body узел с основным содержимым сообщения.
     * @property bodySelector описание расположения body.
     * @property authorNodes кандидаты на автора.
     * @property authorSelector описание расположения автора.
     * @property timeNodes кандидаты на дату публикации.
     * @property timeSelector описание расположения даты.
     */
    private data class Layout(
        val method: String,
        val body: Element,
        val bodySelector: String,
        val authorNodes: List<Element>,
        val authorSelector: String,
        val timeNodes: List<Element>,
        val timeSelector: String
    )

    private data class PendingPost(
        val container: Element,
        val layout: Layout,
        val ordinal: Int,
        val bodyIndex: Int,
        val quoteNodes: List<Element>,
        val quotesTruncated: Boolean,
        val authorCount: Int
    )

    /**
     * Распознаёт один из двух контролируемых DOM-контрактов.
     *
     * Не пытается угадывать произвольную структуру форума.
     * Возвращает null, если обязательные DOM-границы
     * отсутствуют или неоднозначны.
     *
     * Точка расширения:
     * поддержку нового forum engine следует добавлять
     * отдельным явным DOM-контрактом с тестовыми fixtures.
     *
     * @param container потенциальный контейнер сообщения.
     * @return распознанный Layout либо null.
     */
    private fun layout(container: Element): Layout? {

        // Контракт 1: традиционная структура div.post.
        if (
            container.tagName() == "div" &&
            container.hasClass("post")
        ) {

            // Требуем ожидаемый формат DOM ID.
            if (
                !container.id().matches(
                    Regex("(?:p|post-)[0-9]{1,20}")
                )
            ) {
                return null
            }

            // Нужен ровно один непосредственный postbody.
            val body = container.children()
                .filter {
                    it.tagName() == "div" &&
                            it.hasClass("postbody")
                }
                .singleOrNull()
                ?: return null

            // И ровно один непосредственный content.
            val content = body.children()
                .filter {
                    it.tagName() == "div" &&
                            it.hasClass("content")
                }
                .singleOrNull()
                ?: return null

            val headers = body.children()
                .filter {
                    it.tagName() == "p" &&
                            it.hasClass("author")
                }

            return Layout(
                TRADITIONAL,
                content,
                ":scope > div.postbody > div.content",

                headers.flatMap {
                    it.children().filter { node ->
                        node.tagName() == "a" &&
                                node.hasClass("username")
                    }
                },
                ":scope > div.postbody > p.author > a.username",

                headers.flatMap {
                    it.children().filter { node ->
                        node.tagName() == "time" &&
                                node.hasAttr("datetime")
                    }
                },
                ":scope > div.postbody > p.author > time[datetime]"
            )
        }

        // Контракт 2: schema.org DiscussionForumPosting.
        if (
            container.tagName() == "article" &&
            container.hasAttr("itemscope") &&
            container.attr("itemtype") == SCHEMA
        ) {

            val content = container.children()
                .filter {
                    it.tagName() == "div" &&
                            item(it, "text")
                }
                .singleOrNull()
                ?: return null

            val headers = container.children()
                .filter { it.tagName() == "header" }

            val authorNames = headers.flatMap { header ->
                header.children()
                    .filter { item(it, "author") }
                    .flatMap { author ->
                        author.children()
                            .filter { item(it, "name") }
                    }
            }

            return Layout(
                SEMANTIC,
                content,
                ":scope > div[itemprop=text]",
                authorNames,
                ":scope > header > [itemprop=author] > [itemprop=name]",

                headers.flatMap {
                    it.children().filter { node ->
                        node.tagName() == "time" &&
                                item(node, "datePublished") &&
                                node.hasAttr("datetime")
                    }
                },
                ":scope > header > time[itemprop=datePublished][datetime]"
            )
        }

        return null
    }

    /**
     * Извлекает одно сообщение из распознанного DOM-контейнера.
     *
     * Разделяет основной текст и цитаты, извлекает ссылки,
     * предполагаемые вложения, автора и дату публикации.
     *
     * Значения `author` и `posted_at` берутся только из
     * однозначных DOM-узлов. Неизвестные остаются null.
     *
     * @param source URL страницы, содержащей сообщение.
     * @param document исходный DOM страницы.
     * @param candidate DOM-границы и позиции текстовых сегментов.
     * @param normalized нормализованные сегменты всего документа.
     * @param idCounts количество вхождений DOM ID.
     *
     * @return структурированное сообщение или null,
     *   если извлечь содержательное сообщение не удалось.
     */
    private fun post(
        source: String,
        document: Document,
        candidate: PendingPost,
        normalized: List<Text>,
        idCounts: Map<String, Int>
    ): Map<String, Any?>? {
        val (container, layout, ordinal, bodyIndex, quoteNodes, quotesTruncated, authorCount) = candidate
        val text = normalized[bodyIndex]
        val code = codeObservations(layout.body, quoteNodes)
        val quotes = quoteNodes.mapIndexed { index, quote ->
            val value = normalized[bodyIndex + 1 + index]

            mapOf(
                // Количество blockquote-предков до body.
                "depth" to quote.parents()
                    .takeWhile { it !== layout.body }
                    .count { it.tagName() == "blockquote" },

                "text" to value.text,
                "text_truncated" to value.truncated,
                "code_blocks" to code.byContainer[quote].orEmpty(),

                // Citation берём только из явного cite.
                "citation_text" to quote.children()
                    .filter { it.tagName() == "cite" }
                    .singleOrNull()
                    ?.text()
                    ?.take(512)
            )
        }

        // Ссылки извлекаются из оригинального body,
        // поэтому включают и ссылки внутри цитат.
        val links = referencedLinks(
            source,
            document,
            layout.body
        )

        // Используем только реальный DOM ID.
        // Не создаём синтетические идентификаторы.
        val id = container.id().takeIf {
            it.isNotBlank() &&
                    it.length <= 128 &&
                    it.none { character ->
                        character.isWhitespace() ||
                                Character.isISOControl(character)
                    }
        }

        // Вложения — только подсказки из HTML-ссылок.
        // Они не скачиваются и не проверяются по HTTP.
        val attachments = links.values
            .mapNotNull {
                attachment(it, source, id, ordinal)
            }
            .distinctBy { it["target_url"] }

        // Пустой контейнер не считается сообщением.
        if (
            text.text.isBlank() &&
            quotes.all {
                (it["text"] as String).isBlank()
            } &&
            links.values.isEmpty()
        ) {
            return null
        }

        // Скрытые поля не участвуют в определении автора/даты.
        val timeNodes = layout.timeNodes.filterNot(::hidden)

        // Если авторов несколько, singleOrNull вернёт null.
        // Не выбираем первого произвольно.
        val author = if (authorCount == 1) normalized[bodyIndex + 1 + quoteNodes.size] else null

        val rawAuthor = author?.text
            ?.takeIf { it.isNotEmpty() }

        // Дату берём только из атрибута datetime.
        // Не пытаемся интерпретировать текст страницы.
        val rawTime = timeNodes.singleOrNull()
            ?.attr("datetime")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        // Частичность отражает усечение или неоднозначность.
        val partial =
            text.truncated ||
                    quotes.any { it["text_truncated"] == true } ||
                    quotesTruncated ||
                    code.truncated ||
                    links.truncated ||
                    authorCount > 1 ||
                    timeNodes.size > 1 ||
                    author?.truncated == true ||
                    (rawTime?.length ?: 0) > 128

        val duplicate =
            id != null && (idCounts[id] ?: 0) > 1

        return linkedMapOf(
            "source_post_id" to id,
            "ordinal" to ordinal,
            "source_page_url" to source,
            "author" to rawAuthor?.take(256),
            "posted_at" to rawTime?.take(128),
            "text" to text.text,
            "code_blocks" to code.byContainer[layout.body].orEmpty(),

            // Сохраняем и упрощённый список цитат,
            // и полные структурированные quotes.
            "quoted_text" to quotes
                .map { it["text"] as String }
                .filter { it.isNotBlank() },

            "quotes" to quotes,
            "referenced_links" to links.values,
            "attachments" to attachments,

            "extraction_method" to layout.method,
            "confidence" to "DOM_BOUNDARIES_IDENTIFIED",
            "content_trust" to TRUST,

            // Evidence сохраняет фактические DOM-основания,
            // а не предполагаемую достоверность автора или даты.
            "evidence" to linkedMapOf(
                "container_selector" to
                        if (layout.method == TRADITIONAL) {
                            "div.post[id=pDIGITS|post-DIGITS]"
                        } else {
                            "article[itemscope][itemtype=$SCHEMA]"
                        },

                "body_selector" to layout.bodySelector,

                "author_selector" to
                        layout.authorSelector.takeIf {
                            authorCount == 1
                        },

                "time_selector" to
                        layout.timeSelector.takeIf {
                            timeNodes.size == 1
                        },

                "posted_at_basis" to
                        if (rawTime != null) {
                            "RAW_TIME_DATETIME_ATTRIBUTE"
                        } else {
                            null
                        },

                "ambiguous_author" to (authorCount > 1),
                "ambiguous_datetime" to (timeNodes.size > 1),

                "quote_selector" to "blockquote",
                "quote_text_excluded_from_main_text" to true,

                "source_dom_id" to container.id()
                    .takeIf { it.isNotEmpty() }
                    ?.take(128),

                "source_post_id_unique_on_page" to
                        (id != null && !duplicate),

                "duplicate_dom_id" to duplicate,
                "text_truncated" to text.truncated,
                "quotes_truncated" to quotesTruncated,
                "code_block_selector" to CODE_SELECTOR,
                "code_blocks_truncated" to code.truncated,

                "links_truncated" to links.truncated,
                "partial" to partial
            )
        )
    }

    /** Наблюдения кода в основном сообщении и распознанных цитатах. */
    private data class CodeObservations(
        val byContainer: Map<Element, List<Map<String, Any?>>>,
        val truncated: Boolean
    )

    /**
     * Сохраняет исходный текст видимых pre/code после разбора HTML.
     * Код относится к body сообщения или ближайшей распознанной цитате.
     * Пробелы и переносы не проходят через нормализатор. Источник
     * определяется содержащим сообщением и его архивным snapshot.
     *
     * @author Стефан Колесников
     * @author GPT-6.2 Sol
     */
    private fun codeObservations(body: Element, quotes: List<Element>): CodeObservations {
        val byContainer = mutableMapOf<Element, MutableList<Map<String, Any?>>>()
        val recognizedQuotes = quotes.toHashSet()
        var remaining = MAX_POST_TEXT
        var count = 0
        var truncated = false

        for (element in body.select(CODE_SELECTOR)) {
            val path = element.parents().takeWhile { it !== body }
            if (hidden(element) || path.any { it.tagName() in removed || it.tagName() == "code" }) {
                continue
            }
            val quote = path.firstOrNull { it.tagName() == "blockquote" }
            if (quote != null && quote !in recognizedQuotes) continue
            if (count == MAX_CODE_BLOCKS || remaining == 0 || path.size > 256) {
                truncated = true
                break
            }
            val code = readable(element, remaining, skipQuotes = true, preserveLiteral = true)
            if (code.text.isNotEmpty()) {
                count++
                byContainer.getOrPut(quote ?: body) { mutableListOf() }.add(
                    mapOf("ordinal" to count, "text" to code.text, "text_truncated" to code.truncated)
                )
                remaining -= code.text.length
            }
            if (code.truncated) {
                truncated = true
                break
            }
        }
        return CodeObservations(byContainer, truncated)
    }

    /**
     * Результат извлечения ссылок одного сообщения.
     *
     * @property values наблюдаемые ссылки и их metadata.
     * @property truncated признак достижения лимита ссылок.
     */
    private data class Links(
        val values: List<Map<String, Any?>>,
        val truncated: Boolean
    )

    /**
     * Извлекает HTTP(S)-ссылки из тела сообщения.
     *
     * Разрешает относительные href с учётом HTML base[href]
     * через [UriReferences].
     *
     * Ссылки не посещаются, а их наличие не означает
     * разрешение на crawl или загрузку вложений.
     *
     * @param source URL исходной страницы.
     * @param document DOM всей страницы.
     * @param body DOM-узел содержимого сообщения.
     *
     * @return ограниченный список ссылок и флаг усечения.
     */
    private fun referencedLinks(
        source: String,
        document: Document,
        body: Element
    ): Links {

        // Учитываем HTML base[href], если его удаётся разрешить.
        // При ошибке используем URL исходной страницы.
        val base = try {
            UriReferences.resolve(
                source,
                document.selectFirst("base[href]")
                    ?.attr("href")
                    ?.trim() ?: source
            )
        } catch (_: Exception) {
            URI(source)
        }

        val result = mutableListOf<Map<String, Any?>>()
        var truncated = false

        for (anchor in body.select("a[href]")) {

            if (hidden(anchor)) {
                continue
            }

            val raw = anchor.attr("href").trim()

            val target = try {
                val reference = URI(raw)

                // Не допускаем userinfo и явно неподдерживаемые
                // схемы: javascript:, data:, file: и другие.
                if (
                    reference.rawUserInfo != null ||
                    reference.scheme?.let {
                        it !in setOf("http", "https")
                    } == true
                ) {
                    continue
                }

                val resolved = UriReferences.resolve(
                    base.toASCIIString(),
                    raw
                )

                // Здесь проверяется форма URL, но не
                // same-origin authorization и не доступность.
                if (
                    resolved.scheme !in setOf("http", "https") ||
                    resolved.rawUserInfo != null ||
                    resolved.host == null ||
                    resolved.toASCIIString().length > 2048
                ) {
                    continue
                }

                resolved.toASCIIString()

            } catch (_: Exception) {
                continue
            }

            if (result.size >= MAX_LINKS) {
                truncated = true
                break
            }

            result += linkedMapOf(
                "target_url" to target,
                "raw_href" to raw.take(2048),
                "text" to anchor.text().take(512),

                // Отмечаем, находится ли ссылка внутри цитаты.
                "quoted" to anchor.parents()
                    .takeWhile { it !== body }
                    .any { it.tagName() == "blockquote" },

                "download_attribute" to anchor
                    .attr("download")
                    .takeIf { anchor.hasAttr("download") }
                    ?.take(1024),

                "declared_content_type" to anchor
                    .attr("type")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.take(256)
            )
        }

        return Links(result, truncated)
    }

    /**
     * Классифицирует ссылку как предполагаемое вложение.
     *
     * Использует имя файла, расширение и объявленный
     * HTML-атрибут type.
     *
     * Не выполняет HTTP-запросы и не подтверждает:
     * - реальный Content-Type;
     * - Content-Length;
     * - существование ресурса;
     * - успешную загрузку.
     *
     * @param link metadata обнаруженной ссылки.
     * @param source URL страницы сообщения.
     * @param postId исходный DOM ID сообщения, если известен.
     * @param ordinal порядковый номер сообщения.
     *
     * @return metadata предполагаемого вложения либо null.
     */
    private fun attachment(
        link: Map<String, Any?>,
        source: String,
        postId: String?,
        ordinal: Int
    ): Map<String, Any?>? {

        val uri = URI(link["target_url"] as String)

        // Сначала используем download-имя, если оно задано.
        // Иначе берём последний сегмент URL path.
        val name = (link["download_attribute"] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: uri.path.substringAfterLast('/')
                .takeIf { it.isNotBlank() }

        val ext = name
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }

        // Это объявленный в HTML MIME hint, а не HTTP MIME.
        val mime = (link["declared_content_type"] as? String)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)

        // Достаточно совпадения расширения или MIME hint.
        if (ext !in formats && mime !in documentMedia) {
            return null
        }

        val target = uri.toASCIIString().substringBefore('#')

        return linkedMapOf(
            "target_url" to target,
            "source_page_url" to source,
            "source_post_id" to postId,
            "post_ordinal" to ordinal,
            "filename" to name?.take(1024),
            "extension" to ext,
            "declared_content_type" to
                    link["declared_content_type"],

            // Реальные характеристики неизвестны.
            "content_type" to null,
            "content_length" to null,

            "status" to "OBSERVED_LINK",
            "verified_resource" to false,
            "downloaded" to false,
            "quoted" to link["quoted"]
        )
    }

    /**
     * Ограниченный результат извлечения читаемого текста.
     */
    private data class Text(
        val text: String,
        val truncated: Boolean
    )

    /**
     * Элемент стека итеративного обхода DOM.
     *
     * @property node обрабатываемый узел.
     * @property leave true при завершении обработки узла.
     * @property depth глубина обхода DOM.
     */
    private data class Visit(
        val node: Node,
        val leave: Boolean,
        val depth: Int
    )

    /**
     * Преобразует DOM-поддерево в ограниченный читаемый текст.
     *
     * Использует итеративный DFS вместо рекурсивного обхода.
     * Пропускает скрытые и исключённые элементы, сохраняет
     * базовые границы блоков и табличных ячеек.
     *
     * Ограничивает глубину обхода 256 уровнями и длину
     * итогового текста параметром limit.
     *
     * При усечении не оставляет одиночный UTF-16
     * high surrogate на границе результата.
     *
     * @param root корневой DOM-элемент.
     * @param limit максимальная длина результата.
     *
     * @return читаемый текст и признак усечения.
     */
    private fun readable(
        root: Element,
        limit: Int,
        skipQuotes: Boolean = false,
        skipChrome: Boolean = false,
        preserveLiteral: Boolean = false
    ): Text {

        val result = StringBuilder()
        val work = ArrayDeque<Visit>()

        work.add(Visit(root, false, 0))

        var truncated = false

        while (work.isNotEmpty() && result.length <= limit) {
            val visit = work.removeLast()
            val node = visit.node

            // Защита от чрезмерно глубокой DOM-структуры.
            if (visit.depth > 256) {
                truncated = true
                continue
            }

            // Добавляем исходный текст TextNode.
            if (node is TextNode && !visit.leave) {
                val text = node.wholeText
                result.append(text, 0, minOf(text.length, limit - result.length + 1))
            }

            if (node !is Element) {
                continue
            }

            // Исключаем скрипты, стили и скрытые элементы.
            if (
                node.tagName() in removed ||
                skipQuotes && node !== root && node.tagName() == "blockquote" ||
                skipChrome && node.tagName() in chrome ||
                node.hasAttr("hidden") ||
                node.attr("aria-hidden") == "true"
            ) {
                continue
            }

            if (visit.leave) {

                // После блочного элемента добавляем перенос.
                if (
                    !preserveLiteral && (node.isBlock || node.tagName() == "br")
                ) {
                    result.append('\n')
                }

                if (!preserveLiteral && node.tagName() in setOf("td", "th")) {
                    result.append('\t')
                }

            } else {

                // Перед блочным элементом сохраняем
                // читаемую границу текста.
                if (
                    !preserveLiteral && node.isBlock &&
                    result.isNotEmpty() &&
                    result.last() != '\n'
                ) {
                    result.append('\n')
                }

                if (node.tagName() == "br") {
                    result.append('\n')
                }

                // Leave-событие обрабатывается после детей.
                work.add(Visit(node, true, visit.depth))

                // Обратный порядок добавления в стек
                // сохраняет исходный порядок DOM-детей.
                for (child in node.childNodes().asReversed()) {
                    work.add(
                        Visit(
                            child,
                            false,
                            visit.depth + 1
                        )
                    )
                }
            }
        }

        if (work.isNotEmpty() || result.length > limit) {
            truncated = true
        }

        var end = minOf(result.length, limit)

        // Не обрываем UTF-16 surrogate pair посередине.
        if (
            end > 0 &&
            Character.isHighSurrogate(result[end - 1])
        ) {
            end--
        }

        return Text(result.substring(0, end), truncated)
    }

    /**
     * Извлекает общий читаемый текст страницы.
     *
     * Пропускает навигацию, формы, кнопки и служебные блоки
     * при обходе исходного DOM.
     *
     * Если существует ровно один main, использует его.
     * Иначе обрабатывает body.
     *
     * @param document исходный HTML-документ.
     * @return ограниченный текст страницы.
     */
    private fun pageText(document: Document): Text {

        val main = document.select("main").filter { node -> node.parents().none { it.normalName() in chrome } }
            .singleOrNull() ?: document.body()

        return readable(main, MAX_PAGE_TEXT, skipChrome = true)
    }

    /**
     * Эвристически определяет динамическую HTML-оболочку.
     *
     * Использует наличие script, небольшой объём статического
     * текста, признаки SPA root и сообщения о необходимости JS.
     *
     * Не запускает браузер и не исполняет JavaScript.
     * Положительный результат не доказывает наличие
     * извлекаемых сообщений после JS-rendering.
     *
     * @param document исходный DOM.
     * @param text уже извлечённый статический текст страницы.
     *
     * @return true при обнаружении поддерживаемых признаков.
     */
    private fun dynamicShell(
        document: Document,
        text: String
    ): Boolean {

        if (
            document.select("script").isEmpty() ||
            text.length >= 256
        ) {
            return false
        }

        val app = document.select(
            "#app,#root,#__next,[data-reactroot]"
        ).isNotEmpty()

        val label = text + " " +
                document.select("noscript").text()

        return app && text.length < 80 ||
                Regex(
                    "(?i)enable\\s+javascript|javascript\\s+required|requires\\s+javascript"
                ).containsMatchIn(label)
    }

    /**
     * Проверяет базовый синтаксис URL исходной страницы.
     *
     * Требует HTTP(S), host, отсутствие userinfo
     * и длину строки не более 2048 символов.
     *
     * ВАЖНО:
     * Это не проверка onion scope, approval или наличия
     * страницы в архиве. Такие гарантии обеспечивает
     * вызывающий Research-компонент.
     *
     * @param url исходный URL.
     * @return ASCII-представление URI или null.
     */
    private fun safeSource(url: String): String? {
        if (url.length !in 1..2048) {
            return null
        }

        return try {
            val uri = URI(url)

            if (
                uri.scheme !in setOf("http", "https") ||
                uri.host == null ||
                uri.rawUserInfo != null
            ) {
                null
            } else {
                uri.toASCIIString()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Проверяет наличие значения в атрибуте itemprop.
     *
     * Поддерживает несколько whitespace-separated значений.
     */
    private fun item(
        element: Element,
        name: String
    ) = element.attr("itemprop")
        .split(Regex("\\s+"))
        .contains(name)

    /**
     * Проверяет, скрыт ли элемент или любой его предок.
     *
     * Учитывает HTML `hidden` и `aria-hidden="true"`.
     */
    private fun hidden(element: Element): Boolean =
        generateSequence(element) { it.parent() }
            .any {
                it.hasAttr("hidden") ||
                        it.attr("aria-hidden") == "true"
            }

    /**
     * Формирует результат неуспешного извлечения.
     *
     * Не создаёт posts или page_text при ошибке.
     * Причина сохраняется в limitations.
     *
     * @param reason код причины ошибки.
     * @param source URL страницы, если он прошёл проверку.
     *
     * @return результат со статусом FAILED.
     */
    private fun failed(
        reason: String,
        source: String? = null
    ): Map<String, Any?> = linkedMapOf(
        "source_url" to source,
        "title" to "",
        "page_text" to "",
        "posts" to emptyList<Any>(),
        "extraction_status" to "FAILED",
        "extraction_method" to "INPUT_OR_PARSE_FAILURE_V1",
        "limitations" to listOf(reason),
        "content_trust" to TRUST
    )
}
