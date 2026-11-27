package dev.onionall.brawler.research

import java.net.URI
import dev.onionall.brawler.tor.UriReferences

/**
 * Утилиты идентификации и проверки HTML-пагинации форумных тем.
 *
 * Предоставляет отдельную, узкую модель идентичности дискуссии,
 * не изменяя общие правила нормализации URL приложения.
 *
 * Основные возможности:
 * - нормализация известных query-параметров пагинации;
 * - получение идентичности темы без номера страницы;
 * - проверка ссылок на страницы той же дискуссии;
 * - извлечение наблюдаемых pagination-ссылок из HTML;
 * - определение числовой позиции и типа счётчика;
 * - построение конфигурации специального thread crawl.
 *
 * Известные query-параметры переставляются только тогда,
 * когда все имена распознаны, уникальны и значения счётчиков
 * соответствуют ожидаемому числовому формату.
 *
 * Неизвестные, повторяющиеся и неоднозначные параметры
 * не переупорядочиваются этим компонентом.
 *
 * ВАЖНО:
 * Проверка `eligible()` не является Frontier admission.
 * Ограничения количества страниц, глубины, резервирования
 * и пользовательского approval обеспечиваются другими
 * компонентами Research и Frontier.
 *
 * @see ResearchWorkflow
 * @see UriReferences
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
object ForumPagination {

    /**
     * Известные имена query-параметров, участвующие
     * в формировании идентичности форумной темы.
     */
    private val keys = setOf(
        "t", "f", "id", "topic",
        "thread", "page", "start", "offset"
    )

    /**
     * Параметры позиции в пагинации.
     *
     * page обозначает номер страницы, тогда как start
     * и offset обычно обозначают смещение.
     *
     * Эти величины нельзя напрямую сравнивать между собой
     * без дополнительного контекста.
     */
    private val counters = setOf(
        "page", "start", "offset"
    )

    /**
     * Поддерживаемый числовой сегмент пагинации в path.
     *
     * Примеры:
     * /thread/page/2
     * /thread/page/15/
     *
     * Число должно быть положительным и содержать
     * не более шести цифр.
     */
    private val pathPage = Regex("/page/[1-9][0-9]{0,5}/?$")

    /**
     * Формирует канонический URL страницы форума.
     *
     * Сначала применяет общую нормализацию
     * [ResearchWorkflow.canonical].
     *
     * Затем сортирует query-параметры по имени только
     * при выполнении всех условий:
     * - каждый параметр имеет корректную форму key=value;
     * - имена параметров не повторяются;
     * - все имена входят в известный набор;
     * - числовые параметры имеют допустимые значения.
     *
     * В противном случае сохраняет порядок rawQuery,
     * полученный после общей нормализации.
     *
     * Метод не удаляет параметры пагинации.
     * Для идентичности всей темы используется [identity].
     *
     * @param value исходный URL.
     * @return нормализованный URL страницы.
     */
    @JvmStatic fun canonical(value: String): String {

        // Общая нормализация остаётся ответственностью
        // ResearchWorkflow, а не этого компонента.
        val canonical = ResearchWorkflow.canonical(value)
        val uri = URI(canonical)

        val pairs = query(uri)

        // Переставляем параметры только при однозначном
        // и полностью известном наборе query keys.
        val ordered =
            if (pairs != null && known(pairs)) {
                pairs.sortedBy { it.first }
            } else {
                null
            }

        return origin(uri) + uri.rawPath +
                (
                        ordered
                            ?.takeIf { it.isNotEmpty() }
                            ?.joinToString("&", "?") {
                                it.first + "=" + it.second
                            }
                            ?: uri.rawQuery
                                ?.let { "?$it" }
                                .orEmpty()
                )
    }

    /**
     * Вычисляет идентичность форумной темы без её позиции.
     *
     * Сначала нормализует URL через [canonical].
     *
     * Затем:
     * - удаляет завершающий сегмент /page/N, если он подходит
     *   под поддерживаемый числовой шаблон;
     * - нормализует завершающий '/' в path;
     * - удаляет page/start/offset из однозначного известного
     *   набора query-параметров;
     * - сохраняет остальные известные параметры.
     *
     * Если query неоднозначен или содержит неизвестные ключи,
     * его rawQuery сохраняется без удаления счётчиков.
     *
     * Полученная строка служит ключом сравнения тем.
     * Она не заменяет исходный URL страницы.
     *
     * @param value URL страницы дискуссии.
     * @return строковая идентичность темы.
     */
    @JvmStatic fun identity(value: String): String {

        val uri = URI(canonical(value))
        val pairs = query(uri)

        val known = pairs != null && known(pairs)

        // Удаляем только поддерживаемый завершающий
        // числовой сегмент /page/N.
        val stripped =
            if (pathPage.containsMatchIn(uri.rawPath)) {
                uri.rawPath.replace(pathPage, "/")
            } else {
                uri.rawPath
            }

        // Убираем завершающий slash, сохраняя "/" для root.
        val base = stripped
            .trimEnd('/')
            .ifEmpty { "/" }

        // Счётчики можно исключать только при полностью
        // известной и однозначной структуре query.
        val retained =
            if (known) {
                pairs!!.filter {
                    it.first !in counters
                }
            } else {
                null
            }

        return origin(uri) + base +
                (
                        retained
                            ?.takeIf { it.isNotEmpty() }
                            ?.joinToString("&", "?") {
                                it.first + "=" + it.second
                            }
                            ?: if (known) {
                                ""
                            } else {
                                uri.rawQuery
                                    ?.let { "?$it" }
                                    .orEmpty()
                            }
                        )
    }

    /**
     * Формирует специальные параметры thread-only crawl.
     *
     * Применяется только к планам с provider="m61:thread".
     * Для остальных планов возвращает пустую карту.
     *
     * Thread-план обязан содержать:
     * - ровно один seed;
     * - max_pages в диапазоне 1..5;
     * - depth, равный max_pages - 1;
     * - seed в ожидаемой canonical-форме.
     *
     * Полученные параметры предназначены для downstream
     * thread pagination policy.
     *
     * Сам метод не запускает crawler, не создаёт
     * Frontier reservations и не подтверждает план.
     *
     * @param plan конфигурация исследовательского плана.
     * @return thread-specific параметры либо emptyMap().
     *
     * @throws IllegalArgumentException при нарушении
     *   проверяемых ограничений.
     * @throws IllegalStateException при отсутствии seeds.
     */
    @JvmStatic fun configuration(
        plan: Map<String, Any?>
    ): Map<String, Any?> {

        // Обычные research-планы не получают thread policy.
        if (plan["provider"] != "m61:thread") {
            return emptyMap()
        }

        val seeds = plan["seeds"] as? List<*>
            ?: error("Missing thread seed")

        val pages = (plan["max_pages"] as? Number)
            ?.toInt() ?: 0

        // Для N страниц допускается не более N-1
        // переходов по пагинации от исходного seed.
        require(
            seeds.size == 1 &&
                    pages in 1..5 &&
                    (plan["depth"] as? Number)?.toInt() == pages - 1
        ) {
            "Thread requires one seed, 1..5 pages and bounded pagination hops"
        }

        val seed = canonical(seeds.single() as String)

        // Не принимаем seed, который ещё требует
        // дополнительной pagination-нормализации.
        require(seed == seeds.single()) {
            "Thread seed must use canonical pagination identity"
        }

        return mapOf(
            "research.thread.seed" to seed,
            "research.thread.max.pages" to pages
        )
    }

    /**
     * Проверяет допустимость URL как ссылки пагинации той же темы.
     *
     * Для успешной проверки требуются:
     * - совпадение origin кандидата и текущей страницы;
     * - совпадение identity кандидата и исходного seed;
     * - наличие поддерживаемого признака пагинации либо
     *   точное совпадение URL с исходным seed.
     *
     * Поддерживаемые признаки:
     * - числовые query-параметры page/start/offset;
     * - завершающий сегмент /page/N.
     *
     * Метод не проверяет, является ли переход следующим
     * по порядку, и не резервирует страницу во Frontier.
     *
     * Атрибут rel="next" сам по себе не расширяет
     * разрешённый origin или identity темы.
     *
     * @param seed исходный URL thread-плана.
     * @param source URL текущей страницы.
     * @param target предполагаемая ссылка пагинации.
     *
     * @return канонический URL допустимого кандидата
     *   либо null при несовпадении или ошибке.
     */
    @JvmStatic fun eligible(
        seed: String,
        source: String,
        target: String
    ): String? = try {

        val normalized = canonical(target)
        val candidate = URI(normalized)
        val current = URI(canonical(source))
        val start = canonical(seed)

        val pairs = query(candidate)

        // Проверяем наличие числового параметра пагинации.
        // page должен быть >= 1, start/offset допускают 0.
        val numeric = pairs?.any { (key, value) ->
            key in counters &&
                    value.matches(Regex("[0-9]{1,6}")) &&
                    (key != "page" || value.toInt() >= 1)
        } == true

        if (
            origin(candidate) == origin(current) &&
            identity(normalized) == identity(start) &&
            (
                    normalized == start ||
                            numeric ||
                            pathPage.containsMatchIn(candidate.rawPath)
                    )
        ) {
            normalized
        } else {
            null
        }

    } catch (_: Exception) {

        // Некорректные URL или ошибки нормализации
        // не превращаются в допустимые pagination targets.
        null
    }

    /**
     * Извлекает наблюдаемые ссылки пагинации из HTML.
     *
     * Разбирает HTML через JSoup, удаляет base-элементы
     * и рассматривает первые 100 подходящих ссылок.
     *
     * Относительные href разрешаются относительно source,
     * а не произвольного HTML base.
     *
     * Каждая ссылка проходит проверку [eligible].
     * Ссылки на текущую страницу исключаются.
     * Повторяющиеся target_url удаляются.
     *
     * Метод не загружает найденные страницы.
     *
     * @param source URL HTML-страницы.
     * @param html байты HTML-документа.
     * @return наблюдаемые pagination-ссылки с evidence.
     */
    @JvmStatic fun observations(
        source: String,
        html: ByteArray
    ): List<Map<String, Any?>> {

        // Здесь явно используется UTF-8.
        val doc = org.jsoup.Jsoup.parse(
            html.toString(Charsets.UTF_8),
            source
        )
        return observations(source, doc)
    }

    internal fun observations(source: String, doc: org.jsoup.nodes.Document): List<Map<String, Any?>> {
        // Для pagination используется source, а не base из документа.
        // DOM остаётся доступен другим потребителям снимка.
        // Лимит применяется к исходным DOM-элементам
        // до проверки их пригодности.
        return doc
            .select("a[href],link[rel=next][href]")
            .take(100)
            .mapNotNull { anchor ->

                val raw = anchor.attr("href")

                if (raw.length > 2048) {
                    return@mapNotNull null
                }

                val target = try {
                    UriReferences.resolve(
                        source,
                        raw
                    ).toString()
                } catch (_: Exception) {
                    return@mapNotNull null
                }

                // В этом сценарии source используется
                // одновременно как seed и current page.
                val permitted = eligible(
                    source,
                    source,
                    target
                ) ?: return@mapNotNull null

                // Не включаем саму текущую страницу.
                if (permitted == canonical(source)) {
                    return@mapNotNull null
                }

                mapOf(
                    "source_url" to source,
                    "target_url" to permitted,
                    "rel" to anchor.attr("rel"),
                    "evidence" to
                            "SAME_THREAD_NUMERIC_PAGINATION_ANCHOR"
                )
            }
            .distinctBy { it["target_url"] }
    }

    /**
     * Извлекает числовую позицию из URL пагинации.
     *
     * Приоритет:
     * 1. Первый числовой query-параметр из
     *    page/start/offset в порядке появления.
     * 2. Завершающий числовой сегмент /page/N.
     * 3. Значение 0, если позиция не определена.
     *
     * ВАЖНО:
     * Результат не содержит единиц измерения.
     * Для интерпретации необходимо использовать
     * [counterKinds].
     *
     * @param value URL страницы.
     * @return обнаруженная числовая позиция или 0.
     */
    @JvmStatic fun position(value: String): Int {

        val uri = URI(canonical(value))

        val queryCounter = query(uri)
            ?.firstOrNull {
                it.first in counters &&
                        it.second.matches(Regex("[0-9]{1,6}"))
            }
            ?.second

        return queryCounter?.toInt()
            ?: pathPage.find(uri.rawPath)
                ?.value
                ?.trim('/')
                ?.substringAfterLast('/')
                ?.toInt()
            ?: 0
    }

    /**
     * Определяет типы числовых счётчиков пагинации.
     *
     * Возвращает названия обнаруженных параметров:
     * page, start, offset.
     *
     * Если URL содержит поддерживаемый сегмент /page/N,
     * добавляет тип page.
     *
     * Номер страницы и offset/start имеют разные единицы.
     * Без известного размера страницы их нельзя корректно
     * сравнивать или сортировать как одну шкалу.
     *
     * @param value URL страницы пагинации.
     * @return множество типов обнаруженных счётчиков.
     */
    @JvmStatic fun counterKinds(value: String): Set<String> {

        val uri = URI(canonical(value))

        val kinds = query(uri)
            .orEmpty()
            .filter {
                it.first in counters &&
                        it.second.matches(Regex("[0-9]{1,6}"))
            }
            .map { it.first }
            .toMutableSet()

        if (pathPage.containsMatchIn(uri.rawPath)) {
            kinds.add("page")
        }

        return kinds
    }

    /**
     * Разбирает rawQuery в список пар key=value.
     *
     * Значения остаются в исходном encoded-представлении.
     * URL decoding здесь не выполняется.
     *
     * Возвращает null, если:
     * - обнаружен параметр без знака '=';
     * - имя параметра пустое или blank;
     * - имена параметров повторяются.
     *
     * Отсутствие query представляется пустым списком.
     *
     * @param uri URL для разбора query.
     * @return список пар или null при неоднозначной форме.
     */
    private fun query(
        uri: URI
    ): List<Pair<String, String>>? {

        val raw = uri.rawQuery
            ?: return emptyList()

        val pairs = raw.split('&').map { token ->

            // Разделяем только по первому '='.
            // Остальная часть относится к значению.
            val parts = token.split('=', limit = 2)

            if (
                parts.size != 2 ||
                parts[0].isBlank()
            ) {
                return null
            }

            parts[0] to parts[1]
        }

        // Повторяющиеся имена не допускают
        // однозначного определения pagination identity.
        return pairs.takeIf {
            it.map { pair -> pair.first }
                .distinct()
                .size == it.size
        }
    }

    /**
     * Формирует строковое представление origin.
     *
     * Использует scheme и host.
     *
     * ВАЖНО:
     * Текущая реализация не включает явный port.
     * Корректность этого представления зависит от
     * ограничений общей canonicalization.
     *
     * @param uri исходный URI.
     * @return строка вида scheme://host.
     */
    private fun origin(uri: URI) =
        "${uri.scheme}://${uri.host}"

    /**
     * Проверяет, что все query-параметры известны
     * и числовые счётчики имеют допустимый формат.
     *
     * page должен быть положительным.
     * start/offset допускают нулевое значение.
     *
     * Проверка уникальности имён выполняется отдельно
     * в [query].
     *
     * @param pairs разобранные query-параметры.
     * @return true, если все пары соответствуют контракту.
     */
    private fun known(
        pairs: List<Pair<String, String>>
    ) = pairs.all {
        it.first in keys &&
                (
                        it.first !in counters ||
                                (
                                        it.second.matches(
                                            Regex("[0-9]{1,6}")
                                        ) &&
                                                (
                                                        it.first != "page" ||
                                                                it.second.toInt() >= 1
                                                        )
                                        )
                        )
    }
}
