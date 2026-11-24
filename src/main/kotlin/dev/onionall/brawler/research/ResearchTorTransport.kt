package dev.onionall.brawler.research

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import dev.onionall.brawler.tor.TorProtocol
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import org.apache.storm.Config
import org.apache.stormcrawler.Metadata

/**
 * Tor-only транспорт для явно разрешённых запросов
 * к поисковому провайдеру Research Discovery.
 *
 * Адаптирует существующий [TorProtocol] к интерфейсу
 * [ProviderTransport], не реализуя отдельный HTTP-клиент.
 *
 * При создании транспорта:
 * - загружает ограниченный по размеру runtime descriptor;
 * - извлекает адрес и порт SOCKS endpoint;
 * - проверяет формат параметров подключения;
 * - конфигурирует TorProtocol в режиме ONLY;
 * - ограничивает список разрешённых hosts провайдером Ahmia;
 * - запрещает автоматические HTTP redirects;
 * - устанавливает лимиты времени и размера ответа.
 *
 * Транспорт предназначен только для получения ответа
 * разрешённого discovery-провайдера.
 *
 * Адреса, обнаруженные внутри поисковой выдачи, не становятся
 * автоматически разрешёнными целями запросов или crawl.
 *
 * Настройки robots обычного crawler не изменяются:
 * provider authorization не распространяется на crawler robots.
 *
 * Гарантии маршрутизации через Tor, remote DNS и запрета
 * DIRECT обеспечиваются реализацией [TorProtocol].
 *
 * После использования транспорт необходимо закрывать,
 * чтобы освободить ресурсы протокола.
 *
 * @param runtime путь к JSON-дескриптору параметров runtime.
 *
 * @see ProviderTransport
 * @see ProviderResponse
 * @see TorProtocol
 * @see AhmiaDiscoveryProvider
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
class ResearchTorTransport(
    runtime: Path
) : ProviderTransport, Closeable {

    /**
     * Существующий протокол сетевого взаимодействия через Tor.
     *
     * Research не создаёт альтернативный HTTP transport
     * и использует общую инфраструктуру приложения.
     */
    private val protocol = TorProtocol()

    init {

        // Принимаем только обычный файл, а не symbolic link
        // в конечном компоненте пути.
        //
        // Максимальный размер runtime descriptor — 32 KiB.
        require(
            Files.isRegularFile(runtime, NOFOLLOW_LINKS) &&
                    Files.size(runtime) <= 32768
        ) {
            "Require regular runtime descriptor <=32KiB"
        }

        // Загружаем конфигурацию как JSON-объект.
        //
        // Конкретная схема дескриптора далее проверяется
        // при извлечении SOCKS endpoint.
        val values = ObjectMapper().readValue(
            Files.readAllBytes(runtime),
            object : TypeReference<Map<String, Any?>>() {}
        )

        // Адрес SOCKS endpoint обязателен.
        val host = values["socks_host"] as? String
            ?: throw IOException("Missing SOCKS endpoint")

        // Порт SOCKS endpoint также обязателен.
        val port = values["socks_port"] as? Number
            ?: throw IOException("Missing SOCKS port")

        // Допускаем только адрес в ограниченном символьном
        // формате и исключаем .onion как SOCKS endpoint.
        //
        // Это синтаксическая проверка, а не проверка
        // доступности или доверенности самого endpoint.
        require(
            host.matches(
                Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")
            ) &&
                    !host.endsWith(".onion", true)
        )

        // Порт должен представляться целым числом
        // и принадлежать допустимому TCP-диапазону.
        require(
            port.toDouble() == port.toInt().toDouble() &&
                    port.toInt() in 1..65535
        )

        val config = Config()

        // Создаём отдельную конфигурацию для provider access.
        //
        // Важно не путать разрешённый поиск у Ahmia
        // с разрешением на crawl адресов из результатов.
        config.putAll(
            mapOf(
                // Только Tor; режим DIRECT не разрешается.
                "tor.mode" to "ONLY",

                // SOCKS endpoint из runtime descriptor.
                "tor.socks.host" to host,
                "tor.socks.port" to port.toInt(),

                // Разрешённый host ограничен провайдером.
                // Result targets сюда не добавляются.
                "tor.allowed.hosts" to listOf(
                    AhmiaDiscoveryProvider.HOST
                ),

                // Настройки интеграции с StormCrawler.
                "protocol.md.prefix" to "protocol.",
                "http.agent.name" to "OnionallResearch",

                // Ограничения сетевого запроса.
                "http.timeout" to 30000,

                // Максимальный размер ответа — 1 MiB.
                "http.content.limit" to 1048576,

                // Не включаем обход robots через skip.
                // Фактическая интерпретация настройки
                // принадлежит реализации TorProtocol.
                "http.robots.file.skip" to false,

                // Не следуем автоматически за redirects.
                "http.allow.redirects" to false,

                // Не отключаем проверку доверия TLS.
                "http.trust.everything" to false,

                // Специальный режим HTML discovery.
                // Его применение обеспечивает TorProtocol.
                "tor.discovery.html.only" to true
            )
        )

        // При ошибке конфигурации освобождаем ресурсы
        // TorProtocol перед повторным выбросом исключения.
        try {
            protocol.configure(config)
        } catch (failure: Exception) {
            protocol.cleanup()
            throw failure
        }
    }

    /**
     * Выполняет запрос через сконфигурированный [TorProtocol].
     *
     * Передаёт URL в существующий механизм StormCrawler
     * вместе с новым пустым объектом [Metadata].
     *
     * Результат преобразуется в [ProviderResponse]:
     * - status — HTTP status code;
     * - contentType — первое значение content-type;
     * - body — полученные байты ответа;
     * - responseUrl — переданный в метод URL.
     *
     * ВАЖНО:
     * Метод не выполняет дополнительную нормализацию URL,
     * не извлекает поисковые результаты и не посещает
     * обнаруженные в них адреса.
     *
     * Ограничение разрешённых hosts, запрет DIRECT,
     * обработка redirects и сетевые таймауты делегированы
     * сконфигурированному TorProtocol.
     *
     * @param url URL запроса к discovery-провайдеру.
     * @return ответ провайдера с кодом, MIME metadata,
     *         телом ответа и переданным URL.
     */
    override fun get(url: String): ProviderResponse {

        // Сетевое взаимодействие выполняет TorProtocol.
        // Пустая Metadata не добавляет crawl-specific
        // контекст к provider request.
        val response = protocol.getProtocolOutput(
            url,
            Metadata()
        )

        // Сохраняем исходные байты и статус ответа.
        // Парсинг HTML выполняется уровнем провайдера.
        return ProviderResponse(
            response.statusCode,
            response.metadata.getFirstValue("content-type"),
            response.content,
            url
        )
    }

    /**
     * Освобождает ресурсы [TorProtocol].
     *
     * Предназначен для использования через use { ... },
     * в том числе при возникновении исключения
     * во время обращения к провайдеру.
     */
    override fun close() = protocol.cleanup()
}
