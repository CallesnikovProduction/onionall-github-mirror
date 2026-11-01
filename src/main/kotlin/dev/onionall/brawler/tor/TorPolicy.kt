package dev.onionall.brawler.tor

import org.apache.stormcrawler.Metadata
import java.net.URI
import java.security.MessageDigest
import java.io.IOException

/**
 * Политика допустимости URL-адресов для Tor-only обхода ресурсов.
 *
 * Отвечает за:
 * - проверку обязательных настроек Tor и SOCKS-прокси;
 * - формирование списка разрешённых onion-хостов;
 * - проверку и нормализацию целевых URL;
 * - проверку структуры onion v3 адресов;
 * - отклонение метаданных с потенциально опасными ключами.
 *
 * Использует модель разрешённых адресов (allowlist):
 * URL допускается только в том случае, если его hostname
 * явно указан в конфигурации [tor.allowed.hosts].
 *
 * Проверка адресов выполняется локально, без DNS-запросов.
 * Класс не устанавливает сетевые соединения и самостоятельно
 * не гарантирует маршрутизацию трафика через Tor.
 *
 * Ошибочная конфигурация приводит к отказу при создании объекта.
 * Отклонённые URL и метаданные приводят к [IOException].
 *
 * @param config конфигурация приложения и StormCrawler.
 * @author Stephan Kolesnikov
 * @author Codex (gpt-6.1-sol-medium)
 */
