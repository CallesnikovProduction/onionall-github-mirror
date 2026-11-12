package dev.onionall.brawler.research

import java.io.IOException

/**
 * Получение кандидатов не запускает crawl и не утверждает внешние hosts.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
interface DiscoveryProvider {
    @Throws(IOException::class)
    fun discover(query: String, limit: Int): DiscoveryBatch
}

/**
 * Узкая граница существующего Tor transport; responseUrl — фактический URL ответа.
 * Реализация должна сохранять deadline, cancellation, remote DNS и запрет DIRECT.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
fun interface ProviderTransport {
    @Throws(IOException::class)
    fun get(url: String): ProviderResponse
}

/**
 * Bounded ответ провайдера, не результат архивного crawler.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
data class ProviderResponse(val status: Int, val contentType: String?, val body: ByteArray, val responseUrl: String)

/**
 * Проверенный candidate с исходными result wrappers; selected/approval принадлежат session.
 * candidateId генерируется один раз, затем сохраняется вместе с provenance.
 *
 * @author Стефан Колесников
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
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "candidate_id" to candidateId, "original_result_url" to originalResultUrl,
        "canonical_url" to canonicalUrl, "host" to host, "title" to title, "snippet" to snippet,
        "discovered_at" to discoveredAt, "validation_status" to validationStatus,
        "duplicate_group" to duplicateGroup, "provenance" to provenance
    )
}

/**
 * actualResultCount считает наблюдавшиеся result anchors, candidates ограничен запрошенным limit.
 * Неполная выдача/ошибка не превращается в выдуманные candidates или локальный search.
 *
 * @author Стефан Колесников
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
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "provider" to provider, "query" to originalQuery, "normalized_query" to normalizedQuery,
        "response_url" to responseUrl, "fetched_at" to fetchedAt,
        "actual_result_count" to actualResultCount, "candidate_count" to candidates.size,
        "valid_unique_count" to validUniqueCount, "candidates" to candidates.map { it.toMap() },
        "rejected" to rejected, "network_requests" to networkRequests,
        "pagination_state" to paginationState,
        "authorization_basis" to "OWNER_CONFIRMED_AHMIA_SEARCH_GRANT",
        "automatic_crawl_authorization" to false
    )
}
