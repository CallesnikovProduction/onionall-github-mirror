package dev.onionall.brawler.tor

import crawlercommons.robots.BaseRobotRules
import crawlercommons.robots.SimpleRobotRulesParser
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.storm.Config
import org.apache.stormcrawler.Metadata
import org.apache.stormcrawler.protocol.Protocol
import org.apache.stormcrawler.protocol.ProtocolResponse
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit

/**
 * Реализация сетевого протокола StormCrawler для обхода onion-ресурсов.
 *
 * Выполняет HTTP(S)-запросы через фиксированный SOCKS-прокси,
 * заданный конфигурацией Tor, с использованием OkHttp.
 *
 * Основные обязанности:
 * - Создавать HTTP-клиент с явно заданным SOCKS-прокси.
 * - Проверять целевые URL и метаданные через [TorPolicy].
 * - Запрещать локальное DNS-разрешение через настроенный [Dns].
 * - Отключать автоматические редиректы и повторные запросы OkHttp.
 * - Ограничивать время выполнения запросов и размер тела ответа.
 * - Загружать и разбирать `robots.txt`.
 * - Кешировать правила `robots.txt` с ограниченным временем жизни.
 * - Освобождать сетевые ресурсы при завершении работы.
 *
 * Модель безопасности:
 * - Целевые адреса должны соответствовать allowlist политики Tor.
 * - HTTP-клиент использует фиксированный SOCKS endpoint.
 * - Автоматическое следование HTTP-редиректам отключено.
 * - Ошибки загрузки `robots.txt` не трактуются как разрешение обхода.
 *
 * Ограничения:
 * - Наличие SOCKS-прокси в конфигурации само по себе не доказывает
 *   отсутствие сетевых обходов на уровне всей системы.
 * - Фактическое поведение DNS, TLS и SOCKS требует интеграционных тестов.
 * - Кеш `robots.txt` существует только в памяти экземпляра протокола.
 *
 * Жизненный цикл:
 * 1. StormCrawler создаёт экземпляр протокола.
 * 2. Вызывает [configure].
 * 3. Использует [getProtocolOutput] и [getRobotRules].
 * 4. Вызывает [cleanup] при завершении работы.
 *
 * @author Stephan Kolesnikov
 * @author Codex (gpt-6.1-sol-medium)
 */
class TorProtocol : Protocol {

    /**
     * Политика допустимости целевых URL и метаданных.
     *
     * Создаётся при [configure] из конфигурации Storm.
     * Используется перед выполнением HTTP-запросов.
     */
    private lateinit var policy: TorPolicy

    private val lifecycle = Any()
    @Volatile private var closed = false
    private val calls = mutableSetOf<Call>()


    /**
     * HTTP-клиент `OkHttp` с фиксированным SOCKS-прокси.
     *
     * Настраивается один раз при [configure].
     * Автоматические редиректы и повторы запросов отключены.
     *
     * TODO(TPR-01): BLOCKED: controlled Tor end-to-end + resolver guard and outage denial must pass; local SOCKS tests alone are insufficient.
     */
    private lateinit var client: OkHttpClient

    /**
     * Имя пользовательского агента HTTP-клиента.
     *
     * Читается из `http.agent.name`.
     * Используется в заголовке User-Agent и при разборе `robots.txt`.
     */
    private lateinit var agent: String

    /**
     * Максимальный допустимый размер тела HTTP-ответа в байтах.
     *
     * Значение по умолчанию: `65 536 байт`.
     *
     * Допустимый диапазон конфигурации: `1..1 048 576 байт`.
     *
     * Превышение лимита приводит к [IOException].
     */
    private var limit = 65536
    private var htmlOnly = false

    /**
     * Локальный кеш правил `robots.txt`.
     *
     * Ключ: базовый URL вида `scheme://authority`.
     *
     * Значение:
     * - время загрузки по [System.nanoTime];
     * - разобранные правила `BaseRobotRules`.
     *
     * Время жизни записи: `10 минут`.
     *
     * Максимальное количество записей: `16`.
     *
     * TODO(TPR-02): BLOCKED: verify TTL expiry and concurrent misses with a deterministic clock test before changing cache synchronization.
     */
    private val robots = LinkedHashMap<String, Pair<Long, BaseRobotRules>>()

