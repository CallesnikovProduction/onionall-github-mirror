package dev.onionall.brawler.discovery

import dev.onionall.brawler.tor.TorPolicy
import java.net.URI
import java.io.IOException
import java.time.Instant

/**
 * Локальный планировщик обнаружения URL в рамках одной crawl-сессии.
 *
 * Получает исходную страницу и список найденных на ней ссылок,
 * нормализует адреса через [TorPolicy] и принимает решение
 * о дальнейшей обработке каждого URL.
 *
 * Поддерживаемые решения:
 * - `ADMITTED` — новый URL допущен в локальный бюджет.
 * - `DUPLICATE` — нормализованный URL уже встречался.
 * - `REJECTED` — URL не удалось корректно обработать.
 * - `METADATA_ONLY` — URL сохраняется как обнаруженный ресурс,
 *   но не допускается к HTML-обходу.
 * - `DEPTH_LIMIT` — достигнута максимальная глубина.
 * - `PAGE_LIMIT` — исчерпан бюджет уникальных допущенных URL.
 *
 * Ограничения:
 * - не выполняет сетевых запросов;
 * - хранит состояние исключительно в памяти экземпляра;
 * - не гарантирует сохранность бюджета после перезапуска;
 * - не координирует несколько экземпляров или workers;
 * - не заменяет durable admission через URLFrontier.
 *
 * @param config конфигурация, передаваемая в [TorPolicy].
 * @property maxPages максимальное количество уникальных URL,
 *   включая исходные страницы, в локальном наборе admitted.
 * @property maxDepth максимальная разрешённая глубина обхода.
 * @property maxLinks количество первых ссылок, рассматриваемых
 *   за один вызов [discover].
 *
 * @see TorPolicy
 * @see DiscoveryParseFilter
 * @author Stephan Kolesnikov
 * @author GPT-6 Astra
 */
