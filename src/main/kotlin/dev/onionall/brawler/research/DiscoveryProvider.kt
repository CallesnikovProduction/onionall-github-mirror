package dev.onionall.brawler.research

/**
 * Контракт поискового провайдера для обнаружения кандидатов.
 *
 * Выполняет поиск по пользовательскому запросу и возвращает
 * структурированный [DiscoveryBatch] с обнаруженными URL,
 * результатами валидации и сведениями о происхождении ссылок.
 *
 * ВАЖНО:
 * Обнаружение кандидатов не запускает crawler, не создаёт
 * Frontier reservations и не разрешает посещение найденных hosts.
 *
 * Выбор кандидатов, пользовательское подтверждение и запуск
 * crawl-планов принадлежат уровню Research Session/Workflow.
 *
 * Конкретный провайдер отвечает за соблюдение ограничений
 * своего источника, проверку результатов и достоверность
 * возвращаемых метаданных.
 *
 * @see DiscoveryBatch
 * @see DiscoveryCandidate
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
interface DiscoveryProvider {

    /**
     * Выполняет ограниченный поиск кандидатов.
     *
     * @param query поисковый запрос пользователя.
     * @param limit максимальное количество кандидатов,
     *   возвращаемых провайдером.
     *
     * @return пакет результатов поиска с provenance,
     *   отклонёнными ссылками и сведениями о покрытии.
     *
     * @throws IOException при ошибке провайдера или транспорта.
     *
     * @see DiscoveryBatch
     */
    @Throws(java.io.IOException::class)
    fun discover(
        query: String,
        limit: Int
    ): DiscoveryBatch
}

/**
 * Абстракция HTTP-транспорта поискового провайдера.
 *
 * Отделяет реализацию Discovery от конкретного механизма
 * сетевых запросов и позволяет подменять транспорт в тестах.
 *
 * Production-реализация должна использовать существующий
 * TorProtocol с сохранением его ограничений:
 * - отсутствие DIRECT fallback;
 * - удалённое разрешение имён через Tor;
 * - ограниченные deadline и timeout;
 * - корректная обработка cancellation;
 * - запрет автоматических HTTP redirects.
 *
 * Этот интерфейс сам по себе не обеспечивает перечисленные
 * гарантии — их обязан соблюдать конкретный транспорт.
 *
 * @see ProviderResponse
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
fun interface ProviderTransport {

    /**
     * Выполняет один HTTP GET-запрос через настроенный транспорт.
     *
     * @param url адрес запрашиваемого ресурса провайдера.
     *
     * @return HTTP-ответ с фактическим responseUrl.
     *
     * @throws IOException при сетевой ошибке, отмене
     *   или нарушении транспортного контракта.
     */
    @Throws(java.io.IOException::class)
    fun get(url: String): ProviderResponse
}

/**
 * Представление одного HTTP-ответа поискового провайдера.
 *
 * Используется на границе ProviderTransport и DiscoveryProvider.
 * Не является результатом загрузки страницы архивным crawler.
 *
 * Сам класс не проверяет размер тела, HTTP status или MIME.
 * Эти ограничения применяются вызывающим кодом и транспортом.
 *
 * @property status фактический HTTP status code.
 * @property contentType значение Content-Type, если известно.
 * @property body полученные байты ответа.
 * @property responseUrl фактический URL, с которого получен ответ;
 *   используется для проверки redirects и origin.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
data class ProviderResponse(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    val responseUrl: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProviderResponse

        if (status != other.status) return false
        if (contentType != other.contentType) return false
        if (!body.contentEquals(other.body)) return false
        if (responseUrl != other.responseUrl) return false

        return true
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + contentType.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + responseUrl.hashCode()
        return result
    }
}

/**
 * Проверенный кандидат, обнаруженный поисковым провайдером.
 *
 * Хранит нормализованный URL, исходную ссылку из поисковой
 * выдачи, текстовые сведения и provenance.
 *
 * Кандидат представляет результат обнаружения, а не
 * разрешение на скачивание или обход сайта.
 *
 * Выбор кандидата (selected), approval и crawl lifecycle
 * принадлежат Research Session, а не этой модели.
 *
 * @property candidateId идентификатор кандидата, создаваемый
 *   при обнаружении и сохраняемый вместе с записью.
 *   Не гарантирует одинаковый ID при повторном поиске URL.
 * @property originalResultUrl исходный href поисковой выдачи,
 *   включая provider-specific wrapper, если он присутствовал.
 * @property canonicalUrl проверенный нормализованный URL.
 * @property host host обнаруженного ресурса.
 * @property title заголовок результата, если доступен.
 * @property snippet текстовое описание, если доступно.
 * @property discoveredAt время обнаружения в строковом формате.
 * @property validationStatus результат проверки кандидата.
 * @property duplicateGroup ключ группировки, предоставленный
 *   провайдером; его семантика определяется реализацией.
 * @property provenance исходные представления ссылок,
 *   связанные с данным кандидатом.
 *
 * @see DiscoveryBatch
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
data class DiscoveryCandidate(
    val candidateId: String,
    val originalResultUrl: String,
    val canonicalUrl: String,
    val host: String,
    val title: String?,
    val snippet: String?,
    val discoveredAt: String,
    val validationStatus: String,
    val duplicateGroup: String,
    val provenance: List<String>
) {

    /**
     * Преобразует кандидата в map-представление для
     * последующей сериализации и хранения.
     *
     * Использует стабильные snake_case имена полей.
     * LinkedHashMap сохраняет порядок их добавления.
     *
     * ВАЖНО:
     * Ключи являются частью формата обмена данными.
     * При изменении необходимо учитывать совместимость
     * существующих записей Research Catalog.
     *
     * @return упорядоченная карта полей кандидата.
     */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "candidate_id" to candidateId,
        "original_result_url" to originalResultUrl,
        "canonical_url" to canonicalUrl,
        "host" to host,
        "title" to title,
        "snippet" to snippet,
        "discovered_at" to discoveredAt,
        "validation_status" to validationStatus,
        "duplicate_group" to duplicateGroup,
        "provenance" to provenance
    )
}

