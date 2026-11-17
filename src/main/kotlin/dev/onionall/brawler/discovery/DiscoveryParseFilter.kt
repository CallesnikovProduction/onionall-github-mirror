package dev.onionall.brawler.discovery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.stormcrawler.Metadata
import org.apache.stormcrawler.parse.ParseFilter
import org.apache.stormcrawler.parse.ParseResult
import org.w3c.dom.DocumentFragment

/**
 * Адаптер Discovery для HTML-парсера Apache StormCrawler.
 *
 * Получает уже извлечённые парсером ссылки, передаёт их в [DiscoveryPlanner]
 * и заменяет исходные outlinks результатами локального планирования.
 *
 * Основные задачи:
 * - применить ограничения глубины, количества страниц и ссылок;
 * - сохранить решения Discovery в metadata исходной страницы;
 * - передать допустимые ссылки в существующий pipeline StormCrawler;
 * - добавить к ссылкам сведения об их происхождении (provenance).
 *
 * ВАЖНО:
 * Работает исключительно в режиме `discovery.budget.mode=local-test`.
 * Локальные бюджеты и дедупликация не обеспечивают долговременные
 * reservations, глобальную quota или координацию нескольких workers.
 *
 * В production для durable admission используется отдельный
 * Frontier-backed компонент `DurableDiscoveryFilter`.
 *
 * Сам фильтр не выполняет HTTP-запросы.
 *
 * @see DiscoveryPlanner
 * @see ParseFilter
 *
 * @author Stephan Kolesnikov
 * @author GPT-6 Astra
 */
class DiscoveryParseFilter : ParseFilter() {

    /**
     * Планировщик локального Discovery.
     *
     * Инициализируется в [configure], поэтому до конфигурации
     * обращаться к нему нельзя.
     *
     * Его внутренние бюджеты и дедупликация ограничены временем
     * жизни экземпляра и не являются durable state.
     */
    private lateinit var planner: DiscoveryPlanner

    /**
     * Jackson mapper для сериализации решений Discovery в JSON.
     *
     * Результат сохраняется в metadata исходной страницы.
     */
    private val KT_DISCOVERYPARSEFILTER_OBJECT_MAPPER = ObjectMapper()

    /**
     * Инициализирует фильтр из конфигурации StormCrawler.
     *
     * Проверяет разрешённый режим работы и наличие session ID,
     * после чего создаёт [DiscoveryPlanner] с заданными лимитами.
     *
     * @param conf общая конфигурация StormCrawler.
     * @param params параметры конкретного фильтра.
     * @param name имя фильтра в конфигурации pipeline.
     *
     * @throws IllegalArgumentException если режим или session ID
     * не соответствуют обязательным условиям.
     * @throws IllegalStateException если отсутствует числовой лимит
     * или его значение невозможно преобразовать в Int.
     */
    override fun configure(
        conf: MutableMap<String, Any>,
        params: JsonNode,
        name: String
    ) {
        // Сохраняем стандартную инициализацию родительского ParseFilter.
        super.configure(conf, params, name)

        // Защита от случайного использования локального планировщика
        // вместо production-механизма durable admission.
        require(conf["discovery.budget.mode"] == "local-test") {
            "Durable global discovery reservations are unavailable; only explicit local-test mode is supported"
        }

        // Session ID обязателен и должен соответствовать безопасному
        // ограниченному формату. Это не гарантия durable session.
        require(
            (conf["discovery.crawl.session.id"] as? String)
                ?.matches(Regex("[A-Za-z0-9_-]{1,64}")) == true
        ) {
            "Require explicit discovery.crawl.session.id"
        }

        /**
         * Читает обязательное целочисленное значение из конфигурации.
         *
         * Здесь можно менять способ чтения конфигурации, но не следует
         * незаметно подставлять defaults вместо обязательных лимитов.
         */
        fun required(key: String): Int =
            conf[key]
                ?.toString()
                ?.toIntOrNull()
                ?: error("Missing M3 integer setting: $key")

        // Основные параметры локального Discovery:
        // max.pages — бюджет страниц;
        // max.depth — максимальная глубина переходов;
        // max.links — ограничение обработки ссылок.
        //
        // Их точную семантику определяет DiscoveryPlanner.
        planner = DiscoveryPlanner(
            conf,
            required("discovery.max.pages"),
            required("discovery.max.depth"),
            required("discovery.max.links")
        )
    }

