
package dev.onionall.brawler.research

import dev.onionall.brawler.tor.TorPolicy
import dev.onionall.brawler.tor.UriReferences
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import org.jsoup.Jsoup

/**
 * Извлекает кандидатов на документы из проверенных архивных HTML-снимков.
 *
 * Работает исключительно с локальными данными ArchiveStore:
 * - проверяет расположение и целостность архивного HTML;
 * - анализирует ссылки <a> и <area> средствами Jsoup;
 * - определяет предполагаемый формат документа по расширению
 *   имени файла или HTML-атрибуту type;
 * - нормализует URL в соответствии с TorPolicy;
 * - формирует кандидатов с метаданными происхождения;
 * - объединяет повторные наблюдения одного кандидата.
 *
 * Класс не выполняет сетевых запросов, не запускает crawler
 * и не скачивает обнаруженные документы.
 *
 * Расширение файла, атрибут download и объявленный HTML type
 * являются только подсказками. Они не подтверждают реальный
 * MIME-тип, размер или доступность целевого ресурса.
 *
 * Проверка архивного объекта основана на его расположении
 * в content-addressed storage, размере и SHA-256.
 *
 * @see ResearchWorkflow
 * @see ResearchThreads
 * @see TorPolicy
 * @see UriReferences
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
object ResearchFiles {

    /**
     * Максимальное число уникальных файловых кандидатов,
     * возвращаемых за один вызов [extract].
     *
     * После достижения лимита обработка завершается
     * досрочным возвратом накопленных результатов.
     */
    const val MAX_CANDIDATES = 1000

    /**
     * Максимальный размер одного архивного HTML-объекта:
     * 128 KiB.
     *
     * Ограничение применяется при проверке и чтении
     * каждого отдельного архивного объекта.
     */
    private const val MAX_HTML_BYTES = 131072

    /**
     * Расширения файлов, распознаваемые как подсказки
     * о возможном документном формате.
     *
     * Расширение не является подтверждением MIME-типа.
     */
    private val extensions = setOf("pdf", "epub", "txt", "md", "markdown", "docx", "odt", "rtf")

    /**
     * MIME-типы, допускаемые в качестве HTML-подсказок
     * при обнаружении документов.
     *
     * Значения берутся из объявленного атрибута type,
     * а не из фактического HTTP-ответа целевого файла.
     */
    private val media = setOf("application/pdf", "application/epub+zip", "text/plain", "text/markdown",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text", "application/rtf", "text/rtf")

    /**
     * Читает проверенный архивный HTML для последующего
     * извлечения содержимого дискуссии.
     *
     * Проверяет:
     * - отсутствие символических ссылок в пути архива;
     * - ожидаемое content-addressed расположение объекта;
     * - регулярность файла и допустимый размер;
     * - соответствие размера архивным метаданным;
     * - соответствие SHA-256 заявленному content_hash.
     *
     * Проверка подтверждает целостность архивных байтов,
     * но не семантическую корректность HTML-документа.
     *
     * Метод не выполняет сетевых запросов.
     *
     * @param page метаданные архивной страницы.
     * @param archiveRoot корневой каталог архивного хранилища.
     * @return байты проверенного архивного объекта.
     * @throws IOException если объект отсутствует,
     *         нарушены ограничения пути, размера или хеша.
     */
    @JvmStatic @Throws(IOException::class)
    fun verifiedHtml(page: Map<String, Any?>, archiveRoot: Path): ByteArray {
        val root = archiveRoot.toAbsolutePath().normalize()
        noSymlinks(root)
        return body(page, root)
    }

    /**
     * Извлекает кандидатов на документы из архивных страниц
     * указанной исследовательской сессии.
     *
     * Для каждой входной записи:
     * 1. Проверяет идентификаторы сессии и страницы.
     * 2. Загружает актуальные метаданные через loadPage.
     * 3. Сопоставляет page ID, job ID и content hash
     *    с переданной записью.
     * 4. Читает и проверяет архивный HTML-объект.
     * 5. Извлекает ссылки на потенциальные документы.
     * 6. Формирует кандидатов и их provenance.
     *
     * Источниками подсказок о формате являются:
     * - атрибут download;
     * - имя файла в URL;
     * - объявленный HTML-атрибут type.
     *
     * Повторные наблюдения одного кандидата объединяются
     * по детерминированному file_id. Для одного кандидата
     * сохраняется не более 100 записей provenance.
     *
     * Кандидаты за пределами исходного origin также могут
     * присутствовать в результате, но получают статус
     * OUT_OF_SCOPE.
     *
     * Поля content_type и content_length остаются null,
     * поскольку фактический файл не загружается.
     *
     * @param sessionId UUID исследовательской сессии.
     * @param pages список записей архивных страниц;
     *        допускается не более 100 элементов.
     * @param loadPage функция получения метаданных страницы
     *        из ArchiveStore по её идентификатору.
     * @param archiveRoot корневой каталог архива.
     * @return список обнаруженных файловых кандидатов,
     *         ограниченный [MAX_CANDIDATES].
     * @throws IOException при ошибках проверки идентификаторов,
     *         архивных метаданных или содержимого.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun extract(sessionId: String, pages: List<Map<String, Any?>>,
                loadPage: (String) -> Map<String, Any?>, archiveRoot: Path): List<Map<String, Any?>> {
        identifier(sessionId)
        if (pages.size > 100) throw IOException("Research page inspection limit reached")
        val root = archiveRoot.toAbsolutePath().normalize()
        noSymlinks(root)
        val candidates = LinkedHashMap<String, Map<String, Any?>>()

        // Каждая запись сопоставляется с метаданными,
        // повторно полученными из архивного хранилища.
        for (row in pages) {
            val pageId = identifier(row["page_id"] as? String)
            val page = loadPage(pageId)
            val job = identifier(page["job_id"] as? String)

            // Не продолжаем извлечение, если ключевые
            // метаданные архивной страницы изменились.
            if (page["page_id"] != pageId || page["job_id"] != row["job_id"] ||
                page["content_hash"] != row["content_hash"]) {
                throw IOException("Archive snapshot changed; inspect after the worker stops")
            }

            val source = canonical(page["canonical_url"] as? String ?: throw IOException("Missing source URL"))
            val sourceUri = URI(source)
            val html = body(page, root)
            val captured = page["fetched_at"] ?: page["recorded_at"]
            val clock = timestamp(captured)
            val kind = page["source_kind"] as? String
            if (kind !in setOf("CRAWLER", "MANUAL_IMPORT")) throw IOException("Unknown archived source kind")

            // Парсим локальный архивный HTML, используя URL
            // страницы как базовый адрес документа.
            val document = Jsoup.parse(ByteArrayInputStream(html), null, source)

            // HTML base[href] влияет на разрешение относительных
            // ссылок. Некорректное значение заменяется source URI.
            val rawBase = document.selectFirst("base[href]")?.attr("href")?.trim()
            val base = try { if (rawBase == null) sourceUri else UriReferences.resolve(source, rawBase) }
            catch (_: Exception) { sourceUri }

            // Сохранённые link metadata используются для
            // уточнения времени обнаружения кандидата.
            val links = (page["links"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

            for ((ordinal, anchor) in document.select("a[href],area[href]").withIndex()) {
                val raw = anchor.attr("href")

                // absUrl() может убрать userinfo: проверяем исходный href до нормализации.
                val reference = try { URI(raw.trim()) } catch (_: Exception) { continue }
                if (reference.rawUserInfo != null || reference.scheme?.lowercase(Locale.ROOT)?.let {
                        it !in setOf("http", "https") } == true) continue

                // Преобразуем ссылку относительно HTML base
                // и проверяем полученный адрес через TorPolicy.
                val target = try { canonical(UriReferences.resolve(base.toString(), reference.toString()).toString()) } catch (_: IOException) { continue }
                val targetUri = URI(target)

                // HTML-атрибуты используются исключительно
                // как наблюдаемые подсказки о документе.
                val download = anchor.attr("download").takeIf { anchor.hasAttr("download") }
                val declared = anchor.attr("type").trim().takeIf { it.isNotEmpty() }?.take(256)
                val hintedMedia = declared?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                val urlName = targetUri.path.substringAfterLast('/').takeIf { it.isNotEmpty() }
                val filename = download?.takeIf { it.isNotBlank() } ?: urlName
                val extension = extension(filename) ?: extension(urlName)

                // Пропускаем ссылки, у которых нет ни известного
                // расширения, ни подходящей MIME-подсказки.
                if (extension !in extensions && hintedMedia !in media) continue

                // Различаем совпадение host и полного origin.
                // Для CANDIDATE требуется совпадение origin.
                val sameHost = targetUri.host == sourceUri.host
                val sameOrigin = sameHost && targetUri.scheme == sourceUri.scheme

                val edge = links.firstOrNull { it["target_url"] == target }
                val discovered = edge?.get("discovered_at")?.let(::timestamp) ?: clock

                // ID кандидата зависит от сессии, задания,
                // исходной страницы и целевого URL.
                val id = digest("$sessionId|$job|$pageId|$target".toByteArray(Charsets.UTF_8))

                // Сохраняем доказательства из конкретного
                // архивного HTML-снимка.
                val evidence = linkedMapOf<String, Any?>("snapshot_id" to page["snapshot_id"],
                    "anchor_ordinal" to ordinal, "href" to raw.take(2048),
                    "download_attribute" to download?.take(1024), "declared_content_type" to declared,
                    "captured_at" to clock)

                val old = candidates[id]

                // Повторное обнаружение того же кандидата
                // расширяет provenance, но не создаёт новый file_id.
                if (old != null) {
                    @Suppress("UNCHECKED_CAST")
                    val observations = old["provenance"] as List<Map<String, Any?>>
                    candidates[id] = old + mapOf("provenance" to (observations + evidence).take(100))
                    continue
                }

                // Лимит применяется к числу уникальных кандидатов.
                if (candidates.size == MAX_CANDIDATES) return candidates.values.toList()

                // Метаданные фактического файла не подтверждены:
                // загрузка документа в этом компоненте отсутствует.
                candidates[id] = linkedMapOf("file_id" to id, "session_id" to sessionId,
                    "job_id" to job, "source_page_id" to pageId, "source_url" to source,
                    "target_url" to target, "original_filename" to filename?.take(1024),
                    "filename_source" to if (!download.isNullOrBlank()) "LINK_DOWNLOAD_ATTRIBUTE" else "URL_PATH_HINT",
                    "extension" to extension, "format_hint_source" to
                            if (extension in extensions) "LINK_FILENAME_OR_URL" else "LINK_TYPE_ATTRIBUTE",
                    "content_type" to null, "content_length" to null,
                    "declared_content_type" to declared, "content_metadata_verified" to false,
                    "discovered_at" to discovered,
                    "discovery_time_basis" to (edge?.get("discovery_time_basis") ?:
                    if (page["fetched_at"] != null) "SNAPSHOT_FETCHED_AT" else "SNAPSHOT_RECORDED_AT"),
                    "source_kind" to kind, "status" to if (sameOrigin) "CANDIDATE" else "OUT_OF_SCOPE",
                    "approved_host" to sameHost, "scope_status" to if (sameOrigin) "SOURCE_ORIGIN" else "OUT_OF_SCOPE",
                    "downloaded" to false, "provenance" to listOf(evidence))
            }
        }
        return candidates.values.toList()
    }

    /**
     * Читает архивный HTML-объект с проверкой целостности.
     *
     * Ожидаемый путь строится из SHA-256:
     * archiveRoot/objects/<content_hash>.html.
     *
     * Проверяет:
     * - формат content_hash;
     * - совпадение storage_location с ожидаемым путём;
     * - отсутствие symbolic links в цепочке пути;
     * - тип файла и размер;
     * - совпадение content_size с размером на диске;
     * - SHA-256 прочитанных байтов.
     *
     * Объём чтения ограничен MAX_HTML_BYTES + 1 байтами.
     *
     * @param page метаданные архивной страницы.
     * @param root нормализованный абсолютный путь архива.
     * @return проверенные байты архивного объекта.
     * @throws IOException если объект не соответствует
     *         ожидаемому content-addressed представлению.
     */
    private fun body(page: Map<String, Any?>, root: Path): ByteArray {
        val hash = page["content_hash"] as? String ?: throw IOException("Missing archive hash")
        if (!hash.matches(Regex("[a-f0-9]{64}"))) throw IOException("Invalid archive hash")
        val expected = root.resolve("objects").resolve("$hash.html")
        val location = page["storage_location"] as? String ?: throw IOException("Missing archive object")
        val actual = try { Path.of(location).toAbsolutePath() } catch (_: Exception) {
            throw IOException("Invalid archive object path")
        }

        // Объект должен находиться строго по вычисленному
        // content-addressed пути.
        if (actual != expected) throw IOException("Archive object outside expected content-addressed location")

        noSymlinks(actual)
        if (!Files.isRegularFile(actual, NOFOLLOW_LINKS)) throw IOException("Archive object is not a regular file")

        val size = Files.size(actual)
        if (size !in 1L..MAX_HTML_BYTES.toLong() || (page["content_size"] as? Number)?.toLong() != size) {
            throw IOException("Archive object size mismatch")
        }

        // Читаем файл без следования symbolic links
        // и проверяем фактическое содержимое по SHA-256.
        val bytes = Files.newInputStream(actual, READ, NOFOLLOW_LINKS).use { it.readNBytes(MAX_HTML_BYTES + 1) }
        if (bytes.size.toLong() != size || digest(bytes) != hash) throw IOException("Archive object hash mismatch")
        return bytes
    }

    /**
     * Извлекает расширение из предполагаемого имени файла.
     *
     * Учитывает разделители путей '/' и '\\',
     * приводит расширение к нижнему регистру.
     *
     * @param filename имя файла или его подсказка.
     * @return расширение без точки либо null.
     */
    private fun extension(filename: String?): String? = filename?.substringAfterLast('/')
        ?.substringAfterLast('\\')?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /**
     * Проверяет и канонизирует URL документа.
     *
     * Допускает только URL, удовлетворяющие политике Tor:
     * - длина исходной строки от 1 до 2048 символов;
     * - корректный URI с host и scheme;
     * - действительный onion host;
     * - отсутствие userinfo;
     * - ожидаемая структура authority.
     *
     * Для нормализации используется [TorPolicy] в режиме ONLY.
     * Создание объекта политики не выполняет сетевой запрос.
     *
     * Возвращаемый URL формируется из нормализованных
     * компонентов URI без fragment.
     *
     * @param value исходный URL.
     * @return канонический onion URL.
     * @throws IOException если адрес не проходит проверку.
     */
    private fun canonical(value: String): String {
        if (value.length !in 1..2048) throw IOException("Invalid document target")
        val uri = try { URI(value) } catch (_: Exception) { throw IOException("Invalid document target") }
        val host = uri.host?.lowercase(Locale.ROOT) ?: throw IOException("Invalid document target")
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: throw IOException("Invalid document target")
        if (!TorPolicy.validOnion(host) || uri.rawUserInfo != null ||
            uri.rawAuthority != uri.host + (if (uri.port == -1) "" else ":${uri.port}")) {
            throw IOException("Invalid document target")
        }

        // Применяем общую URL-политику Tor.
        // Разрешённый host ограничен проверяемым onion host.
        val policy = TorPolicy(mapOf("tor.mode" to "ONLY", "tor.socks.host" to "127.0.0.1",
            "tor.socks.port" to 9050, "tor.allowed.hosts" to listOf(host)))
        val normalized = policy.normalize("$scheme://$host" + (if (uri.port == -1) "" else ":${uri.port}") +
                uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: ""))
        val result = URI(normalized)
        return "$scheme://$host${result.rawPath}" + (result.rawQuery?.let { "?$it" } ?: "")
    }

    /**
     * Проверяет строковый UUID исследовательской сущности.
     *
     * Требует, чтобы UUID.fromString() возвращал
     * точно такое же строковое представление.
     *
     * @param value предполагаемый UUID.
     * @return исходная строка после проверки.
     * @throws IOException если значение отсутствует
     *         или не является канонической записью UUID.
     */
    private fun identifier(value: String?): String {
        if (value == null || try { UUID.fromString(value).toString() != value } catch (_: Exception) { true }) {
            throw IOException("Invalid research identifier")
        }
        return value
    }

    /**
     * Проверяет архивную временную метку.
     *
     * Значение должно быть строкой длиной не более
     * 40 символов, разбираемой через Instant.parse().
     *
     * Метод проверяет формат, но не изменяет
     * представление временной метки.
     *
     * @param value значение из архивных metadata.
     * @return исходная строка timestamp.
     * @throws IOException при отсутствии или
     *         некорректном формате времени.
     */
    private fun timestamp(value: Any?): String {
        if (value !is String || value.length > 40) throw IOException("Missing archive capture timestamp")
        try { Instant.parse(value) } catch (_: Exception) { throw IOException("Invalid archive capture timestamp") }
        return value
    }

    /**
     * Проверяет отсутствие symbolic links во всех
     * компонентах пути вплоть до корня файловой системы.
     *
     * Проверка выполняется без чтения содержимого файла.
     *
     * @param path проверяемый путь.
     * @throws IOException если один из компонентов
     *         является символической ссылкой.
     */
    private fun noSymlinks(path: Path) {
        var current: Path? = path
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw IOException("Archive paths must not contain symbolic links")
            current = current.parent
        }
    }

    /**
     * Вычисляет SHA-256 и возвращает digest
     * в нижнем шестнадцатеричном представлении.
     *
     * Используется для проверки архивных объектов
     * и формирования идентификаторов файловых кандидатов.
     *
     * @param bytes входные байты.
     * @return SHA-256 в виде hex-строки.
     */
    private fun digest(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