/**
 * Результат одной ограниченной операции Discovery.
 *
 * Содержит поисковый запрос, наблюдаемые результаты,
 * проверенных кандидатов, отклонённые ссылки и сведения
 * о фактическом покрытии поисковой выдачи.
 *
 * ВАЖНО:
 * Количество найденных результатов не обязательно равно
 * количеству возвращённых кандидатов.
 *
 * Провайдер не должен придумывать отсутствующие результаты,
 * автоматически подменять неудачный поиск локальной выдачей
 * или заявлять неподтверждённое покрытие пагинации.
 *
 * DiscoveryBatch не предоставляет crawl authorization.
 *
 * @property provider идентификатор поискового провайдера.
 * @property originalQuery исходный запрос пользователя.
 * @property normalizedQuery нормализованный поисковый запрос.
 * @property responseUrl URL, зафиксированный провайдером
 *   для provenance поисковой операции.
 *   Может быть очищен от временных hidden-параметров.
 * @property fetchedAt время получения результатов.
 * @property actualResultCount число наблюдавшихся result anchors
 *   в ответе провайдера.
 * @property candidates проверенные кандидаты, ограниченные limit.
 * @property rejected записи об отклонённых и повторных результатах.
 * @property networkRequests количество сетевых запросов,
 *   заявленное реализацией провайдера.
 * @property paginationState состояние покрытия выдачи,
 *   определённое провайдером.
 * @property validUniqueCount количество уникальных валидных
 *   результатов до применения лимита возвращаемых кандидатов.
 *
 * @see DiscoveryProvider
 * @see DiscoveryCandidate
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
data class DiscoveryBatch(
    val provider: String,
    val originalQuery: String,
    val normalizedQuery: String,
    val responseUrl: String,
    val fetchedAt: String,
    val actualResultCount: Int,
    val candidates: List<DiscoveryCandidate>,
    val rejected: List<Map<String, Any?>>,
    val networkRequests: Int,
    val paginationState: String,
    val validUniqueCount: Int
) {

    /**
     * Преобразует результаты Discovery в map-представление.
     *
     * Сохраняет наблюдаемые счётчики, provenance и сведения
     * о покрытии. Кандидаты сериализуются через их toMap().
     *
     * Добавляет сведения об основании разрешённой операции
     * поиска Ahmia и явно запрещает трактовать этот результат
     * как автоматическое разрешение на crawl.
     *
     * ВАЖНО:
     * authorization_basis относится к разрешению операции
     * поиска у провайдера, а не к разрешению посещать
     * обнаруженные onion-hosts.
     *
     * Имена полей следует сохранять совместимыми
     * с существующими форматами Research Catalog.
     *
     * @return упорядоченная карта данных Discovery.
     */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "provider" to provider,
        "query" to originalQuery,
        "normalized_query" to normalizedQuery,
        "response_url" to responseUrl,
        "fetched_at" to fetchedAt,

        // Различаем фактическое число результатов,
        // число возвращённых кандидатов и уникальные URL.
        "actual_result_count" to actualResultCount,
        "candidate_count" to candidates.size,
        "valid_unique_count" to validUniqueCount,

        "candidates" to candidates.map { it.toMap() },
        "rejected" to rejected,

        // Диагностика поисковой операции и её покрытия.
        "network_requests" to networkRequests,
        "pagination_state" to paginationState,

        // Это разрешение поиска Ahmia, не разрешение crawl.
        "authorization_basis" to "OWNER_CONFIRMED_AHMIA_SEARCH_GRANT",
        "automatic_crawl_authorization" to false
    )
}
