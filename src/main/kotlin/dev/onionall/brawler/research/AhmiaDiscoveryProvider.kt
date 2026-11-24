package dev.onionall.brawler.research

import dev.onionall.brawler.tor.TorPolicy
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Провайдер обнаружения onion-ресурсов через поисковую форму Ahmia.
 *
 * Выполняет ограниченную поисковую операцию:
 * 1. Загружает главную страницу фиксированного Ahmia onion-сервиса.
 * 2. Находит и проверяет HTML-форму поиска.
 * 3. Извлекает полученные от сервера hidden-поля.
 * 4. Отправляет GET-запрос с поисковой строкой.
 * 5. Извлекает ссылки из HTML-результатов.
 * 6. Проверяет, нормализует и дедуплицирует onion-URL.
 * 7. Возвращает [DiscoveryBatch] с результатами и provenance.
 *
 * Провайдер не посещает найденные сайты. Полученные URL являются
 * кандидатами для последующего пользовательского выбора, а не
 * разрешением на автоматический обход.
 *
 * Ограничения безопасности:
 * - запросы выполняются только к фиксированному origin Ahmia;
 * - JavaScript и CAPTCHA не исполняются;
 * - hidden-поля принимаются только из проверенной формы;
 * - перенаправления HTTP не допускаются;
 * - размер ответа ограничен;
 * - неподтверждённая пагинация не используется;
 * - авторизация поиска Ahmia не распространяется на crawler.
 *
 * Сетевые запросы делегируются [ProviderTransport].
 * Его production-реализация должна использовать TorProtocol
 * без DIRECT fallback и локального DNS для onion-адресов.
 *
 * @param transport транспорт для HTTP-запросов к провайдеру.
 * @param clock источник времени; может подменяться в тестах.
 *
 * @see DiscoveryProvider
 * @see ProviderTransport
 * @see DiscoveryBatch
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
class AhmiaDiscoveryProvider @JvmOverloads constructor(
    private val transport: ProviderTransport,
    private val clock: Clock = Clock.systemUTC()
) : DiscoveryProvider {

    companion object {

        /** Фиксированный onion-host поискового провайдера Ahmia. */
        const val HOST =
            "juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion"

        /** Единственная разрешённая исходная точка запросов провайдера. */
        const val DEFAULT_ORIGIN = "http://$HOST/"

        /** Максимальный размер одного HTTP-ответа: 1 MiB. */
        private const val MAX_RESPONSE_BYTES = 1048576
    }

    /**
     * Выполняет один ограниченный поиск через HTML-форму Ahmia.
     *
     * Запрос состоит из загрузки homepage и отправки формы поиска.
     * Все обнаруженные ссылки проверяются до создания кандидатов.
     *
     * Повторяющиеся canonical URL объединяются в одного кандидата,
     * при этом исходные варианты ссылок сохраняются в provenance.
     *
     * @param query исходный поисковый запрос пользователя.
     * @param limit максимальное число возвращаемых кандидатов (1..20).
     *
     * @return пакет результатов, включая отклонённые ссылки,
     *   количество наблюдаемых результатов и сведения о покрытии.
     *
     * @throws IllegalArgumentException при нарушении ограничений
     *   поискового запроса или limit.
     * @throws IOException при ошибке провайдера, некорректной
     *   HTML-форме, недопустимом ответе или отмене операции.
     */
    @Throws(IOException::class)
    override fun discover(
        query: String,
        limit: Int
    ): DiscoveryBatch {

        // Первичная проверка исходной строки до нормализации.
        require(
            query.length <= 256 &&
                    query.none { it.isISOControl() && !it.isWhitespace() }
        ) {
            "Require a bounded plain-text query"
        }

        // Убираем пробелы по краям и объединяем последовательности
        // Unicode whitespace в один обычный пробел.
        val normalized = query.trim()
            .replace(Regex("(?U)\\s+"), " ")

        // Ограничения фактической HTML-формы TorResultsView.
        require(
            normalized.isNotEmpty() &&
                    normalized.length <= 100 &&
                    normalized.split(Regex("(?U)[+\\s]+"))
                        .count { it.isNotEmpty() } <= 10
        ) {
            "Ahmia requires a nonblank query up to 100 characters and 10 words"
        }

        require(limit in 1..20) {
            "Discovery limit must be 1..20"
        }

        // Сначала получаем настоящую форму поиска.
        // Нельзя заранее придумывать hidden token или параметры.
        val homepage = fetch(DEFAULT_ORIGIN)
        val home = document(homepage)

        val form = home.selectFirst("form#searchForm")
            ?: throw IOException("PROVIDER_SEARCH_FORM_MISSING")

        // Разрешаем action относительно фиксированного origin.
        val action = try {
            URI(DEFAULT_ORIGIN).resolve(form.attr("action"))
        } catch (_: Exception) {
            throw IOException("PROVIDER_FORM_REJECTED")
        }

        // Запрещаем отправку формы на другой host, path,
        // с дополнительными query/fragment или другим методом.
        if (
            !sameProvider(action) ||
            action.rawPath != "/search/" ||
            action.rawQuery != null ||
            action.rawFragment != null ||
            !form.attr("method").equals("get", true)
        ) {
            throw IOException("PROVIDER_FORM_REJECTED")
        }

        // В форме должен присутствовать ровно один параметр q.
        if (form.select("input[name=q]").size != 1) {
            throw IOException("PROVIDER_FORM_REJECTED")
        }

        // Hidden-поля берём только из реально полученного HTML.
        val fields = form.select("input[type=hidden]")

        if (fields.isEmpty() || fields.size > 8) {
            throw IOException("PROVIDER_FORM_REJECTED")
        }

        val names = HashSet<String>()
        val parameters = mutableListOf("q=" + encode(normalized))

        for (field in fields) {
            val name = field.attr("name")
            val value = field.attr("value")

            // Не допускаем пустые/небезопасные имена,
            // повторяющиеся поля, подмену q и control characters.
            if (
                !name.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
                name == "q" ||
                !names.add(name) ||
                value.isEmpty() ||
                value.length > 256 ||
                value.any { it.isISOControl() }
            ) {
                throw IOException("PROVIDER_FORM_REJECTED")
            }

            parameters.add(encode(name) + "=" + encode(value))
        }

        // Отправляем обычную GET-форму, включая hidden-поля.
        val response = fetch(
            action.toASCIIString() + "?" +
                    parameters.joinToString("&")
        )

        val results = document(response)

        // Проверяем, что получен именно ожидаемый HTML
        // поисковой выдачи, а не произвольная страница.
        if (
            results.selectFirst("#ahmiaResultsPage") == null ||
            (
                    results.selectFirst("ol.searchResults") == null &&
                            results.selectFirst("#noResults") == null
                    )
        ) {
            throw IOException("PROVIDER_RESPONSE_NOT_SEARCH_RESULTS")
        }

        // CSS-селектор зависит от наблюдаемого DOM-контракта Ahmia.
        val anchors = results.select(
            "ol.searchResults li.result h4 a[href]"
        )

        if (anchors.size > 5000) {
            throw IOException("PROVIDER_RESULT_LIMIT_EXCEEDED")
        }

        // Одна временная отметка для всех наблюдений batch.
        val now = Instant.now(clock).toString()

        // LinkedHashMap сохраняет порядок первых появлений
        // и позволяет объединять кандидатов по canonical URL.
        val all = linkedMapOf<String, DiscoveryCandidate>()

        // Отдельно фиксируем невалидные и повторные результаты.
        val rejected = mutableListOf<Map<String, Any?>>()

        for (anchor in anchors) {

            // Проверяем отмену во время обработки большого ответа.
            interrupted()

            val original = anchor.attr("href")

            // Снимаем provider redirect-wrapper и проверяем
            // конечный onion URL без обращения к самому target.
            val canonical = try {
                canonical(unwrap(original))
            } catch (_: IOException) {
                rejected.add(
                    mapOf(
                        "original_result_url" to original.take(8192),
                        "validation_status" to "INVALID_URL",
                        "discovered_at" to now
                    )
                )
                continue
            }

            val previous = all[canonical]

            if (previous != null) {

                // Один canonical URL может встречаться в выдаче
                // через разные исходные href.
                all[canonical] = previous.copy(
                    provenance = (
                            previous.provenance + original
                            ).distinct()
                )

                rejected.add(
                    mapOf(
                        "original_result_url" to original,
                        "canonical_url" to canonical,
                        "validation_status" to "DUPLICATE",
                        "discovered_at" to now
                    )
                )
                continue
            }

            val host = URI(canonical).host

            // Создаём кандидата с исходной ссылкой,
            // нормализованным URL, title, snippet и provenance.
            //
            // Никакой fetch найденного ресурса здесь не происходит.
            all[canonical] = DiscoveryCandidate(
                UUID.randomUUID().toString(),
                original,
                canonical,
                host,
                anchor.text().trim().take(2048).ifEmpty { null },
                anchor.closest("li.result")
                    ?.selectFirst("p")
                    ?.text()
                    ?.trim()
                    ?.take(4096)
                    ?.ifEmpty { null },
                now,
                "VALID",
                host,
                listOf(original)
            )
        }

        // Возвращаем фактическую выдачу одной HTML-страницы.
        // Не придумываем следующие страницы или результаты.
        return DiscoveryBatch(
            "ahmia",
            query,
            normalized,

            // Hidden-поля могут содержать временные токены.
            // В provenance сохраняем только публичный q.
            action.toASCIIString() + "?q=" + encode(normalized),

            now,
            anchors.size,
            all.values.take(limit),
            rejected,
            2,

            // Различаем ограничение выдачи, наличие признака
            // неподдерживаемой пагинации и обычную одну страницу.
            if (all.size > limit) {
                "SINGLE_PAGE_LIMIT_REACHED"
            } else if (results.select("a[rel~=next]").isNotEmpty()) {
                "PAGINATION_NOT_SUPPORTED"
            } else {
                "SINGLE_PAGE"
            },

            all.size
        )
    }

    /**
     * Получает один ответ от фиксированного поискового провайдера.
     *
     * Проверяет отмену, фактический URL ответа, HTTP status,
     * размер тела и MIME-тип.
     *
     * Сетевую маршрутизацию обеспечивает [ProviderTransport].
     * Транспорт не должен автоматически следовать redirects.
     *
     * @param url запрашиваемый URL провайдера.
     * @return проверенный HTTP-ответ.
     *
     * @throws IOException если нарушен контракт ответа.
     * @throws InterruptedIOException при отмене.
     */
    private fun fetch(url: String): ProviderResponse {
        interrupted()
        val response = transport.get(url)
        interrupted()

        val actual = try {
            URI(response.responseUrl)
        } catch (_: Exception) {
            throw IOException("PROVIDER_INVALID_RESPONSE_URL")
        }

        // Даже успешный ответ не принимается,
        // если его URL отличается от запрошенного.
        if (!sameProvider(actual) || actual != URI(url)) {
            throw IOException("PROVIDER_REDIRECT_REJECTED")
        }

        if (response.status != 200) {
            throw IOException("PROVIDER_HTTP_${response.status}")
        }

        if (
            response.body.isEmpty() ||
            response.body.size > MAX_RESPONSE_BYTES
        ) {
            throw IOException("PROVIDER_BODY_LIMIT")
        }

        if (
            response.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase() != "text/html"
        ) {
            throw IOException("PROVIDER_UNSUPPORTED_CONTENT_TYPE")
        }

        return response
    }

    /**
     * Преобразует проверенный HTML-ответ в JSoup Document.
     *
     * Base URI позволяет JSoup работать с относительными
     * ссылками в пределах полученного документа.
     *
     * @param response HTML-ответ провайдера.
     * @return разобранный DOM-документ.
     */
    private fun document(response: ProviderResponse): Document =
        Jsoup.parse(
            ByteArrayInputStream(response.body),
            null,
            response.responseUrl
        )

    /**
     * Проверяет точное соответствие URI разрешённому Ahmia origin.
     *
     * Требуется HTTP, фиксированный onion-host, отсутствие
     * userinfo и явного порта, а также точный rawAuthority.
     *
     * @param uri проверяемый URI.
     * @return true, если URI принадлежит разрешённому origin.
     */
    private fun sameProvider(uri: URI): Boolean =
        uri.scheme == "http" &&
                uri.host == HOST &&
                uri.rawUserInfo == null &&
                uri.port == -1 &&
                uri.rawAuthority == HOST

    /**
     * Извлекает исходный URL результата из Ahmia redirect-wrapper.
     *
     * Поддерживает обычные прямые ссылки и наблюдаемый формат
     * /search/redirect?redirect_url=...
     *
     * Важно: в текущем формате upstream параметр redirect_url
     * находится последним, а внутренние символы '&' целевого
     * URL могут быть не закодированы.
     *
     * Поэтому стандартный разбор query-параметров способен
     * потерять часть исходного адреса.
     *
     * Метод не выполняет HTTP redirect.
     *
     * @param value исходный href результата.
     * @return извлечённый URL для последующей проверки.
     *
     * @throws IOException при некорректном или неоднозначном href.
     */
    private fun unwrap(value: String): String {
        val raw = value.trim()

        if (
            raw.length > 8192 ||
            raw.any { it.isISOControl() }
        ) {
            throw IOException("INVALID_RESULT_URL")
        }

        val question = raw.indexOf('?')

        if (question >= 0) {
            val route = try {
                URI(DEFAULT_ORIGIN).resolve(
                    raw.substring(0, question)
                )
            } catch (_: Exception) {
                null
            }

            if (
                route != null &&
                sameProvider(route) &&
                route.rawPath == "/search/redirect" &&
                route.rawFragment == null
            ) {
                val query = raw.substring(question + 1)

                val positions = Regex(
                    "(?:^|&)redirect_url="
                ).findAll(query).toList()

                if (positions.size != 1) {
                    throw IOException("AMBIGUOUS_RESULT_URL")
                }

                // Берём остаток строки после redirect_url=.
                // Это сохраняет неэкранированные '&' внутри
                // query-параметров самого целевого URL.
                val target = query.substring(
                    positions.single().range.last + 1
                )

                if (
                    target.startsWith("http://", true) ||
                    target.startsWith("https://", true)
                ) {
                    return target
                }

                // Поддерживаем вариант с URL-encoded схемой.
                if (
                    Regex("(?i)^https?%3a%2f%2f")
                        .containsMatchIn(target)
                ) {
                    return try {
                        URLDecoder.decode(target, UTF_8)
                    } catch (_: IllegalArgumentException) {
                        throw IOException("INVALID_RESULT_URL")
                    }
                }

                throw IOException("INVALID_RESULT_URL")
            }
        }

        // Прямая ссылка не нуждается в unwrap.
        // Её допустимость проверяется отдельно в canonical().
        return raw
    }

    /**
     * Проверяет и нормализует найденный onion URL.
     *
     * Требует допустимый onion v3 host, запрещает userinfo
     * и использует [TorPolicy] для нормализации.
     *
     * Для проверки создаётся политика с режимом ONLY
     * и разрешённым host конкретного кандидата.
     *
     * Это проверка кандидата, а не разрешение crawler
     * посещать данный host.
     *
     * Fragment не включается в итоговый canonical URL.
     *
     * @param value извлечённый URL результата поиска.
     * @return нормализованный URL кандидата.
     *
     * @throws IOException при нарушении ограничений URL.
     */
    private fun canonical(value: String): String {
        if (value.length > 2048) {
            throw IOException("INVALID_RESULT_URL")
        }

        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw IOException("INVALID_RESULT_URL")
        }

        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw IOException("INVALID_RESULT_URL")

        if (
            !TorPolicy.validOnion(host) ||
            uri.rawUserInfo != null
        ) {
            throw IOException("INVALID_RESULT_URL")
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: throw IOException("INVALID_RESULT_URL")

        val authority = host +
                if (uri.port == -1) "" else ":${uri.port}"

        // Отклоняем нестандартные представления authority,
        // которые могут привести к неоднозначной трактовке URL.
        if (
            !uri.rawAuthority.equals(
                uri.host +
                        if (uri.port == -1) "" else ":${uri.port}"
            )
        ) {
            throw IOException("INVALID_RESULT_URL")
        }

        // Временная политика нужна исключительно для проверки
        // данного onion-кандидата.
        val policy = TorPolicy(
            mapOf(
                "tor.mode" to "ONLY",
                "tor.socks.host" to "127.0.0.1",
                "tor.socks.port" to 9050,
                "tor.allowed.hosts" to listOf(host)
            )
        )

        val normalized = policy.normalize(
            "$scheme://$authority${uri.rawPath.ifEmpty { "/" }}" +
                    (uri.rawQuery?.let { "?$it" } ?: "")
        )

        val valid = URI(normalized)

        return "$scheme://$host${valid.rawPath}" +
                (valid.rawQuery?.let { "?$it" } ?: "")
    }

    /** Кодирует значение параметра GET-формы в UTF-8. */
    private fun encode(value: String): String =
        URLEncoder.encode(value, UTF_8)

    /**
     * Проверяет флаг прерывания текущего потока.
     *
     * Позволяет отменить discovery до/после HTTP-запроса
     * и во время обработки результатов.
     *
     * Не заменяет таймауты и отмену внутри транспорта.
     *
     * @throws InterruptedIOException если поток прерван.
     */
    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("DISCOVERY_CANCELLED")
        }
    }
}