    /**
     * Инициализирует сетевой протокол.
     *
     * Создаёт [TorPolicy], проверяет HTTP-настройки и формирует
     * экземпляр [OkHttpClient] с фиксированным SOCKS-прокси.
     *
     * Настройки:
     * - `http.timeout`: общий лимит времени, `1..120000 мс`;
     * - `http.content.limit`: лимит тела, `1..1048576 байт`;
     * - `http.agent.name`: имя `crawler`;
     * - `http.robots.file.skip`: не должен быть `true`;
     * - `http.allow.redirects`: не должен быть `true`;
     * - `http.trust.everything`: не должен быть `true`.
     *
     * Время ожидания применяется к `connect`, `read`, `write` и `call`.
     *
     * @param conf конфигурация Apache Storm.
     *
     * TPR-03: configure и cleanup сериализованы отдельным lifecycle lock.
     * После cleanup экземпляр терминален; для restart нужен новый экземпляр.
     * TODO(TPR-03): BLOCKED: inject partial prepare failure in a real topology and prove cleanup of every created protocol instance.
     *
     * TODO(TPR-04): BLOCKED: enumerate upstream configuration aliases with negative tests before claiming complete bypass prevention.
     */
    override fun configure(conf: Config): Unit = synchronized(lifecycle) {
        check(!closed) { "Protocol closed" }
        check(!this::client.isInitialized) {
            "Protocol already configured"
        }

        policy = TorPolicy(conf)
        htmlOnly = conf["tor.discovery.html.only"] == true

        // SimpleFetcherBolt 3.7.0 prefixes response metadata at the merge boundary.
        // Without this setting its fallback is empty, allowing remote control-key collisions.
        val metadataPrefix = conf[ProtocolResponse.PROTOCOL_MD_PREFIX_PARAM] as? String
        require(!metadataPrefix.isNullOrBlank()) { "Require nonempty protocol.md.prefix" }

        val timeout =
            if (conf.containsKey("http.timeout"))
                conf["http.timeout"]
                    .toString()
                    .toLongOrNull()
                    ?: error("Invalid timeout")
            else 10000

        limit =
            if (conf.containsKey("http.content.limit"))
                conf["http.content.limit"]
                    .toString()
                    .toIntOrNull()
                    ?: error("Invalid content limit")
            else 65536

        require(timeout in 1..120000 && limit in 1..1048576) {
            "Invalid bounded timeout/content limit"
        }

        require(
            conf["http.robots.file.skip"] != true &&
                conf["http.allow.redirects"] != true &&
                conf["http.trust.everything"] != true
        ) {
            "Unsafe HTTP configuration"
        }

        agent = conf["http.agent.name"] as? String ?: error("Missing http.agent.name")

        require(agent.matches(Regex("[A-Za-z_-]+"))) { "Invalid crawler agent" }

        // TODO(TPR-05): BLOCKED: replacement test must prove a new protocol resolves the new gateway IP; existing instances intentionally retain their endpoint.
        val endpoint = InetSocketAddress(
            policy.proxyHost, // hostname
            policy.proxyPort             // port
        )

        require(!endpoint.isUnresolved) {
            "Invalid SOCKS endpoint"
        }

        // Сетевой транспорт Onionall:
        // принудительно направляются запросы через SOCKS-прокси
        // и минимизация неявного поведения HTTP-клиента
        client = OkHttpClient.Builder()
            .proxy(
                /*
                * Допустим, конфигурация:
                * tor.socks.host: 127.0.0.1
                * tor.socks.port: 9050
                *
                * Тогда OkHttp будет подрубаться к 127.0.0.1:9050, а не непосредственно к onion-сервису
                * */
                Proxy(
                    Proxy.Type.SOCKS,
                    endpoint
                )

                /*
                * В стандартном SOCKS-маршруте OkHttp использует unresolved-адрес назначения,
                * позволяя передать hostname прокси
                * */
            )
            // Переиспользование существующих TCP-соединений,
            // следовательно экономия времени соединений (TCP, SOCKS, TLS, HTTP)
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 0, // Свободных в пуле НЕ ДОЛЖНО быть
                    keepAliveDuration  = 1, // Длительность хранения свободных соединений (особо не играет роли)
                    TimeUnit.SECONDS
                )

            )
            .dns(
                /*
                * Вместо настоящего DNS-резольвера установлен постоянный выброс исключения
                *
                * Однако, тонкость: при обычном SOCKS-маршруте OkHttp
                * НЕ ДОЛЖЕН вызывать DNS-resolver для целевого имени хоста.
                *
                * Адрес передаётся SOCKS-прокси как unresolved
                * */
                Dns {
                    throw UnknownHostException("LOCAL_DNS_FORBIDDEN")
                }
            )
            // Запрет редиректов, поскольку каждый URL должен пройти отдельную проверку политики
            .followRedirects(false)                           // Отключение авто-следованиям редиректам
            .followSslRedirects(false) // Отключение отдельного управления редиректами
            // Управление ретраями остаётся за планировщиком StormCrawler
            .retryOnConnectionFailure(false)
            // 4 разных таймаута
            .connectTimeout( // ограничение времени установления соединения с прокси
                timeout,
                TimeUnit.MILLISECONDS
            )
            .readTimeout(    // ограничение ожидания данных при операции чтения из соединения
                timeout,
                TimeUnit.MILLISECONDS
            )
            .writeTimeout(   // ограничение операции записи в соединение
                timeout,
                TimeUnit.MILLISECONDS
            )
            .callTimeout(    // общий срок выполнения вызова
                timeout,
                TimeUnit.MILLISECONDS
            )
            .build()

        // TODO(TPR-01): BLOCKED: controlled Tor end-to-end + resolver guard and outage denial must pass; local SOCKS tests alone are insufficient.

        // TODO(TPR-14): BLOCKED: controlled TLS/HTTP2/auth challenge fixtures must demonstrate bounded follow-ups on SOCKS only.
    }

    /**
     * Выполняет HTTP(S)-запрос к разрешённому onion-ресурсу.
     *
     * Алгоритм:
     * 1. Проверяет метаданные запроса через [TorPolicy].
     * 2. Проверяет и нормализует целевой URL.
     * 3. Выполняет запрос через настроенный [OkHttpClient].
     * 4. Копирует HTTP-заголовки ответа, исключая исходные Location.
     * 5. Для 3xx, кроме 304, экспортирует единственный проверенный canonical Location.
     * 6. Присваивает категорию HTTP-ответу.
     * 7. Читает тело с ограничением по размеру.
     * 8. Возвращает ProtocolResponse.
     *
     * Автоматическое следование редиректам отключено.
     * Метод не выполняет дополнительный запрос по Location.
     *
     * Категории ответа:
     * - HTTP_OK: статус ниже 300;
     * - REDIRECT: статус 300..399;
     * - HTTP_ERROR: статус 400 и выше.
     *
     * @param url целевой URL.
     * @param metadata метаданные запроса.
     * @return HTTP-статус, тело и метаданные ответа.
     * @throws IOException при отклонении адреса,
     * сетевой ошибке или превышении лимита тела.
     *
     * TPR-06: Дубли, пустой или отсутствующий Location отклоняются для redirect.
     *  Downstream получает только проверенный канонический target; 304 не требует Location.
     *
     * TODO(TPR-07): BLOCKED: controlled TLS and every SOCKS reply-code test needed; ONION_UNREACHABLE retains cause, not a precise Tor diagnosis.
     *
     * TPR-08: Непустой protocol.md.prefix обязателен при configure;
     *  prefix добавляет SimpleFetcherBolt, а не этот протокол.
     * TODO(TPR-08): BLOCKED: execute oversized aggregate-header tests against pinned OkHttp before claiming a header-memory bound.
     */
    override fun getProtocolOutput(
        url: String,
        metadata: Metadata
    ): ProtocolResponse {
        if (closed) throw IOException("PROTOCOL_CLOSED")
        policy.checkMetadata(metadata)
        val safe = policy.normalize(url)
        val call = synchronized(lifecycle) {
            if (closed) throw IOException("PROTOCOL_CLOSED")
            client.newCall(Request.Builder()
                    .url(safe)
                    .header(
                        name  = "User-Agent",
                        value = agent
                    )
                    .apply { if (htmlOnly) header("Accept-Encoding", "identity") }
                    .build()
                ).also { calls.add(it) }
        }
        try {
            call.execute().use { response ->
                    val md = Metadata()
                    response.headers.forEach { (name,value) ->
                        // OkHttp reads the last Location, StormCrawler reads the first.
                        // Never export an unchecked representation (including on 304).
                        if (!name.equals("Location", ignoreCase = true)) {
                            md.addValue(name.lowercase(), value)
                        }
                    }

                    if (response.code in 300..399 && response.code != 304) {
                        val locations = response.headers.values("Location")
                        if (locations.size != 1 || locations.single().isBlank()) {
                            throw IOException("SCOPE_REJECTED")
                        }
                        try {
                            val target = policy.normalize(URI(safe)
                                .resolve(locations.single())
                                .toString()
                            )
                            md.setValue("location", target)
                        } catch (e: IllegalArgumentException) {
                            throw IOException("SCOPE_REJECTED", e)
                        }
                    }

                    md.setValue(
                        "tor.category",
                        if (response.code >= 400)
                            "HTTP_ERROR"
                        else if (response.code in 300..399)
                            "REDIRECT"
                        else "HTTP_OK"
                    )

                    if (htmlOnly && response.code !in 300..399) {
                        val types = response.headers.values("Content-Type")
                        val media = types.singleOrNull()?.toMediaTypeOrNull()
                        val mime = media?.let { "${it.type}/${it.subtype}" }
                        val encodings = response.headers.values("Content-Encoding")
                        val identity = encodings.isEmpty() || (encodings.size == 1 && encodings.single().equals("identity", ignoreCase = true))
                        val robotsRequest = URI(safe).rawPath == "/robots.txt"
                        val allowed = if (robotsRequest) mime == "text/plain" else mime == "text/html"
                        if (!allowed || !identity) { call.cancel(); throw IOException("METADATA_ONLY") }
                    }

                    if (htmlOnly && response.code in 300..399) call.cancel()
                    val content = if (htmlOnly && response.code in 300..399) byteArrayOf() else response
                        .body
                        ?.byteStream()
                        ?.readNBytes(limit+1)
                        ?: byteArrayOf()

                    if (content.size > limit) { if (htmlOnly) call.cancel(); throw IOException("BODY_LIMIT_EXCEEDED") }
                    if (htmlOnly && URI(safe).rawPath != "/robots.txt" && response.code !in 300..399) {
                        val prefix = content.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart().lowercase()
                        if (!(prefix.startsWith("<!doctype html") || prefix.startsWith("<html") ||
                              prefix.startsWith("<head") || prefix.startsWith("<body") || prefix.startsWith("<article")))
                            throw IOException("METADATA_ONLY")
                    }
                    return ProtocolResponse(
                        content,
                        response.code,
                        md
                    )
                }
        } catch (e: SocketTimeoutException) {
            if (closed) throw IOException("PROTOCOL_CLOSED", e)
            throw IOException("REQUEST_TIMEOUT", e)
        } catch (e: java.io.InterruptedIOException) {
            if (closed) throw IOException("PROTOCOL_CLOSED", e)
            throw IOException("REQUEST_TIMEOUT", e)
        } catch (e: ConnectException) {
            if (closed) throw IOException("PROTOCOL_CLOSED", e)
            throw IOException("SOCKS_FAILURE", e)
        } catch (e: IOException) {
            if (closed) throw IOException("PROTOCOL_CLOSED", e)
            if (e.message in setOf(
                    "SCOPE_REJECTED",
                    "BODY_LIMIT_EXCEEDED",
                    "METADATA_ONLY"
            )) throw e
            throw IOException("ONION_UNREACHABLE", e)
        } finally {
            synchronized(lifecycle) { calls.remove(call) }
        }
    }

    /**
     * Возвращает правила `robots.txt` для заданного URL.
     *
     * Алгоритм:
     * 1. Проверяет и нормализует URL.
     * 2. Определяет базовый адрес ресурса.
     * 3. Ищет действующие правила в локальном кеше.
     * 4. При отсутствии записи загружает `/robots.txt`.
     * 5. Требует HTTP-статус 200.
     * 6. Отклоняет явно HTML-ответ, затем использует SimpleRobotRulesParser.
     * 7. Сохраняет результат в кеше.
     *
     * Применяется fail-closed подход:
     * отсутствие доступного `robots.txt` не считается
     * автоматическим разрешением обхода.
     *
     * Метод синхронизирован для последовательного
     * доступа к общему кешу правил.
     *
     * @param url URL ресурса, для которого нужны правила.
     * @return разобранные правила `robots.txt`.
     * @throws IOException если загрузка `robots.txt`
     * завершилась ошибкой или статус отличен от 200.
     *
     * TPR-09: Строгая политика M2 принимает только HTTP 200.
     *
     * TPR-10: Размер ограничен транспортом, явно HTML отклоняется до parser.
     *  Пустой robots и отсутствие подходящей UA-группы допускаются parser.
     * Malformed UTF-8/BOM-selected UTF-16 and parser runtime errors fail closed.
     */
    @Synchronized
    override fun getRobotRules(url: String): BaseRobotRules {
        if (closed) throw IOException("PROTOCOL_CLOSED")
        val safe = URI(policy.normalize(url))
        val base = "${safe.scheme}://${safe.rawAuthority}"

        robots[base]?.let {
            if (System.nanoTime() - it.first < TimeUnit.MINUTES.toNanos(10))
                return it.second
        }

        val response = try {
            getProtocolOutput("$base/robots.txt",Metadata())
        } catch (e: IOException) {
            if (closed) throw IOException("PROTOCOL_CLOSED", e)
            throw IOException("ROBOTS_UNAVAILABLE",e)
        }

        if (response.statusCode != 200) throw IOException("ROBOTS_UNAVAILABLE")

        val content = response.content
        // Match crawler-commons 1.6 BOM selection for validation and HTML detection.
        // Reject malformed input before the parser can replace bytes in policy directives.
        val charset = if (content.size >= 2 && (
                (content[0] == 0xff.toByte() && content[1] == 0xfe.toByte()) ||
                (content[0] == 0xfe.toByte() && content[1] == 0xff.toByte())))
            Charsets.UTF_16 else Charsets.UTF_8
        val text = try {
            charset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(content)).toString().removePrefix("\uFEFF")
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw IOException("ROBOTS_UNAVAILABLE", e)
        }
        val htmlType = response.metadata.getValues("content-type")?.any {
            it.substringBefore(';').trim().lowercase() in setOf("text/html", "application/xhtml+xml")
        } == true
        // Skip only document preamble, not robots directives. Iteration avoids a
        // recursive regex repetition over attacker-controlled comment sequences.
        val preamble = Regex("(?is)\\s+|<!--.*?-->|<\\?xml\\s.*?\\?>").toPattern().matcher(text)
        var start = 0
        while (preamble.region(start, text.length).lookingAt()) start = preamble.end()
        val htmlDocument = Regex(
            "(?is)\\A(?:<!doctype\\s+html(?:\\s|>)|<(?:html|head|body)(?:\\s|>))"
        ).containsMatchIn(text.substring(start))
        if (htmlType || htmlDocument) throw IOException("ROBOTS_UNAVAILABLE")

        // TPR-15: Exact agent, wildcard and no matching group have local regressions.
        // RFC-wide compliance remains the dependency's contract.
        val rules = try { SimpleRobotRulesParser()
            .parseContent(
                "$base/robots.txt",
                response.content,
                "text/plain",
                listOf(agent.lowercase())
            ) } catch (e: RuntimeException) {
                throw IOException("ROBOTS_UNAVAILABLE", e)
            }

        if (closed) throw IOException("PROTOCOL_CLOSED")
        if (robots.size >= 16) robots.remove(robots.keys.first())

        robots[base] = Pair(
            System.nanoTime(),
            rules
        )

        return rules
    }

    /**
     * Освобождает ресурсы сетевого протокола.
     *
     * Если HTTP-клиент был инициализирован:
     * - отменяет активные запросы;
     * - удаляет простаивающие соединения из пула;
     * - останавливает executor диспетчера OkHttp.
     *
     * Затем очищает локальный кеш `robots.txt`.
     *
     * TPR-11: Локально проверены повторный cleanup, запрет новых вызовов
     * и отмена заблокированного SOCKS вызова. Call учитывается до execute,
     * поэтому cleanup отменяет и ещё не зарегистрированный в dispatcher Call.
     * TODO(TPR-11): BLOCKED: real Storm shutdown/partial-prepare test must prove ProtocolFactory ownership; unit cancellation alone is insufficient.
     */
    override fun cleanup() {
        synchronized(lifecycle) {
            closed = true
            calls.forEach { it.cancel() }
            if (this::client.isInitialized) {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
        synchronized(this) {
            robots.clear()
        }
    }
}
