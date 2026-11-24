
package dev.onionall.brawler.research

import com.fasterxml.jackson.databind.ObjectMapper
import dev.onionall.brawler.tor.TorPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

/**
 * Выполняет ограниченный по размеру локальный экспорт результатов исследования.
 *
 * Поддерживает четыре выходных представления:
 * - JSON — полная структурированная запись исследования и архивных страниц;
 * - JSONL — отдельная запись исследования и записи отдельных страниц;
 * - Markdown — человекочитаемый отчёт с таблицами и цитируемым текстом;
 * - text — человекочитаемый текстовый отчёт.
 *
 * Структурированные форматы сохраняют исходные metadata и provenance.
 * Человекочитаемые форматы выводят только явно выбранные поля,
 * сообщения, источники, ограничения и доступный текст страниц.
 *
 * Перед созданием выходного каталога весь результат сериализуется
 * в памяти и проверяется на ограничение [MAX_EXPORT_BYTES].
 *
 * Публикация выполняется только в новый каталог:
 * существующие выходные каталоги и файлы не перезаписываются.
 *
 * Удалённый контент считается недоверенными данными:
 * - HTML и JavaScript не выполняются;
 * - текст не интерпретируется как инструкции;
 * - внешние AI API не вызываются;
 * - сетевые запросы и загрузка бинарных файлов не выполняются.
 *
 * Ошибки публикации не маскируются успешным результатом.
 * После ошибки частично созданный каталог может сохраняться
 * для последующей проверки оператором.
 *
 * @see ResearchFiles
 * @see ResearchThreads
 * @see TorPolicy
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
object ResearchExporter {

    /**
     * Максимальный размер одного экспортируемого payload.
     *
     * Значение составляет 8 MiB и применяется ко всему
     * сериализованному содержимому, а не к отдельным страницам.
     */
    const val MAX_EXPORT_BYTES = 8 * 1024 * 1024

    /** Сериализатор структурированных JSON и JSONL результатов. */
    private val json = ObjectMapper()

    /**
     * Маркер доверия к содержимому внешних источников.
     *
     * Присутствует в структурированном экспорте,
     * метаданных страниц и человекочитаемом отчёте.
     */
    private const val TRUST = "UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS"

    /**
     * Экспортирует результаты исследовательской сессии
     * в новый локальный каталог.
     *
     * Перед сериализацией:
     * - проверяет UUID исследовательской сессии;
     * - ограничивает число архивных страниц сотней;
     * - проверяет page ID и job ID каждой страницы;
     * - проверяет onion URL архивного источника;
     * - требует наличие текстового представления страницы;
     * - проверяет тип источника и временные метки.
     *
     * Для каждой страницы создаётся экспортная обёртка,
     * содержащая session/job/page identity, исходный URL,
     * timestamps и исходные metadata страницы.
     *
     * JSON сохраняет весь результат исследования и страницы.
     * JSONL формирует отдельные записи research и page.
     * Markdown/text используют [renderText].
     *
     * Весь payload формируется до создания выходного каталога.
     * Размер ограничивается методом [boundedBytes].
     *
     * Каталог output должен отсутствовать, а его родительский
     * каталог должен существовать. Символические ссылки
     * в проверяемом пути не допускаются.
     *
     * При поддержке POSIX каталог создаётся с правами 0700.
     * Выходной файл открывается исключительно с CREATE_NEW.
     *
     * @param result результат исследования с идентификатором сессии,
     *        статусами, наблюдениями и связанными сущностями.
     * @param details подробные metadata архивных страниц.
     * @param format требуемый формат: json, jsonl, markdown/md
     *        либо text/txt; регистр не учитывается.
     * @param output путь к новому выходному каталогу.
     * @return metadata опубликованного экспорта, включая путь,
     *         размер, SHA-256, формат и время публикации.
     * @throws IOException при ошибке валидации, сериализации,
     *         превышении лимитов или публикации результата.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun export(result: Map<String, Any?>, details: List<Map<String, Any?>>,
               format: String, output: Path): Map<String, Any?> {
        val session = result["session_id"] as? String ?: throw IOException("Missing research session identifier")
        identifier(session)
        if (details.size > 100) throw IOException("Research export page limit reached")

        // Формируем проверенные экспортные обёртки страниц.
        // В каждой сохраняется исходный документ page.
        val pages = details.map { page ->
            val id = identifier(page["page_id"] as? String)
            val job = identifier(page["job_id"] as? String)
            val source = page["canonical_url"] as? String ?: throw IOException("Missing archived source URL")
            val uri = try { URI(source) } catch (_: Exception) { throw IOException("Invalid archived source URL") }

            // Экспортируем только допустимые onion URL
            // без userinfo и нестандартных портов.
            if (uri.scheme !in setOf("http", "https") || !TorPolicy.validOnion(uri.host ?: "") ||
                uri.rawUserInfo != null || uri.port !in setOf(-1, if (uri.scheme == "http") 80 else 443)) {
                throw IOException("Invalid archived source URL")
            }

            if (page["text"] !is String) throw IOException("Missing archived page text")
            val fetched = page["fetched_at"]
            val kind = page["source_kind"]

            // Для crawler-источника требуется фактический
            // fetched_at. MANUAL_IMPORT допускает его отсутствие.
            if (kind !in setOf("CRAWLER", "MANUAL_IMPORT") || kind == "CRAWLER" && fetched == null) {
                throw IOException("Invalid archived fetch provenance")
            }

            if (fetched != null) timestamp(fetched)
            page["recorded_at"]?.let(::timestamp)

            linkedMapOf<String, Any?>("session_id" to session, "job_id" to job, "page_id" to id,
                "source_url" to source, "fetched_at" to fetched, "recorded_at" to page["recorded_at"],
                "discussion_structure" to "PAGE_LEVEL_TEXT_ONLY", "content_trust" to TRUST,
                "page" to LinkedHashMap(page))
        }

        // Нормализуем только имя выбранного формата.
        // Неподдерживаемые форматы отклоняются явно.
        val selectedFormat = when (format.lowercase(Locale.ROOT)) {
            "json" -> "json"
            "jsonl" -> "jsonl"
            "markdown", "md" -> "markdown"
            "text", "txt" -> "text"
            else -> throw IOException("Require json, jsonl, markdown or text export format")
        }

        // Сначала сериализуем весь результат с лимитом 8 MiB.
        // До завершения этого этапа каталог не создаётся.
        val payload = boundedBytes { outputStream ->
            when (selectedFormat) {
                "json" -> json.writeValue(outputStream, linkedMapOf("export_version" to 1,
                    "session_id" to session, "content_trust" to TRUST,
                    "research" to result, "pages" to pages))

                "jsonl" -> {
                    json.factory.createGenerator(outputStream).use { generator ->
                        generator.codec = json

                        // Первая запись описывает исследование.
                        // Страницы записываются отдельными строками.
                        generator.writeObject(linkedMapOf("record_type" to "research",
                            "export_version" to 1, "session_id" to session,
                            "content_trust" to TRUST, "research" to result.minus("pages")))
                        generator.writeRaw('\n')

                        for (page in pages) {
                            generator.writeObject(linkedMapOf("record_type" to "page") + page)
                            generator.writeRaw('\n')
                        }
                    }
                }

                // Markdown и text используют общий renderer,
                // различающийся оформлением заголовков и данных.
                else -> OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    renderText(writer, result, pages, selectedFormat == "markdown")
                }
            }
        }

        val destination = output.toAbsolutePath().normalize()
        val parent = destination.parent ?: throw IOException("Export requires a new directory")

        // Путь публикации не должен содержать symbolic links.
        // Существующий destination не перезаписывается.
        noSymlinks(destination)
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || Files.exists(destination, NOFOLLOW_LINKS)) {
            throw IOException("Export requires an existing parent and a new output directory; nothing overwritten")
        }

        val filename = when (selectedFormat) {
            "json" -> "research.json"
            "jsonl" -> "research.jsonl"
            "markdown" -> "research.md"
            else -> "research.txt"
        }

        try {
            // На POSIX-файловых системах ограничиваем
            // доступ к новому каталогу владельцем.
            if (Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
                Files.createDirectory(destination, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createDirectory(destination)
            }

            val target = destination.resolve(filename)

            // CREATE_NEW запрещает замену существующего файла.
            // force(true) запрашивает синхронизацию данных
            // и metadata файла с хранилищем.
            FileChannel.open(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = ByteBuffer.wrap(payload)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }

            // Возвращаем фактические metadata созданного файла.
            // network_requests=0 относится именно к экспорту.
            return linkedMapOf("session_id" to session, "format" to selectedFormat,
                "output_directory" to destination.toString(), "output_file" to target.toString(),
                "bytes" to payload.size, "sha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),
                "pages_exported" to pages.size, "discussion_structure" to
                        if (records(result["threads"]).any { records(it["posts"]).isNotEmpty() })
                            "STRUCTURED_POSTS_WITH_PAGE_TEXT_FALLBACK" else "PAGE_LEVEL_TEXT_ONLY",
                "content_trust" to TRUST, "network_requests" to 0, "exported_at" to Instant.now().toString())
        } catch (_: Exception) {
            // Удаление по пути после ошибки могло бы затронуть чужой race-created файл: сохраняем evidence.
            throw IOException("Research export publication failed; inspect the requested output directory")
        }
    }

    /**
     * Формирует человекочитаемый Markdown или текстовый отчёт.
     *
     * Отчёт включает:
     * - общую сводку исследовательской сессии;
     * - обнаруженные источники и статусы выполнения;
     * - наблюдаемые дискуссии и сообщения;
     * - provenance архивных страниц;
     * - наблюдаемую пагинацию;
     * - исходный текст страниц при необходимости fallback;
     * - файловые кандидаты;
     * - ограничения, ошибки и неизвестные показатели.
     *
     * В отличие от JSON/JSONL, метод не сериализует
     * произвольные вложенные карты в читаемый документ.
     * Выводятся только заранее выбранные поля.
     *
     * Недоверенные текстовые значения обрабатываются
     * методами [inline], [quoted] и [markdownData].
     *
     * @param writer поток символьного вывода.
     * @param result результат исследования.
     * @param pages проверенные экспортные обёртки страниц.
     * @param markdown true для Markdown, false для plain text.
     */
    private fun renderText(writer: Writer, result: Map<String, Any?>,
                           pages: List<Map<String, Any?>>, markdown: Boolean) {
        val session = record(result["session"])
        val candidates = records(session["candidates"])
        val seeds = records(session["seeds"])
        val threads = records(result["threads"])
        val files = records(result["file_candidates"])
        val metrics = record(result["metrics"])
        val details = pages.map { record(it["page"]) }

        // Структурированные сообщения выводятся только
        // для дискуссий с допустимым extraction_status.
        val structured = threads.filter { it["extraction_status"] in setOf("STRUCTURED_POSTS", "PARTIAL") }
        val postCount = structured.sumOf { records(it["posts"]).size }

        // Все значения полей проходят через inline(),
        // включая значения из недоверенных источников.
        fun field(label: String, value: Any?) { writer.write((if (markdown) "- " else "") + "$label: ${inline(value, markdown)}\n") }

        fun section(title: String, level: Int = 2) {
            writer.write("\n" + (if (markdown) "#".repeat(level) + " " else "") + title + "\n\n")
        }

        writer.write(if (markdown) "# RESEARCH EXPORT\n" else "RESEARCH EXPORT\n")

        // Общая сводка использует наблюдаемые показатели,
        // не подменяя неизвестные значения предположениями.
        section("Research summary")
        field("Query", session["query"])
        field("Provider", session["provider"])
        field("Session ID", result["session_id"])
        field("Started at", session["run_started_at"] ?: session["created_at"])
        val fetched = details.mapNotNull { it["fetched_at"] as? String }.sorted()
        field("First captured at", fetched.firstOrNull())
        field("Last captured at", fetched.lastOrNull())
        field("Discovered URL observations", session["actual_result_count"])
        field("Stored candidate URLs", candidates.size)
        field("Unique candidate onion hosts", candidates.filter { it["validation_status"] == "VALID" }
            .mapNotNull { it["host"] as? String }.distinct().size)
        field("Approved seeds", if (session["approved"] == true) seeds.size else 0)
        field("Archived pages in this export", pages.size)
        field("Extracted threads", structured.size)
        field("Extracted posts", postCount)
        field("File candidates", files.size)
        field("Session status", result["session_status"])
        field("Acquisition status", result["acquisition_status"])
        field("Scheduler readback current", result["scheduler_readback_current"])
        field("Status basis", result["status_basis"])
        writer.write("\nContent trust: $TRUST\n")
        writer.write("Full metadata and extraction evidence are available through separate JSON/JSONL exports.\n")

        // Sources связывает обнаруженные URL с выбранными
        // seeds, наблюдениями job и архивными страницами.
        section("Sources")
        val sources = candidates.map { candidate ->
            val seed = seeds.firstOrNull { it["candidate_id"] == candidate["candidate_id"] }
            val observation = records(result["per_seed"]).firstOrNull { seed != null && it["job_id"] == seed["job_id"] }
            val status = record(observation?.get("status"))
            val archived = if (seed == null) 0 else details.count { it["job_id"] == seed["job_id"] }
            listOf(candidate["canonical_url"], candidate["selected"],
                if (seed != null && session["approved"] == true) "APPROVED" else "NOT APPROVED",
                observation?.get("execution_state") ?: seed?.get("execution_state"),
                status["job_state"], archived)
        }

        table(writer, listOf("URL", "Selected", "Scope", "Execution", "Observed job state", "Archived pages"), sources, markdown)
        if (sources.isEmpty()) writer.write("No discovered candidate sources recorded.\n")
        writer.write("Archived page counts are local observations; they do not establish successful fetch or crawl completion.\n")

        // Discussion rendering не предполагает, что каждая
        // архивная страница имеет структурированные сообщения.
        section("Discussions")
        if (threads.isEmpty()) writer.write("No structured discussion observations.\n")

        // covered хранит страницы, представленные
        // действительно выведенными сообщениями.
        val covered = mutableSetOf<String>()

        // Partial/dynamic страницы сохраняют полный доступный page text даже рядом с сообщениями.
        val fallbackRequired = mutableSetOf<String>()

        // Принудительно сохраняем page-level fallback,
        // когда extraction неполный или текст был усечён.
        for (thread in threads) {
            records(thread["captured_pages"]).filter {
                it["extraction_status"] != "STRUCTURED_POSTS" || it["page_text_truncated"] == true
            }.mapNotNull { it["page_id"] as? String }.forEach(fallbackRequired::add)

            for (post in records(thread["posts"])) {
                val evidence = record(post["evidence"])
                if (listOf("text_truncated", "quotes_truncated", "links_truncated").any { evidence[it] == true } ||
                    records(post["quotes"]).any { it["text_truncated"] == true }) {
                    (post["source_page_id"] as? String)?.let(fallbackRequired::add)
                    records(post["provenance"]).mapNotNull { it["source_page_id"] as? String }
                        .forEach(fallbackRequired::add)
                }
            }
        }

        for ((index, thread) in threads.withIndex()) {
            section("THREAD ${index + 1}", 3)
            field("Title", thread["title"])
            field("Thread ID", thread["thread_id"])
            field("Source URL", thread["source_url"])
            field("Source host", thread["source_host"])

            // Сохраняем provenance всех страниц темы,
            // независимо от успешности извлечения сообщений.
            val captured = records(thread["captured_pages"])
            field("Captured pages", captured.size)
            field("Extraction status", thread["extraction_status"])
            field("Completeness status", thread["completeness_status"])
            if (thread["ordering_status"] != null) field("Ordering status", thread["ordering_status"])
            if (thread["post_order_basis"] != null) field("Post order basis", thread["post_order_basis"])

            table(writer, listOf("Captured URL", "Page ID", "Snapshot ID", "Job ID", "Fetched at", "Recorded at", "Source kind"), captured.map {
                listOf(it["url"], it["page_id"], it["snapshot_id"], it["job_id"], it["fetched_at"], it["recorded_at"], it["source_kind"])
            }, markdown)

            // Пагинация описывает только наблюдаемые ссылки.
            // Captured не является доказательством полноты темы.
            val pagination = records(thread["observed_pagination"])
            field("Observed pagination links", pagination.size)
            field("Observed pagination links captured", pagination.count { it["captured"] == true })
            if (pagination.isNotEmpty()) table(writer,
                listOf("Pagination source", "Target", "Disposition", "Captured"), pagination.map {
                    listOf(it["source_url"], it["target_url"], it["disposition"], it["captured"])
                }, markdown)

            val posts = if (thread in structured) records(thread["posts"]) else emptyList()
            if (posts.isEmpty()) writer.write("\nNo extracted messages; available captured page text is shown separately.\n")

            for ((postIndex, post) in posts.withIndex()) {
                section("MESSAGE ${postIndex + 1}", 4)
                field("Author", post["author"])
                field("Posted at", post["posted_at"])
                field("Source post ID", post["source_post_id"])
                field("Post ID", post["post_id"])
                field("Source URL", post["source_page_url"])
                field("Page ID", post["source_page_id"])
                field("Snapshot ID", post["snapshot_id"])
                field("Extraction method", post["extraction_method"])
                field("Confidence", post["confidence"])

                // Текст сообщения и цитаты выводятся
                // как недоверенные данные, а не Markdown-код.
                writer.write("\nMessage text (untrusted data):\n")
                quoted(writer, post["text"] as? String ?: "", markdown)

                val quotes = (post["quoted_text"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                for ((quoteIndex, quote) in quotes.withIndex()) {
                    writer.write("Quoted text ${quoteIndex + 1} (untrusted data):\n")
                    quoted(writer, quote, markdown)
                }

                val references = records(post["referenced_links"])
                if (references.isNotEmpty()) table(writer, listOf("Referenced URL"), references.map {
                    listOf(it["url"] ?: it["target_url"])
                }, markdown)

                val attachments = records(post["attachments"])
                if (attachments.isNotEmpty()) table(writer, listOf("Attachment name", "Observed URL"), attachments.map {
                    listOf(it["original_filename"] ?: it["filename"] ?: it["name"], it["url"] ?: it["target_url"])
                }, markdown)

                // Только страницы с действительно отрисованными сообщениями исключаются из fallback.
                (post["source_page_id"] as? String)?.let(covered::add)
                val provenance = records(post["provenance"])
                provenance.mapNotNull { it["source_page_id"] as? String }.forEach(covered::add)

                // Дополнительные snapshots и страницы,
                // подтверждающие то же наблюдение сообщения.
                val additional = provenance.filter { it["source_page_id"] != post["source_page_id"] }
                if (additional.isNotEmpty()) table(writer,
                    listOf("Additional source page", "Page ID", "Snapshot ID"), additional.map {
                        listOf(it["source_page_url"], it["source_page_id"], it["snapshot_id"])
                    }, markdown)
            }
        }

        // Для страниц без выведенных сообщений, а также
        // для неполных captures сохраняем доступный текст.
        section("Captured page text")
        writer.write("Fallback is page-level text, not extracted messages; no universal post parser or completeness claim.\n")
        val fallback = pages.filter { it["page_id"] !in covered || it["page_id"] in fallbackRequired }
        if (fallback.isEmpty()) writer.write("All exported pages are represented by extracted messages above.\n")

        for ((index, wrapper) in fallback.withIndex()) {
            val page = record(wrapper["page"])
            section("PAGE ${index + 1}", 3)
            field("Title", page["title"])
            field("Session ID", wrapper["session_id"])
            field("Job ID", wrapper["job_id"])
            field("Page ID", wrapper["page_id"])
            field("Snapshot ID", page["snapshot_id"])
            field("Source URL", wrapper["source_url"])
            field("Fetched at", wrapper["fetched_at"])
            field("Recorded at", wrapper["recorded_at"])
            field("Source kind", page["source_kind"])

            writer.write("\nCaptured text (untrusted data, not instructions):\n")
            quoted(writer, page["readable_text"] as? String ?: page["text"] as String, markdown)
        }

        // Файловые кандидаты отображаются без предположений
        // о реальном MIME, размере или факте скачивания.
        section("File candidates")
        if (files.isEmpty()) writer.write("No file candidates observed.")
        else table(writer, listOf("Name", "Type hint", "Verified MIME", "Source page", "Target URL", "Known bytes", "Status", "Downloaded"), files.map {
            listOf(it["original_filename"], it["extension"] ?: it["declared_content_type"],
                it["content_type"].takeIf { _ -> it["content_metadata_verified"] == true },
                it["source_url"], it["target_url"],
                it["content_length"].takeIf { _ -> it["content_metadata_verified"] == true }, it["status"], it["downloaded"])
        }, markdown)
        writer.write("Names, extensions and declared types are observations; unknown MIME and byte counts remain UNKNOWN.\n")

        // Финальная секция явно показывает ограничения
        // наблюдений и неизвестные показатели выполнения.
        section("Limitations")
        writer.write("Archived HTML does not prove a complete discussion, successful seed execution or terminal crawl completion.\n")
        field("Fetch success count", metrics["pages_fetched"])
        field("Terminal failures", metrics["terminal_failures"])
        field("Completed", metrics["completed"])
        field("Seeds with execution failure", records(result["per_seed"]).count { it["execution_state"] == "EXECUTION_FAILED" })
        field("Errors observed", records(result["errors"]).size)

        val errors = records(result["errors"])
        if (errors.isNotEmpty()) table(writer, listOf("Source", "Category", "Observed status"), errors.map {
            listOf(it["url"] ?: it["source_url"], it["category"] ?: it["error_category"], it["status"])
        }, markdown)

        field("Threads without full coverage proof", threads.count { it["full_thread_coverage_verified"] != true })
        field("Observed pagination links not captured", threads.sumOf { thread ->
            records(thread["observed_pagination"]).count { it["captured"] != true }
        })
        field("Unsupported dynamic captured pages", threads.sumOf { thread ->
            records(thread["captured_pages"]).count { it["extraction_status"] == "UNSUPPORTED_DYNAMIC_CONTENT" }
        })

        val rejected = records(result["discussion_scope_rejected_pages"])
        field("Discussion scope rejected pages", rejected.size)
        if (rejected.isNotEmpty()) table(writer, listOf("Rejected page ID", "Source URL", "Reason"), rejected.map {
            listOf(it["source_page_id"] ?: it["page_id"], it["source_url"] ?: it["url"], it["reason"])
        }, markdown)

        field("Links may be truncated", result["links_may_be_truncated"])
        field("Files may be truncated", result["files_may_be_truncated"])
        writer.write("\nUnobserved author, time, file metadata and scheduler counters remain UNKNOWN. Export performs no network requests or binary downloads.\n")
    }

    /**
     * Безопасно интерпретирует значение как одну Map.
     *
     * Если значение не является картой, возвращает пустую Map.
     * Метод не выполняет глубокую проверку её содержимого.
     *
     * @param value произвольное значение.
     * @return карта либо пустая карта.
     */
    private fun record(value: Any?): Map<*, *> = value as? Map<*, *> ?: emptyMap<Any, Any>()

    /**
     * Извлекает карты из Iterable, игнорируя элементы
     * неподходящих типов.
     *
     * @param value произвольное значение.
     * @return список элементов типа Map.
     */
    private fun records(value: Any?): List<Map<*, *>> = (value as? Iterable<*>)?.filterIsInstance<Map<*, *>>().orEmpty()

    /**
     * Преобразует значение в безопасное однострочное
     * представление для полей и ячеек таблиц.
     *
     * Поддерживает строки, числа и Boolean.
     * Прочие типы отображаются как UNKNOWN.
     *
     * Управляющие и направляющие Unicode-символы
     * обрабатываются через [plain].
     * Переводы строк и табуляции заменяются литералами.
     *
     * В Markdown дополнительно экранируется синтаксис
     * через [markdownData].
     *
     * @param value исходное значение.
     * @param markdown требуется ли Markdown-экранирование.
     * @return однострочное представление.
     */
    private fun inline(
        value: Any?,
        markdown: Boolean
    ): String {
        val text = when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> "UNKNOWN"
        }
        val safe = plain(text).replace("\n", "\\n").replace("\t", "\\t")
        return if (markdown) markdownData(safe) else safe
    }

    /**
     * Записывает таблицу в Markdown или plain text.
     *
     * Заголовки задаются вызывающим кодом.
     * Значения строк проходят через [inline],
     * поэтому недоверенный текст не может напрямую
     * вставить новую строку или Markdown-разметку.
     *
     * Пустая таблица не выводится.
     *
     * @param writer поток вывода.
     * @param headings заголовки столбцов.
     * @param rows строки таблицы.
     * @param markdown форматирование Markdown.
     */
    private fun table(
        writer: Writer,
        headings: List<String>,
        rows: List<List<Any?>>,
        markdown: Boolean
    ) {
        if (rows.isEmpty()) return
        if (markdown) writer.write("\n")
        writer.write(headings.joinToString(" | ", if (markdown) "| " else "", if (markdown) " |\n" else "\n"))
        if (markdown) writer.write(headings.joinToString(" | ", "| ", " |\n") { "---" })
        for (row in rows) writer.write(row.joinToString(" | ", if (markdown) "| " else "", if (markdown) " |\n" else "\n") {
            inline(it, markdown)
        })
        writer.write("\n")
    }

    /**
     * Выводит многострочный недоверенный текст
     * в виде цитируемого содержимого.
     *
     * В Markdown каждая строка получает префикс "> ".
     * В обычном тексте используется префикс "| ".
     *
     * Перед выводом применяются [plain] и, при необходимости,
     * [markdownData]. Таким образом содержимое не должно
     * интерпретироваться как активная Markdown-разметка.
     *
     * @param writer поток вывода.
     * @param text исходный текст страницы или сообщения.
     * @param markdown форматирование Markdown.
     */
    private fun quoted(writer: Writer, text: String, markdown: Boolean) {
        if (markdown) writer.write("\n")
        for (line in plain(text).lineSequence()) {
            writer.write(if (markdown) "> " else "| ")
            writer.write(if (markdown) markdownData(line) else line)
            writer.write("\n")
        }
        if (markdown) writer.write("\n")
    }

    /**
     * Экранирует недоверенный текст для вывода в Markdown.
     *
     * Заменяет HTML-значимые символы на entities
     * и экранирует ASCII punctuation обратным слешем.
     *
     * Используется для предотвращения интерпретации
     * внешнего текста как ссылок, изображений, заголовков,
     * inline code и других элементов Markdown.
     *
     * @param value предварительно обработанная строка.
     * @return строка с экранированным Markdown-синтаксисом.
     */
    private fun markdownData(value: String): String = buildString {
        for (character in value) when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> {
                if (character.code in 33..47 || character.code in 58..64 ||
                    character.code in 91..96 || character.code in 123..126) append('\\')
                append(character)
            }
        }
    }

    /**
     * Подготавливает строку для безопасного текстового вывода.
     *
     * Экранирует:
     * - ISO control characters, кроме newline и tab;
     * - Unicode-символы U+2028..U+202E;
     * - bidi isolates U+2066..U+2069;
     * - directional marks U+200E..U+200F.
     *
     * Опасные или неоднозначные символы заменяются
     * текстовым представлением "\\uXXXX".
     *
     * Метод сохраняет обычные символы и разрешённые
     * переводы строк для последующего форматирования.
     *
     * @param value исходная строка.
     * @return строка с экранированными control characters.
     */
    private fun plain(value: String): String = buildString {
        for (character in value) {
            if ((Character.isISOControl(character) && character != '\n' && character != '\t') ||
                character.code in 0x2028..0x202e || character.code in 0x2066..0x2069 ||
                character.code in 0x200e..0x200f) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else append(character)
        }
    }

    /**
     * Сериализует payload в память с ограничением 8 MiB.
     *
     * Создаёт OutputStream, который проверяет суммарный
     * объём данных при каждом вызове write().
     *
     * При превышении [MAX_EXPORT_BYTES] или ошибке
     * сериализации выбрасывается IOException.
     *
     * Метод выполняется до создания выходного каталога,
     * предотвращая публикацию заведомо слишком большого
     * экспортного файла.
     *
     * @param action операция записи в ограниченный поток.
     * @return сериализованный payload.
     * @throws IOException если сериализация не завершилась
     *         в пределах установленного лимита.
     */
    private fun boundedBytes(action: (OutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) {
                if (bytes.size() >= MAX_EXPORT_BYTES) throw IOException("Research export 8MiB limit reached")
                bytes.write(value)
            }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                if (length > MAX_EXPORT_BYTES - bytes.size()) throw IOException("Research export 8MiB limit reached")
                bytes.write(value, offset, length)
            }
        }
        try { action(output) } catch (_: Exception) {
            throw IOException("Research export cannot be encoded within 8MiB limit")
        }
        return bytes.toByteArray()
    }

    /**
     * Проверяет каноническое строковое представление UUID.
     *
     * @param value предполагаемый идентификатор.
     * @return исходный UUID после проверки.
     * @throws IOException если UUID отсутствует
     *         или имеет некорректный формат.
     */
    private fun identifier(value: String?): String {
        if (value == null || try { UUID.fromString(value).toString() != value } catch (_: Exception) { true }) {
            throw IOException("Invalid research export identifier")
        }
        return value
    }

    /**
     * Проверяет архивную временную метку.
     *
     * Требует строку длиной не более 40 символов,
     * корректно разбираемую через Instant.parse().
     *
     * Не преобразует значение в другой часовой пояс
     * и не изменяет исходное представление.
     *
     * @param value значение timestamp.
     * @return исходная строка после проверки.
     * @throws IOException при некорректном timestamp.
     */
    private fun timestamp(value: Any?): String {
        if (value !is String || value.length > 40) throw IOException("Invalid export timestamp")
        try { Instant.parse(value) } catch (_: Exception) { throw IOException("Invalid export timestamp") }
        return value
    }

    /**
     * Проверяет отсутствие символических ссылок
     * в компонентах указанного пути.
     *
     * Последовательно проверяет сам путь и всех его
     * родителей до корня файловой системы.
     *
     * Метод не создаёт, не удаляет и не открывает файлы.
     *
     * @param path путь для проверки.
     * @throws IOException при обнаружении symbolic link.
     */
    private fun noSymlinks(path: Path) {
        var current: Path? = path
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw IOException("Export paths must not contain symbolic links")
            current = current.parent
        }
    }
}