class TorPolicy(
    config: Map<String, Any>
) {

    /**
     * Имя хоста SOCKS-прокси, через который предполагается
     * выполнять Tor-соединения.
     *
     * Читается из `tor.socks.host`.
     *
     * Отсутствие настройки приводит к ошибке инициализации.
     * Допустимый формат проверяется в блоке инициализации.
     *
     * Само наличие этого значения не обеспечивает
     * использование прокси сетевыми клиентами.
     */
    val proxyHost = config["tor.socks.host"] as? String
                        ?: error("Missing tor.socks.host")

    /**
     * TCP-порт SOCKS-прокси.
     *
     * Читается из `tor.socks.port` и преобразуется в число.
     * Допустимый диапазон: `1..65535`.
     *
     * Отсутствие или некорректный формат настройки
     * приводят к ошибке инициализации.
     */
    val proxyPort = config["tor.socks.port"]
                        ?.toString()
                        ?.toIntOrNull()
                        ?: error("Missing tor.socks.port")

    /**
     * Множество явно разрешённых onion v3 хостов.
     *
     * Формируется из `tor.allowed.hosts`.
     *
     * Каждый элемент проверяется методом [validOnion].
     * Дубликаты удаляются при преобразовании в множество.
     *
     * Политика требует от 1 до 16 уникальных хостов.
     * Адреса вне этого множества отклоняются методом [normalize].
     */
    private val hosts = (config["tor.allowed.hosts"] as? List<*>)
                            ?.map {
                                require(it is String && validOnion(it)) {
                                    "Invalid approved onion hostname"
                                }
                                it
                            }
                            ?.toSet()
                            ?: error("Missing tor.allowed.hosts")

    /**
     * Проверяет обязательные инварианты конфигурации.
     *
     * Создание политики допускается только при:
     * - `tor.mode == "ONLY"`;
     * - допустимом формате SOCKS-хоста;
     * - TCP-порте в диапазоне `1..65535`;
     * - наличии от 1 до 16 разрешённых onion-хостов.
     *
     * Нарушение любого условия прерывает инициализацию.
     */
    init {
        require(config["tor.mode"] == "ONLY") {
            "tor.mode must be ONLY"
        }

        require(proxyHost.matches(Regex("[a-zA-Z0-9.-]+"))
                && proxyHost.isNotBlank()
                && !proxyHost
                    .trimEnd('.')
                    .lowercase()
                    .endsWith(".onion")
        ) {
            "Invalid SOCKS host"
        }

        require(proxyPort in 1..65535) {
            "Invalid SOCKS port"
        }

        require(hosts.isNotEmpty() && hosts.size <= 16) {
            "Require 1..16 approved hosts"
        }
    }

    /**
     * Проверяет и нормализует целевой URL.
     *
     * URL допускается только при выполнении всех условий:
     * - схема строго `http` или `https`;
     * - отсутствуют пользовательские данные (userinfo);
     * - hostname присутствует в разрешённом множестве [hosts];
     * - hostname соответствует структуре onion v3;
     * - порт отсутствует либо соответствует стандартному
     *   порту выбранной схемы (80 или 443);
     * - authority имеет строго ожидаемое представление.
     *
     * После проверки нормализует путь средствами [URI.normalize],
     * исключает fragment и возвращает ASCII-представление URL.
     *
     * Метод не выполняет DNS-разрешение и сетевые запросы.
     *
     * @param url исходный URL для проверки.
     * @return нормализованный URL, прошедший проверки политики.
     * @throws IOException если URL отклонён политикой.
     *
     * TODO(TP-01): Проверить нестандартные формы URI,
     *  включая пустой/null path, opaque URI и encoded delimiters.
     *
     * TODO(TP-03): Подтвердить тестами, что нормализация
     *  не меняет смысл адреса и не нарушает ограничения scope.
     */
    fun normalize(url: String): String {
        val u = try {
            URI(url)
        } catch (_: Exception) {
            throw IOException("SCOPE_REJECTED")
        }

        if (
            u.scheme !in setOf("http", "https") ||
            u.rawUserInfo != null ||
            u.host !in hosts ||
            !validOnion(u.host ?: "") ||
            u.port !in setOf(
                -1,
                if (u.scheme == "http") 80 else 443
            ) ||
            u.rawAuthority != u.host
                + (if (u.port == -1) "" else ":${u.port}")
        ) {
            throw IOException("SCOPE_REJECTED")
        }

        val rawPath = u.rawPath.ifEmpty { "/" }
        val rawQuery = u.rawQuery?.let { "?$it" } ?: ""

        val pathUri = URI("${u.scheme}://${u.rawAuthority}$rawPath$rawQuery")
        val normalized = pathUri.normalize()
        // OkHttp also collapses encoded dot segments; robots rules see the policy
        // URL first. Reject residual segments rather than fetching another path.
        if (normalized.rawPath != pathUri.rawPath || pathUri.rawPath.split('/').any {
                it.replace("%2e", ".", ignoreCase = true) in setOf(".", "..")
            }) throw IOException("SCOPE_REJECTED")
        return normalized.toASCIIString()
    }

    /**
     * Проверяет метаданные обнаруженной ссылки.
     *
     * Отклоняет метаданные, если хотя бы один ключ:
     * - содержит подстроку `proxy`;
     * - начинается с `http.`;
     * - содержит подстроку `set-header`.
     *
     * Сравнение выполняется без учёта регистра.
     *
     * Проверяются только имена ключей, не их значения.
     * Это предварительный защитный механизм, а не полная
     * гарантия невозможности изменения сетевого поведения.
     *
     * @param metadata метаданные обнаруженной ссылки.
     * @throws IOException при обнаружении запрещённого ключа.
     *
     * TODO(TP-04): Сопоставить проверки с реальными ключами,
     *  которые StormCrawler и TorProtocol используют для
     *  управления прокси, HTTP-заголовками и запросами.
     */
    fun checkMetadata(metadata: Metadata) {
        if (
            metadata.keySet().any {
                val k = it.lowercase()

                "proxy" in k ||
                        k.startsWith("http.") ||
                        "set-header" in k
            }) throw IOException("PROXY_POLICY_VIOLATION")
    }

    companion object {

        /**
         * Проверяет структурную корректность onion v3 hostname.
         *
         * Алгоритм:
         * 1. Проверяет формат: 56 символов `Base32` + `.onion`.
         * 2. Декодирует 56 символов `Base32` в 35 байт.
         * 3. Проверяет байт версии (`0x03`).
         * 4. Вычисляет SHA3-256 от последовательности:
         *    `.onion checksum` + public key + version.
         * 5. Сравнивает первые два байта хеша с checksum
         *    из декодированного адреса.
         *
         * Структура декодированных данных:
         * - байты `0..31`: публичный ключ;
         * - байты `32..33`: контрольная сумма;
         * - байт `34`: версия протокола.
         *
         * Успешная проверка подтверждает согласованность
         * формата адреса, но не существование onion-сервиса,
         * его доступность или доверенность.
         *
         * @param host hostname с суффиксом `.onion`.
         * @return `true` при корректной структуре onion v3,
         * иначе `false`.
         *
         * TODO(TP-02): Проверить алгоритм по независимым тестовым векторам.
         *  Base32-декодирование уже выделено в decodeBase32.
         */
        @JvmStatic fun validOnion(host: String): Boolean {
            if (!host.matches(Regex("[a-z2-7]{56}\\.onion"))) return false

            val bytes = decodeBase32(host)
            val raw = bytes.toByteArray()

            if (raw[34] != 3.toByte()) return false

            // checksum = SHA3-256(".onion checksum" || publicKey || version)[0:2]
            val checksum = MessageDigest
                            .getInstance("SHA3-256")
                            .digest(".onion checksum".toByteArray(Charsets.US_ASCII)
                                    + raw.copyOfRange(0, 32)
                                    + byteArrayOf(3)
                            )

            return raw[32] == checksum[0] && raw[33] == checksum[1]
        }

        @JvmStatic private fun decodeBase32(hostString: String): List<Byte> {
            val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
            var bits = 0
            var value = 0

            val bytes = ArrayList<Byte>()

            // Декодинг Base32: каждый символ Base32 = 5 бит
            for (c in hostString.take(56)) {
                value = (value shl 5) or alphabet.indexOf(c)
                bits += 5

                if (bits >= 8) {
                    bits -= 8
                    bytes.add((value shr bits).toByte())
                }
            }

            return bytes
        }
    }
}