class DiscoveryPlanner(
    config: Map<String, Any>,
    private val maxPages: Int,
    private val maxDepth: Int,
    private val maxLinks: Int
) {

    /**
     * Политика нормализации и проверки URL.
     *
     * Правила допустимых адресов находятся в TorPolicy,
     * а не реализуются повторно в этом классе.
     */
    private val policy = TorPolicy(config)

    /**
     * Уникальные URL, уже допущенные в текущем экземпляре.
     *
     * LinkedHashSet обеспечивает уникальность и сохраняет
     * порядок добавления.
     *
     * Важно: наличие URL в admitted не означает,
     * что соответствующая страница успешно загружена.
     */
    private val admitted = linkedSetOf<String>()

    init {
        // Проверяем допустимые границы конфигурации.
        //
        // maxPages: 1..10000
        // maxDepth: 0..100
        // maxLinks: 1..1000
        //
        // Эти пределы ограничивают параметры локального теста.
        // Они не являются глобальной quota Frontier.
        require(
            maxPages in 1..10000 &&
                    maxDepth in 0..100 &&
                    maxLinks in 1..1000
        )
    }

    /**
     * Результат анализа одной обнаруженной ссылки.
     *
     * Представляет решение Discovery, а не результат HTTP-fetch.
     *
     * @property source нормализованный URL исходной страницы.
     * @property target нормализованный целевой URL;
     *   null, если обработка адреса завершилась ошибкой.
     * @property discoveredAt время выполнения планирования
     *   в ISO-8601, а не время загрузки целевой страницы.
     * @property depth предполагаемая глубина целевого URL.
     * @property result строковый код решения Discovery.
     */
    data class Edge(
        val source: String,
        val target: String?,
        val discoveredAt: String,
        val depth: Int,
        val result: String
    )

    /**
     * Анализирует ссылки, обнаруженные на исходной странице.
     *
     * Алгоритм:
     * 1. Проверяет глубину исходной страницы.
     * 2. Нормализует source через TorPolicy.
     * 3. Учитывает source в локальном бюджете.
     * 4. Рассматривает первые maxLinks ссылок.
     * 5. Нормализует каждый target.
     * 6. Проверяет тип ресурса, дубликаты, глубину и бюджет.
     * 7. Возвращает список решений [Edge].
     *
     * Порядок результатов соответствует порядку первых
     * maxLinks элементов входного списка references.
     *
     * Метод изменяет внутренний набор admitted.
     *
     * @param source URL исходной страницы.
     * @param depth глубина исходной страницы, начиная с нуля.
     * @param references ссылки, извлечённые из исходной страницы.
     *
     * @return список решений для рассмотренных ссылок.
     *
     * @throws IllegalArgumentException если depth вне диапазона.
     * @throws IllegalStateException если новый source не помещается
     *   в оставшийся локальный бюджет.
     */
    @Synchronized fun discover(
        source: String,
        depth: Int,
        references: List<String>
    ): List<Edge> {

        // Глубина исходной страницы должна быть допустимой.
        require(depth in 0..100)

        // Приводим исходный адрес к форме, принятой TorPolicy.
        val base = policy.normalize(source)

        // Учитываем исходную страницу в бюджете, если она
        // ещё не была допущена в этом экземпляре planner.
        if (base !in admitted) {

            // Если бюджет исчерпан, новый source добавить нельзя.
            check(admitted.size < maxPages) {
                "Source outside session admission budget"
            }

            admitted.add(base)
        }

        // Одна временная отметка для всех ссылок этого вызова.
        // Это время планирования, не время HTTP-запроса.
        val now = Instant.now().toString()

        // Рассматриваем только первые maxLinks ссылок.
        // Остальные не получают Edge в этом вызове.
        return references.take(maxLinks).map { reference ->

            // Преобразуем относительную ссылку в абсолютную
            // относительно нормализованного source.
            val target = try {

                // Ограничиваем длину исходного представления URL.
                if (reference.length > 2048) {
                    throw IOException("URL_LIMIT")
                }

                policy.normalize(
                    URI(base).resolve(reference).toString()
                )

            } catch (_: IOException) {
                // Ошибка нормализации или явное превышение лимита.
                null
            } catch (_: IllegalArgumentException) {
                // Некорректная ссылка или недопустимый аргумент.
                null
            }

            // Порядок проверок важен:
            // первая подходящая ветка определяет результат.
            val result = when {
                // Адрес не удалось получить или нормализовать.
                target == null -> "REJECTED"

                // Ресурс по эвристике не предназначен для
                // обычного HTML-обхода.
                metadataOnly(target) -> "METADATA_ONLY"

                // URL уже присутствует в локальном наборе.
                target in admitted -> "DUPLICATE"

                // Достигнут предел глубины.
                depth >= maxDepth -> "DEPTH_LIMIT"

                // Локальный бюджет уникальных URL исчерпан.
                admitted.size >= maxPages -> "PAGE_LIMIT"

                else -> {
                    // Новый URL занимает место в локальном бюджете.
                    // Это ещё не HTTP-fetch и не durable reservation.
                    admitted.add(target)
                    "ADMITTED"
                }
            }

            // Глубина target на один уровень больше глубины source.
            Edge(
                source       = base,
                target       = target,
                discoveredAt = now,
                depth        = depth + 1,
                result       = result
            )
        }
    }

    companion object {

        /**
         * Определяет, следует ли оставить URL только
         * в метаданных обнаружения.
         *
         * Текущая реализация использует исключительно
         * расширение последнего сегмента URL path.
         *
         * Возвращает true для путей с точкой в имени
         * последнего сегмента, кроме .html и .htm.
         *
         * ВАЖНО:
         * Это эвристика по URL, а не проверка MIME-типа.
         * Она может исключать динамические HTML-страницы
         * с расширениями .php, .asp, .aspx и другими.
         *
         * Расширения отсутствующих в URL бинарных ресурсов
         * также невозможно определить этим методом.
         *
         * Точка расширения:
         * для полноценного forum crawling потребуется
         * пересмотреть классификацию HTML-страниц.
         *
         * @param url нормализованный URL ресурса.
         * @return true, если URL классифицирован как metadata-only.
         */
        @JvmStatic fun metadataOnly(url: String): Boolean {

            // Анализируем path, не включая query-параметры.
            val path = URI(url).path.lowercase()

            // Извлекаем последний сегмент пути.
            val leaf = path.substringAfterLast('/')

            // Существующая консервативная эвристика:
            // только .html и .htm явно допускаются
            // среди URL с расширениями.
            return '.' in leaf &&
                    !leaf.endsWith(".html") &&
                    !leaf.endsWith(".htm")
        }
    }
}
