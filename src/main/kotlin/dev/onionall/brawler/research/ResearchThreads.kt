package dev.onionall.brawler.research

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.file.Path
import java.util.UUID

/**
 * Агрегатор форумных дискуссий из проверенных архивных HTML snapshots.
 *
 * Объединяет страницы, принадлежащие одной discussion identity,
 * извлекает сообщения и сохраняет происхождение каждого наблюдения.
 *
 * Основные задачи:
 * - группировка архивных страниц по идентичности темы;
 * - сохранение metadata страниц и snapshots;
 * - упорядочивание страниц по совместимым счётчикам пагинации;
 * - объединение повторных наблюдений одного сообщения;
 * - обнаружение конфликтующих source post IDs;
 * - сохранение наблюдаемых pagination links;
 * - привязка файловых кандидатов к сообщениям форума.
 *
 * Internal post IDs не являются идентификаторами сообщений
 * на исходном форуме. Они вычисляются отдельно от source_post_id.
 *
 * Для объединения сообщений недостаточно совпадения
 * наблюдаемого идентификатора: дополнительно сравнивается
 * содержательная семантика сообщения.
 *
 * Компонент работает только с проверенным архивным HTML.
 * Сетевые запросы, запуск crawler и загрузка вложений
 * здесь не выполняются.
 *
 * Статусы полноты описывают только наблюдаемые архивные
 * страницы и ссылки. Они не доказывают, что получена
 * вся дискуссия на исходном сайте.
 *
 * @see ForumPagination
 * @see ForumExtractor
 * @see ResearchFiles
 * @see ResearchWorkflow
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
object ResearchThreads {

    /**
     * Максимальное число сохраняемых наблюдений сообщений
     * за один вызов extract().
     *
     * Лимит общий для всех обрабатываемых дискуссий,
     * а не отдельный для каждой темы.
     */
    private const val MAX_POSTS = 1000

    /**
     * Максимальный суммарный размер сериализованных
     * сохраняемых сообщений и их provenance.
     *
     * Бюджет общий для всех дискуссий одного вызова extract().
     */
    private const val MAX_BYTES = 1572864

    /**
     * JSON-сериализатор для оценки размера сообщений.
     *
     * Используется для контроля объёма результирующих
     * post records, а не для записи файлов архива.
     */
    private val json = ObjectMapper()

    /**
     * Извлекает и агрегирует форумные дискуссии из архива.
     *
     * Для каждой страницы:
     * 1. Проверяет принадлежность явно выбранной теме,
     *    если сессия находится в режиме THREAD.
     * 2. Читает HTML через [ResearchFiles.verifiedHtml].
     * 3. Извлекает содержимое через [ForumExtractor].
     * 4. Определяет discussion identity.
     * 5. Собирает наблюдаемые ссылки пагинации.
     *
     * После группировки:
     * - исключает группы без доказанных сообщений,
     *   кроме явно выбранной THREAD-дискуссии;
     * - ограничивает результат первыми 100 группами;
     * - определяет допустимый порядок страниц;
     * - агрегирует сообщения с учётом provenance;
     * - фиксирует конфликты наблюдаемых post IDs;
     * - формирует статусы извлечения и покрытия.
     *
     * Если в URL используются разные типы pagination counters,
     * исходный порядок captures сохраняется.
     *
     * Сообщения с одинаковым уникальным source_post_id
     * объединяются только при совпадении их семантики.
     * При конфликте создаётся отдельное наблюдение.
     *
     * @param session сохранённый документ Research Session.
     * @param details подробные metadata архивных страниц.
     * @param root корневой каталог архивного хранилища.
     * @return список агрегированных дискуссий.
     */
    fun extract(
        session: Map<String, Any?>,
        details: List<Map<String, Any?>>,
        root: Path
    ): List<Map<String, Any?>> = aggregate(session, details.mapNotNull { page ->
        val source = page["canonical_url"] as String
        if (!includes(session, source)) return@mapNotNull null
        val html = ResearchFiles.verifiedHtml(page, root)
        page to (ForumExtractor.extract(source, html) + mapOf("pagination" to ForumPagination.observations(source, html)))
    })

    private fun explicitSeed(session: Map<String, Any?>): String? =
        (session["thread_seed_url"] as? String)?.takeIf { session["mode"] == "THREAD" }

    internal fun includes(session: Map<String, Any?>, source: String): Boolean =
        explicitSeed(session)?.let { ForumPagination.identity(source) == ForumPagination.identity(it) } ?: true

    internal fun aggregate(
        session: Map<String, Any?>,
        captures: List<Pair<Map<String, Any?>, Map<String, Any?>>>
    ): List<Map<String, Any?>> {
        val explicit = explicitSeed(session)

        // Сохраняем порядок первого появления дискуссий.
        //
        // Каждая запись группы содержит metadata страницы
        // и результат её HTML-извлечения.
        val groups = linkedMapOf<
                String,
                MutableList<
                        Pair<Map<String, Any?>, Map<String, Any?>>
                        >
                >()

        for ((page, parsed) in captures) {

            val source = page["canonical_url"] as String

            val identity = ForumPagination.identity(
                explicit ?: source
            )

            groups.getOrPut(identity) {
                mutableListOf()
            }.add(page to parsed)
        }

        // Глобальные бюджеты одного вызова extract().
        // Они не обнуляются при переходе к следующей теме.
        var used = 0
        var count = 0

        // Сначала устанавливаем discussion identity.
        // Затем допускаем generic/dynamic captures той же темы.
        //
        // Обычные home/category pages без доказанных
        // границ сообщений не становятся дискуссиями.
        val identified = groups.filterValues { captures ->
            explicit != null ||
                    captures.any {
                        ResearchWorkflow.maps(
                            it.second["posts"]
                        ).isNotEmpty()
                    }
        }

        return identified.entries.take(100).map {
                (identity, observations) ->

            // Определяем все виды pagination counters,
            // наблюдаемые в URL архивных страниц.
            val kinds = observations
                .flatMap {
                    ForumPagination.counterKinds(
                        it.first["canonical_url"] as String
                    )
                }
                .toSet()

            // page, start и offset не образуют общую шкалу.
            val ambiguousOrder = kinds.size > 1

            // Сортировка по числовой позиции допустима
            // только при одном известном типе счётчика.
            //
            // Иначе сохраняется исходный порядок captures.
            val captures =
                if (ambiguousOrder || kinds.isEmpty()) {
                    observations
                } else {
                    observations.sortedBy {
                        ForumPagination.position(
                            it.first["canonical_url"] as String
                        )
                    }
                }

            // Внутренний идентификатор темы детерминирован
            // парой session ID + discussion identity.
            val threadId = identifier(
                "${session["session_id"]}|$identity"
            )

            // Сохраняем provenance каждой архивной страницы.
            val pages = captures.map { (page, parsed) ->
                mapOf(
                    "page_id" to page["page_id"],
                    "snapshot_id" to page["snapshot_id"],
                    "url" to page["canonical_url"],
                    "fetched_at" to page["fetched_at"],
                    "recorded_at" to page["recorded_at"],
                    "source_kind" to page["source_kind"],
                    "job_id" to page["job_id"],
                    "extraction_status" to
                            parsed["extraction_status"],
                    "extraction_method" to
                            parsed["extraction_method"],
                    "page_text_truncated" to
                            (parsed["evidence"] as? Map<*, *>)
                                ?.get("page_text_truncated"),
                    "limitations" to parsed["limitations"]
                )
            }

            // Набор канонических URL реально захваченных
            // архивных страниц данной группы.
            val captured = pages
                .map {
                    ForumPagination.canonical(
                        it["url"] as String
                    )
                }
                .toSet()

            // Ключи сообщений позволяют объединять
            // повторные наблюдения при достаточных доказательствах.
            val posts = linkedMapOf<
                    String,
                    Map<String, Any?>
                    >()

            val conflicts = mutableListOf<String>()
            var limited = false

            for ((page, parsed) in captures) {
                for (
                post in ResearchWorkflow.maps(
                    parsed["posts"]
                )
                ) {

                    // Наблюдаемый идентификатор из HTML форума.
                    // Он может отсутствовать или быть неоднозначным.
                    val observedId =
                        post["source_post_id"] as? String

                    val source =
                        page["canonical_url"] as String

                    // Provenance связывает сообщение с конкретной
                    // архивной страницей, job и snapshot.
                    val provenance = mapOf(
                        "source_page_url" to source,
                        "source_page_id" to page["page_id"],
                        "job_id" to page["job_id"],
                        "snapshot_id" to page["snapshot_id"],
                        "fetched_at" to page["fetched_at"],
                        "recorded_at" to page["recorded_at"],
                        "source_kind" to page["source_kind"]
                    )

                    // Доверяем source_post_id как ключу
                    // объединения только при подтверждённой
                    // уникальности внутри исходной страницы.
                    val unique =
                        (post["evidence"] as? Map<*, *>)
                            ?.get(
                                "source_post_id_unique_on_page"
                            ) == true

                    // Если уникальность не доказана,
                    // используем локальную идентичность
                    // наблюдения: страницу, snapshot и ordinal.
                    val key = observedId
                        ?.takeIf { unique }
                        ?.let { "observed:$it" }
                        ?: "page:$source:${page["snapshot_id"]}:${post["ordinal"]}"

                    val previous = posts[key]

                    // Совпадение ключа и содержательной семантики
                    // позволяет объединить provenance наблюдений.
                    if (
                        previous != null &&
                        !codeTruncated(previous) && !codeTruncated(post) &&
                        semantics(previous) == semantics(post)
                    ) {

                        val updated = previous + mapOf(
                            "provenance" to (
                                    ResearchWorkflow.maps(
                                        previous["provenance"]
                                    ) + provenance
                                    ).distinct()
                        )

                        // При объединении учитываем только
                        // изменение сериализованного размера.
                        val delta =
                            json.writeValueAsBytes(updated).size -
                                    json.writeValueAsBytes(previous).size

                        if (used + delta > MAX_BYTES) {
                            limited = true
                        } else {
                            posts[key] = updated
                            used += delta
                        }

                        continue
                    }

                    // Совпадающий ключ с разной семантикой
                    // фиксируется как конфликт, а не
                    // автоматически объединяется.
                    if (previous != null) {
                        conflicts.add(observedId ?: key)
                    }

                    // При конфликте формируем отдельный ключ,
                    // включающий URL страницы и ordinal.
                    val distinctKey =
                        if (previous == null) {
                            key
                        } else {
                            "$key|$source|${post["ordinal"]}"
                        }

                    // Формируем самостоятельное наблюдение.
                    // Internal ID не подменяет source_post_id.
                    val value = post + provenance + mapOf(
                        "post_id" to identifier(
                            "$threadId|$distinctKey"
                        ),
                        "post_id_basis" to
                                "INTERNAL_OBSERVATION_ID",
                        "thread_id" to threadId,
                        "provenance" to listOf(provenance)
                    )

                    val size =
                        json.writeValueAsBytes(value).size

                    // Ограничения применяются ко всему вызову
                    // extract(), включая предыдущие темы.
                    if (
                        count >= MAX_POSTS ||
                        used + size > MAX_BYTES
                    ) {
                        limited = true
                        continue
                    }

                    posts[distinctKey] = value
                    used += size
                    count++
                }
            }

            // Объединяем наблюдаемые pagination edges,
            // удаляя повторы по паре source/target.
            val pagination = captures
                .flatMap { (_, parsed) ->
                    ResearchWorkflow.maps(
                        parsed["pagination"]
                    )
                }
                .distinctBy {
                    listOf(
                        it["source_url"],
                        it["target_url"]
                    )
                }
                .map { edge ->

                    val target =
                        edge["target_url"] as String

                    // Сопоставляем наблюдаемую ссылку
                    // с сохранёнными link metadata.
                    val persisted = captures
                        .flatMap {
                            ResearchWorkflow.maps(
                                it.first["links"]
                            )
                        }
                        .firstOrNull {
                            it["source_url"] ==
                                    edge["source_url"] &&
                                    it["target_url"] == target
                        }

                    edge + mapOf(
                        // captured означает наличие URL
                        // среди архивных страниц этой темы.
                        "captured" to (
                                target in captured
                                ),

                        // Отсутствие persisted disposition
                        // не означает фактический crawl.
                        "disposition" to (
                                persisted?.get("disposition")
                                    ?: "OBSERVED_ONLY"
                                )
                    )
                }

            // Признаки неполного или неоднозначного извлечения.
            val partial =
                ambiguousOrder ||
                        limited ||
                        conflicts.isNotEmpty() ||
                        captures.any {
                            it.second["extraction_status"] in
                                    setOf("PARTIAL", "FAILED")
                        } ||
                        (
                                posts.isNotEmpty() &&
                                        captures.any {
                                            it.second["extraction_status"] !=
                                                    "STRUCTURED_POSTS"
                                        }
                                )

            // Статус характеризует качество извлечения
            // из имеющихся snapshots, а не полноту сайта.
            val status = when {

                captures.all {
                    it.second["extraction_status"] == "FAILED"
                } -> "FAILED"

                partial -> "PARTIAL"

                posts.isNotEmpty() -> "STRUCTURED_POSTS"

                captures.any {
                    it.second["extraction_status"] ==
                            "UNSUPPORTED_DYNAMIC_CONTENT"
                } -> "UNSUPPORTED_DYNAMIC_CONTENT"

                else -> "PAGE_TEXT_ONLY"
            }

            // Лимит link observations оценивается
            // по сохранённым metadata страниц.
            val truncated = captures.any {
                ResearchWorkflow.maps(
                    it.first["links"]
                ).size >= 100
            }

            // Проверяем покрытие только наблюдаемой пагинации.
            //
            // Даже положительный результат не доказывает,
            // что на исходном сайте нет других страниц.
            val complete =
                pagination.isNotEmpty() &&
                        pagination.all {
                            it["captured"] == true
                        } &&
                        !truncated &&
                        !partial

            mapOf(
                "thread_id" to threadId,
                "source_url" to (
                        explicit ?: pages.first()["url"]
                        ),
                "canonical_thread_identity" to identity,
                "source_host" to URI(identity).host,
                "title" to captures.first().second["title"],

                "captured_pages" to pages,
                "observed_pagination" to pagination,
                "posts" to posts.values.toList(),

                "ordering_status" to
                        if (ambiguousOrder) {
                            "AMBIGUOUS_PAGINATION_COUNTERS"
                        } else if (kinds.isEmpty()) {
                            "CAPTURE_ORDER_ONLY"
                        } else {
                            "VERIFIED_NUMERIC_PAGINATION"
                        },

                "post_order_basis" to
                        if (ambiguousOrder || kinds.isEmpty()) {
                            "CAPTURE_ORDER_AND_DOM_ORDER"
                        } else {
                            "NUMERIC_PAGINATION_AND_DOM_ORDER"
                        },

                "extraction_status" to status,

                "completeness_status" to
                        if (complete) {
                            "OBSERVED_PAGINATION_CAPTURED"
                        } else {
                            "PARTIAL_OR_UNKNOWN"
                        },

                "completeness_basis" to
                        "ARCHIVED_HTML_LINKS_ONLY_NOT_SITE_EXHAUSTION",

                "extraction_limited" to limited,
                "conflicting_source_post_ids" to
                        conflicts.distinct(),

                // Полнота всей дискуссии не подтверждается
                // только наличием архивных pagination links.
                "full_thread_coverage_verified" to false
            )
        }
    }

    /**
     * Связывает файловые кандидаты с наблюдениями сообщений.
     *
     * Для каждого файла ищет сообщения, удовлетворяющие
     * одновременно двум условиям:
     * - provenance сообщения содержит source_page_id файла;
     * - attachments сообщения содержат сопоставимый target URL.
     *
     * Сопоставление URL выполняется через [comparable].
     *
     * Если найдено ровно одно сообщение, переносит его
     * source_post_id в файловый кандидат.
     *
     * При нескольких совпадениях source_post_id остаётся null,
     * но сохраняются internal IDs всех совпавших наблюдений.
     *
     * Метод не скачивает вложения и не подтверждает
     * доступность файлов по найденным адресам.
     *
     * @param files файловые кандидаты из архивных страниц.
     * @param threads агрегированные форумные дискуссии.
     * @return файловые кандидаты с привязкой к сообщениям.
     */
    fun attachPostSources(
        files: List<Map<String, Any?>>,
        threads: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        class Match {
            var seen = false
            var sourceId: Any? = null
            val observations = linkedSetOf<Any?>()

            fun add(post: Map<String, Any?>) {
                sourceId = if (seen) null else post["source_post_id"]
                seen = true
                observations += post["post_id"]
            }
        }

        val index = HashMap<Any?, MutableMap<String, Match>>()
        val matches = files.map { file ->
            comparable(file["target_url"])?.let { target ->
                index.getOrPut(file["source_page_id"]) { HashMap() }.getOrPut(target) { Match() }
            }
        }
        if (index.isNotEmpty()) for (thread in threads) {
            for (post in ResearchWorkflow.maps(thread["posts"])) {
                val pages = ResearchWorkflow.maps(post["provenance"]).map { it["source_page_id"] }
                    .distinct().mapNotNull(index::get)
                if (pages.isEmpty()) continue
                val targets = ResearchWorkflow.maps(post["attachments"]).mapNotNull { comparable(it["target_url"]) }.toSet()
                for (page in pages) for (target in targets) page[target]?.add(post)
            }
        }
        return files.mapIndexed { i, file ->
            val match = matches[i]
            file + mapOf(
                "source_post_id" to match?.sourceId,
                "source_post_observation_ids" to match?.observations?.toList().orEmpty()
            )
        }
    }

    /**
     * Формирует детерминированный внутренний UUID.
     *
     * Использует UUID.nameUUIDFromBytes() и UTF-8.
     * При одинаковой входной строке возвращает
     * одинаковый идентификатор.
     *
     * Это UUID версии 3, а не случайный UUID.
     *
     * @param value строковая основа идентификатора.
     * @return внутренний UUID в строковом представлении.
     */
    private fun identifier(value: String) =
        UUID.nameUUIDFromBytes(
            value.toByteArray(Charsets.UTF_8)
        ).toString()

    /**
     * Приводит URL вложения к сопоставимому виду.
     *
     * Проверяет возможность разбора URI, отсутствие
     * userinfo и применяет общую onion canonicalization.
     *
     * Scheme и host предварительно приводятся
     * к нижнему регистру.
     *
     * При невозможности нормализации возвращает null.
     *
     * Метод не выполняет DNS lookup или сетевой запрос.
     *
     * @param value предполагаемый URL вложения.
     * @return канонический URL либо null.
     */
    private fun comparable(value: Any?): String? = try {

        val uri = URI(value as String)

        require(uri.rawUserInfo == null)

        ResearchWorkflow.canonical(
            "${uri.scheme.lowercase()}://${uri.host.lowercase()}" +
                    (
                            if (uri.port == -1) {
                                ""
                            } else {
                                ":${uri.port}"
                            }
                            ) +
                    uri.rawPath +
                    uri.rawQuery
                        ?.let { "?$it" }
                        .orEmpty()
        )

    } catch (_: Exception) {
        null
    }

    /** Нельзя подтвердить совпадение кода по одному усечённому префиксу. */
    private fun codeTruncated(post: Map<String, Any?>): Boolean =
        (post["evidence"] as? Map<*, *>)?.get("code_blocks_truncated") == true

    /**
     * Формирует содержательную сигнатуру сообщения.
     *
     * Используется для проверки эквивалентности
     * повторных наблюдений с одинаковым ключом.
     *
     * Учитывает:
     * - автора, дату публикации и наблюдаемую дату изменения;
     * - основной и цитируемый текст;
     * - структуру цитат и наблюдаемый буквальный код;
     * - наблюдаемые ссылки и их атрибуты;
     * - вложения и их описательные metadata.
     *
     * Provenance, internal IDs и source_post_id
     * в эту сигнатуру не включаются.
     *
     * Это сравнение наблюдаемых данных, а не
     * доказательство идентичности сообщений на сайте.
     *
     * @param post извлечённое сообщение форума.
     * @return упорядоченная сигнатура его содержимого.
     */
    private fun semantics(
        post: Map<String, Any?>
    ): List<Any?> {

        // Игнорируем элементы, которые не являются Map.
        fun records(value: Any?) =
            (value as? Iterable<*>)
                ?.filterIsInstance<Map<*, *>>()
                ?: emptyList()

        return listOf(
            post["author"],
            post["posted_at"],
            post["modified_at"],
            post["text"],
            post["quoted_text"],
            post["quotes"],
            post["code_blocks"],

            records(post["referenced_links"]).map {
                listOf(
                    it["target_url"],
                    it["text"],
                    it["quoted"],
                    it["download"],
                    it["declared_content_type"]
                )
            },

            records(post["attachments"]).map {
                listOf(
                    it["target_url"],
                    it["filename"],
                    it["extension"],
                    it["declared_content_type"],
                    it["quoted"]
                )
            }
        )
    }
}