    /**
     * Указывает StormCrawler, что фильтру не нужен DOM.
     *
     * Используются только готовые outlinks и metadata из ParseResult.
     */
    override fun needsDOM() = false

    /**
     * Обрабатывает ссылки, обнаруженные на исходной HTML-странице.
     *
     * Последовательность:
     * 1. Получить metadata исходной страницы.
     * 2. Определить её текущую discovery depth.
     * 3. Передать найденные ссылки в [DiscoveryPlanner].
     * 4. Сохранить решения планировщика в metadata страницы.
     * 5. Оставить допустимые outlinks и дополнить их provenance.
     *
     * Метод изменяет переданный [result].
     *
     * @param url URL исходной страницы.
     * @param content байты содержимого; здесь не используются.
     * @param doc DOM-фрагмент; не используется, поскольку needsDOM=false.
     * @param result изменяемый результат парсинга StormCrawler.
     *
     * @throws IllegalArgumentException если результат исходной
     * страницы отсутствует или depth имеет некорректный формат.
     */
    override fun filter(
        url: String,
        content: ByteArray,
        doc: DocumentFragment?,
        result: ParseResult
    ) {
        // Получаем metadata именно исходной страницы.
        // Если парсер не создал результат для url, прерываем обработку.
        val parent = requireNotNull(result.get(url)) {
            "Missing parent parse result"
        }.metadata

        // Глубина исходной страницы.
        // При отсутствии metadata используется 0 (корневая глубина).
        val depth = (parent.getFirstValue("discovery.depth") ?: "0").toInt()

        // Фиксируем исходные outlinks до их последующей замены.
        val links = result.outlinks.toList()

        // Planner принимает URL ссылок и возвращает решения Discovery.
        //
        // ВАЖНЫЙ КОНТРАКТ:
        // Ниже используется links[i] для соответствующего edges[i].
        // Следовательно, порядок и индексация результатов planner
        // должны соответствовать исходному списку links.
        val edges = planner.discover(
            url,
            depth,
            links.map { it.targetURL }
        )

        // Сохраняем решения Discovery в metadata исходной страницы.
        // Каждое решение сериализуется как отдельная JSON-строка.
        parent.setValues(
            "discovery.edges",
            edges.map {
                KT_DISCOVERYPARSEFILTER_OBJECT_MAPPER.writeValueAsString(it)
            }.toTypedArray()
        )

        // Отмечаем ситуацию, когда результатов планирования меньше,
        // чем исходных ссылок.
        //
        // Это индикатор сокращения списка, а не точное количество
        // отклонённых ссылок.
        parent.setValue(
            "discovery.links.truncated",
            (links.size > edges.size).toString()
        )

        // Не допускаем повторной выдачи одного target URL
        // внутри текущего вызова filter().
        val emitted = mutableSetOf<String>()

        // Заменяем исходные outlinks отобранными ссылками.
        result.outlinks = edges.mapIndexedNotNull { i, edge ->

            if (
                // Пропускаем только разрешённые здесь категории.
                edge.result !in setOf("ADMITTED", "DUPLICATE") ||

                // Исключаем ссылку на саму исходную страницу.
                edge.target == edge.source ||

                // Нельзя передать ссылку без целевого URL.
                edge.target == null ||

                // Убираем повторяющиеся target в текущем результате.
                !emitted.add(edge.target)
            )
                null
            else
                links[i].also {

                    // Копируем существующие metadata исходного outlink,
                    // чтобы сохранить информацию других компонентов.
                    val metadata = Metadata()
                    it.metadata?.let { old -> metadata.putAll(old) }
                    it.metadata = metadata

                    // Подставляем нормализованный target,
                    // возвращённый DiscoveryPlanner.
                    it.targetURL = edge.target

                    // Provenance: где, когда и на какой глубине
                    // обнаружена ссылка, а также решение planner.
                    it.metadata.setValue(
                        "discovery.source",
                        edge.source
                    )
                    it.metadata.setValue(
                        "discovery.at",
                        edge.discoveredAt
                    )
                    it.metadata.setValue(
                        "discovery.depth",
                        edge.depth.toString()
                    )
                    it.metadata.setValue(
                        "discovery.result",
                        edge.result
                    )
                }
        }
    }
}
