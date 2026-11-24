package dev.onionall.brawler.research

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.Savepoint
import java.time.Instant
import java.util.UUID

/**
 * Адаптер хранения исследовательских сессий в существующем H2-каталоге.
 *
 * Использует дополнительную таблицу research_sessions_v1, не изменяя
 * основную схему ArchiveStore и существующие архивные документы.
 *
 * Каждая исследовательская сессия представлена JSON-документом
 * с обязательными полями:
 * - `session_id` — идентификатор исследовательской сессии;
 * - `schema_version` — версия формата документа;
 * - `revision` — номер ревизии для optimistic concurrency control;
 * - `created_at` — время создания документа;
 * - `updated_at` — время последнего изменения.
 *
 * Неизвестные JSON-поля сохраняются при создании документа
 * и при обновлении, если присутствуют в переданном документе.
 *
 * Для предотвращения конфликтующих изменений используется
 * compare-and-set по revision. Записи дополнительно
 * сериализуются через существующую таблицу `catalog_guard`.
 *
 * Соединение [connection] заимствовано у внешнего владельца:
 * - адаптер не закрывает соединение;
 * - не завершает транзакцию, начатую вызывающим кодом;
 * - при `autoCommit=true` управляет собственной транзакцией;
 * - при `autoCommit=false` использует JDBC savepoint.
 *
 * После неоднозначного commit, неудачного rollback
 * или восстановления состояния соединения адаптер
 * может стать непригодным для дальнейшего использования.
 * В таком случае владелец должен восстановить соединение
 * и создать новый экземпляр ResearchCatalog.
 *
 * Класс хранит состояние исследовательского workflow.
 * Резервирование URL, планирование обхода и retries
 * остаются ответственностью Frontier.
 *
 * @property connection заимствованное JDBC-соединение
 *           существующего архивного каталога.
 * @constructor проверяет доступность таблицы исследования
 *              либо создаёт её при допустимом состоянии
 *              транзакции.
 * @throws IOException если инициализация каталога недоступна.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
class ResearchCatalog @Throws(IOException::class) constructor(private val connection: Connection) {

    /**
     * Признак допустимости дальнейших операций через адаптер.
     *
     * После неоднозначного commit/cleanup caller должен восстановить
     * connection и создать новый adapter.
     *
     * Значение false означает, что адаптер больше не может
     * безопасно предполагать известное состояние транзакции.
     */
    private var usable = true

    /**
     * Константы ограничений и средства JSON-сериализации
     * исследовательских документов.
     */
    companion object {

        /** Максимальный размер одного JSON-документа: 4 MiB. */
        const val MAX_DOCUMENT_BYTES: Int = 4 * 1024 * 1024

        /** Максимальное число исследовательских сессий в каталоге. */
        const val MAX_SESSIONS: Int = 100

        /**
         * ObjectMapper с дополнительными проверками входного JSON.
         *
         * STRICT_DUPLICATE_DETECTION запрещает повторяющиеся
         * ключи внутри JSON-объектов.
         *
         * FAIL_ON_TRAILING_TOKENS запрещает дополнительные
         * JSON-токены после основного документа.
         */
        private val json = ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        /** Тип десериализации JSON-документа с сохранением порядка полей. */
        private val documentType = object : TypeReference<LinkedHashMap<String, Any?>>() {}
    }

    /**
     * Инициализирует дополнительную таблицу исследовательских сессий.
     *
     * Если соединение работает с autoCommit=true, допускается
     * CREATE TABLE IF NOT EXISTS.
     *
     * Если соединение уже находится во внешней транзакции,
     * выполняется только проверочный SELECT.
     *
     * Такое разделение необходимо потому, что DDL в H2
     * может выполнять неявный commit, нарушая границы
     * транзакции внешнего владельца.
     *
     * Синхронизация по connection защищает последовательность
     * операций для пользователей того же объекта соединения.
     */
    init {
        synchronized(connection) {
            try {
                if (connection.autoCommit) {
                    connection.createStatement().use { statement ->
                        statement.execute("CREATE TABLE IF NOT EXISTS research_sessions_v1 (" +
                                "session_id VARCHAR(36) PRIMARY KEY, revision BIGINT NOT NULL, " +
                                "document_json CLOB NOT NULL, updated_at VARCHAR(40) NOT NULL)")
                    }
                } else {
                    // H2 DDL делает implicit commit: не создаём таблицу внутри чужой транзакции.
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT session_id,revision,document_json,updated_at " +
                                "FROM research_sessions_v1 WHERE 1=0").close()
                    }
                }
            } catch (_: Exception) {
                throw IOException("Research catalog initialization unavailable")
            }
        }
    }

    /**
     * Создаёт новый документ исследовательской сессии.
     *
     * Начальная ревизия всегда равна нулю.
     *
     * Перед сохранением:
     * - проверяется канонический UUID сессии;
     * - проверяется schema_version;
     * - отклоняется ненулевая явно переданная revision;
     * - устанавливаются created_at и updated_at;
     * - документ сериализуется с ограничением размера.
     *
     * Внутри [mutate] проверяется общий лимит сессий,
     * после чего выполняется INSERT.
     *
     * Создание не заменяет существующий документ.
     * Ограничение уникальности session_id обеспечивается
     * первичным ключом таблицы.
     *
     * @param document исходный документ исследовательской сессии.
     * @return сохранённый документ с revision=0 и timestamps.
     * @throws IOException при ошибке валидации, превышении
     *         лимитов или невозможности сохранить документ.
     */
    @Throws(IOException::class)
    fun create(document: Map<String, Any?>): Map<String, Any?> = synchronized(connection) {
        ensureUsable()
        val id = identifier(document["session_id"] as? String)
        validateVersion(document)
        if (document.containsKey("revision") && revision(document["revision"]) != 0L) {
            throw IOException("New research document requires revision zero")
        }

        val created = document["created_at"]?.let(::timestamp) ?: Instant.now().toString()

        // Формируем новый envelope, сохраняя дополнительные
        // поля исходного исследовательского документа.
        val value = LinkedHashMap(document).apply {
            put("session_id", id)
            put("schema_version", 1)
            put("revision", 0L)
            put("created_at", created)
            put("updated_at", Instant.now().toString())
        }

        val encoded = encode(value)

        // Лимит числа сессий и INSERT выполняются
        // под общей транзакционной защитой catalog_guard.
        mutate {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM research_sessions_v1").use { rows ->
                    rows.next()
                    if (rows.getLong(1) >= MAX_SESSIONS) throw IOException("Research session limit reached")
                }
            }

            connection.prepareStatement("INSERT INTO research_sessions_v1 " +
                    "(session_id,revision,document_json,updated_at) VALUES(?,?,?,?)").use { insert ->
                insert.setString(1, id)
                insert.setLong(2, 0L)
                insert.setString(3, encoded)
                insert.setString(4, value["updated_at"] as String)
                insert.executeUpdate()
            }

            // Повторное чтение проверяет согласованность
            // сохранённого SQL envelope и JSON-документа.
            read(id)
        }
    }

    /**
     * Возвращает сохранённый документ исследовательской сессии.
     *
     * Проверяет идентификатор, затем выполняет [read],
     * который сопоставляет SQL-колонки с JSON envelope.
     *
     * Метод не изменяет revision и timestamps.
     *
     * @param id канонический UUID исследовательской сессии.
     * @return сохранённый JSON-документ.
     * @throws IOException если сессия отсутствует,
     *         документ повреждён или чтение недоступно.
     */
    @Throws(IOException::class)
    fun get(id: String): Map<String, Any?> = synchronized(connection) {
        ensureUsable()
        try {
            read(identifier(id))
        } catch (error: IOException) {
            throw error
        } catch (_: Exception) {
            throw IOException("Research catalog serializableRead unavailable")
        }
    }

    /**
     * Обновляет документ с использованием compare-and-set.
     *
     * Изменение разрешено только при совпадении
     * expectedRevision с текущей сохранённой revision.
     *
     * Дополнительно проверяются:
     * - неизменность session_id;
     * - поддерживаемая schema_version;
     * - согласованность переданной revision;
     * - неизменность created_at.
     *
     * Новая revision вычисляется как expectedRevision + 1.
     * updated_at устанавливается заново.
     *
     * SQL UPDATE содержит условие WHERE revision=?,
     * поэтому изменение не применяется при конфликте
     * ревизий.
     *
     * Вся операция выполняется внутри [mutate],
     * с соблюдением транзакционных границ владельца
     * соединения и блокировки catalog_guard.
     *
     * @param id идентификатор обновляемой сессии.
     * @param expectedRevision ожидаемая текущая ревизия.
     * @param document новое содержимое документа.
     * @return сохранённый документ с увеличенной revision.
     * @throws IOException при конфликте ревизий,
     *         изменении неизменяемых полей, нарушении
     *         ограничений или ошибке транзакции.
     */
    @Throws(IOException::class)
    fun update(id: String, expectedRevision: Long, document: Map<String, Any?>): Map<String, Any?> =
        synchronized(connection) {
            ensureUsable()
            val checkedId = identifier(id)

            // Не допускаем отрицательные ревизии
            // и переполнение при увеличении Long.MAX_VALUE.
            if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
                throw IOException("Invalid research revision")
            }

            if (identifier(document["session_id"] as? String) != checkedId) {
                throw IOException("Research session identifier is immutable")
            }

            validateVersion(document)
            if (document.containsKey("revision") && revision(document["revision"]) != expectedRevision) {
                throw IOException("Research document revision disagrees with expected revision")
            }

            mutate {
                val old = read(checkedId)

                // Сначала проверяем ревизию уже сохранённого
                // документа, а не только входного payload.
                if (revision(old["revision"]) != expectedRevision) {
                    throw IOException("Research document changed; reload before retrying")
                }

                if (document.containsKey("created_at") && timestamp(document["created_at"]) != old["created_at"]) {
                    throw IOException("Research creation timestamp is immutable")
                }

                val next = expectedRevision + 1

                // created_at наследуется из старого документа.
                // updated_at отражает новое изменение.
                val value = LinkedHashMap(document).apply {
                    put("revision", next)
                    put("created_at", old["created_at"])
                    put("updated_at", Instant.now().toString())
                }

                val encoded = encode(value)

                // Второй уровень CAS находится непосредственно
                // в SQL WHERE, предотвращая silent overwrite.
                connection.prepareStatement("UPDATE research_sessions_v1 SET revision=?,document_json=?,updated_at=? " +
                        "WHERE session_id=? AND revision=?").use { update ->
                    update.setLong(1, next)
                    update.setString(2, encoded)
                    update.setString(3, value["updated_at"] as String)
                    update.setString(4, checkedId)
                    update.setLong(5, expectedRevision)
                    if (update.executeUpdate() != 1) throw IOException("Research document changed; reload before retrying")
                }

                read(checkedId)
            }
        }

    /**
     * Проверяет существование исследовательской сессии.
     *
     * Выполняет SELECT по первичному ключу.
     * Не изменяет содержимое документа, revision
     * или временные метки.
     *
     * @param id канонический UUID сессии.
     * @return true, если соответствующая запись существует.
     * @throws IOException при недопустимом идентификаторе
     *         или недоступности чтения каталога.
     */
    @Throws(IOException::class)
    fun exists(id: String): Boolean = synchronized(connection) {
        ensureUsable()
        val checked = identifier(id)
        try {
            connection.prepareStatement("SELECT 1 FROM research_sessions_v1 WHERE session_id=?").use { query ->
                query.setString(1, checked)
                query.executeQuery().use { it.next() }
            }
        } catch (_: Exception) {
            throw IOException("Research catalog serializableRead unavailable")
        }
    }

    /**
     * Загружает JSON-документ и проверяет его SQL envelope.
     *
     * Проверяет размер CLOB сначала по CHAR_LENGTH,
     * затем по фактическому UTF-8 представлению.
     *
     * После десериализации сопоставляет:
     * - session_id с ключом запрошенной записи;
     * - JSON revision с SQL revision;
     * - JSON updated_at с SQL updated_at;
     * - schema_version с поддерживаемой версией.
     *
     * Также проверяет created_at.
     *
     * Небольшие целочисленные значения Jackson может
     * десериализовать как Int. Перед возвратом revision
     * заменяется значением SQL BIGINT типа Long.
     *
     * @param id идентификатор исследовательской сессии.
     * @return проверенный JSON-документ.
     * @throws IOException если запись отсутствует,
     *         превышен размер или нарушен envelope.
     */
    private fun read(id: String): Map<String, Any?> {
        connection.prepareStatement("SELECT revision,document_json,updated_at,CHAR_LENGTH(document_json) " +
                "FROM research_sessions_v1 WHERE session_id=?").use { query ->
            query.setString(1, id)
            query.executeQuery().use { rows ->
                if (!rows.next()) throw IOException("Unknown research session")

                // CHAR_LENGTH позволяет рано отклонить
                // заведомо слишком длинное значение CLOB.
                if (rows.getLong(4) > MAX_DOCUMENT_BYTES) throw IOException("Research document size limit reached")

                val raw = rows.getString(2)

                // UTF-8 размер может превышать число символов.
                // Поэтому выполняется отдельная проверка байтов.
                if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_DOCUMENT_BYTES) {
                    throw IOException("Research document size limit reached")
                }

                val value = try {
                    json.readValue(raw, documentType)
                } catch (_: Exception) {
                    throw IOException("Malformed research document")
                }

                validateVersion(value)

                // SQL-колонки и JSON envelope должны
                // описывать одну и ту же версию документа.
                if (identifier(value["session_id"] as? String) != id ||
                    revision(value["revision"]) != rows.getLong(1) ||
                    timestamp(value["updated_at"]) != rows.getString(3)) {
                    throw IOException("Research document envelope mismatch")
                }

                timestamp(value["created_at"])

                // Jackson может вернуть Int для малого числа; внешний revision всегда Long.
                value["revision"] = rows.getLong(1)
                return value
            }
        }
    }

    /**
     * Выполняет изменение каталога с соблюдением
     * транзакционных границ внешнего владельца.
     *
     * Поддерживаются два режима.
     *
     * 1. connection.autoCommit == true:
     *    - временно отключается autoCommit;
     *    - захватывается catalog_guard;
     *    - выполняется action;
     *    - выполняется commit;
     *    - восстанавливается autoCommit.
     *
     * 2. connection.autoCommit == false:
     *    - создаётся JDBC savepoint;
     *    - захватывается catalog_guard;
     *    - выполняется action;
     *    - при успехе освобождается savepoint;
     *    - внешний commit не выполняется.
     *
     * При ошибке предпринимается rollback всей собственной
     * транзакции либо rollback до savepoint.
     *
     * Если результат commit неизвестен, rollback недоступен
     * или восстановление соединения завершилось ошибкой,
     * адаптер помечается непригодным для повторного
     * использования.
     *
     * Диагностические сведения SQL-исключений намеренно
     * не передаются в публичные причины ошибок, поскольку
     * могут содержать приватные данные документа.
     *
     * @param action операция изменения каталога.
     * @return результат операции action.
     * @throws IOException при ошибке транзакции или
     *         невозможности безопасного продолжения.
     */
    private fun <T> mutate(action: () -> T): T {
        ensureUsable()

        // Считываем исходный режим соединения.
        // Если это невозможно, состояние считается неизвестным.
        val original = try { connection.autoCommit } catch (error: Throwable) {
            usable = false
            throw mutationFailure(error)
        }

        var savepoint: Savepoint? = null
        var began = false
        var commitAttempted = false
        var committed = false
        var restoreAllowed = true
        var primary: Throwable? = null

        try {
            // Для собственной транзакции отключаем autoCommit.
            // Для внешней транзакции создаём savepoint.
            if (original) connection.autoCommit = false else savepoint = connection.setSavepoint()
            began = true

            // Общая блокировка каталога сериализует
            // конкурирующие операции изменения.
            connection.prepareStatement("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").use { lock ->
                lock.executeQuery().use { rows ->
                    if (!rows.next()) throw IOException("Research catalog guard unavailable")
                }
            }

            val result = action()

            if (original) {
                // Фиксируем только транзакцию,
                // созданную непосредственно адаптером.
                commitAttempted = true
                connection.commit()
                committed = true
            } else {
                // Внешняя транзакция остаётся открытой.
                // Её окончательное завершение принадлежит caller.
                connection.releaseSavepoint(savepoint)
            }

            return result
        } catch (error: Throwable) {

            // Ошибка во время commit не позволяет однозначно
            // определить, были ли изменения зафиксированы.
            val failure = if (commitAttempted && !committed) {
                usable = false
                restoreAllowed = false
                val unknown = IOException("Research catalog commit outcome is unknown; caller must recover connection")
                if (error is Error || error is IOException) {
                    error.addSuppressed(unknown)
                    error
                } else unknown
            } else mutationFailure(error)

            primary = failure

            if (began) {
                try {
                    if (original) {
                        connection.rollback()
                    } else if (savepoint != null) {
                        connection.rollback(savepoint)
                        connection.releaseSavepoint(savepoint)
                    }
                } catch (_: Throwable) {
                    // Неудачный rollback делает состояние
                    // соединения непредсказуемым для адаптера.
                    usable = false
                    restoreAllowed = false
                    failure.addSuppressed(IOException("Research catalog rollback unavailable; caller must recover connection"))
                }
            } else {
                usable = false
                restoreAllowed = false
            }

            throw failure
        } finally {
            // Возвращаем autoCommit только если адаптер
            // сам управлял транзакцией и восстановление допустимо.
            if (original && began && restoreAllowed) {
                try { connection.autoCommit = true } catch (cleanup: Throwable) {
                    usable = false

                    // Различаем уже завершённый commit
                    // и невозможность восстановить соединение.
                    val restoration = IOException(if (committed)
                        "Research catalog transaction committed; connection restoration unavailable"
                    else "Research catalog transaction restoration unavailable; caller must recover connection")

                    if (primary != null) {
                        primary.addSuppressed(restoration)
                    } else if (cleanup is Error) {
                        cleanup.addSuppressed(restoration)
                        throw cleanup
                    } else {
                        throw restoration
                    }
                }
            }
        }
    }

    /**
     * Преобразует ошибку транзакции в публичную ошибку адаптера.
     *
     * IOException и Error сохраняются без преобразования.
     * Остальные Throwable заменяются обобщённой IOException.
     *
     * Это ограничивает распространение внутренних
     * диагностических сведений JDBC.
     *
     * @param error исходная ошибка.
     * @return ошибка для передачи вызывающему коду.
     */
    private fun mutationFailure(error: Throwable): Throwable = when (error) {
        is IOException, is Error -> error
        else -> IOException("Research catalog transaction unavailable")
    }

    /**
     * Проверяет пригодность адаптера для дальнейших операций.
     *
     * После необратимой или неоднозначной транзакционной
     * ошибки требуется восстановление соединения его
     * владельцем и создание нового ResearchCatalog.
     *
     * @throws IOException если адаптер помечен непригодным.
     */
    private fun ensureUsable() {
        if (!usable) throw IOException("Research catalog requires connection recovery and a new adapter")
    }

    /**
     * Сериализует исследовательский документ в JSON.
     *
     * Использует ограниченный OutputStream, который
     * запрещает запись более [MAX_DOCUMENT_BYTES] байтов.
     *
     * Ограничение проверяется непосредственно во время
     * сериализации, до передачи документа в SQL.
     *
     * @param value документ для сериализации.
     * @return JSON-строка в кодировке UTF-8.
     * @throws IOException при ошибке сериализации
     *         или превышении допустимого размера.
     */
    private fun encode(value: Map<String, Any?>): String {
        val output = ByteArrayOutputStream()

        // Ограничиваем не число символов JSON,
        // а фактическое количество записываемых байтов.
        val bounded = object : OutputStream() {
            override fun write(value: Int) {
                if (output.size() >= MAX_DOCUMENT_BYTES) throw IOException("Research document size limit reached")
                output.write(value)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > MAX_DOCUMENT_BYTES - output.size()) throw IOException("Research document size limit reached")
                output.write(bytes, offset, length)
            }
        }

        try {
            json.writeValue(bounded, value)
        } catch (_: Exception) {
            throw IOException("Research document cannot be encoded within size limit")
        }

        return output.toString(StandardCharsets.UTF_8)
    }

    /**
     * Проверяет каноническое представление UUID сессии.
     *
     * Требует строку из 36 символов с lowercase hex
     * и стандартным расположением дефисов.
     *
     * Дополнительно сверяет результат UUID.fromString()
     * с исходной строкой.
     *
     * @param id идентификатор для проверки.
     * @return проверенный идентификатор.
     * @throws IOException при некорректном UUID.
     */
    private fun identifier(id: String?): String {
        if (id == null || !id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) ||
            try { UUID.fromString(id).toString() != id } catch (_: IllegalArgumentException) { true }) {
            throw IOException("Invalid research session identifier")
        }
        return id
    }

    /**
     * Проверяет версию схемы исследовательского документа.
     *
     * Поддерживается только schema_version=1.
     * Значение должно проходить целочисленную
     * проверку методом [revision].
     *
     * @param value JSON-документ.
     * @throws IOException при неподдерживаемой версии.
     */
    private fun validateVersion(value: Map<String, Any?>) {
        if (revision(value["schema_version"]) != 1L) throw IOException("Unsupported research document version")
    }

    /**
     * Проверяет неотрицательное целочисленное значение.
     *
     * Допускает только Byte, Short, Int и Long.
     * Float, Double, строки и null отклоняются.
     *
     * Используется как для revision, так и для
     * проверки schema_version.
     *
     * @param value значение для проверки.
     * @return проверенное значение типа Long.
     * @throws IOException при неподдерживаемом типе
     *         или отрицательном значении.
     */
    private fun revision(value: Any?): Long {
        if (value !is Byte && value !is Short && value !is Int && value !is Long) {
            throw IOException("Invalid research revision")
        }
        return (value as Number).toLong().also { if (it < 0) throw IOException("Invalid research revision") }
    }

    /**
     * Проверяет временную метку исследовательского документа.
     *
     * Требует строку длиной не более 40 символов,
     * корректно разбираемую через Instant.parse().
     *
     * Исходное строковое представление сохраняется.
     *
     * @param value значение timestamp.
     * @return проверенная исходная строка.
     * @throws IOException при некорректной временной метке.
     */
    private fun timestamp(value: Any?): String {
        if (value !is String || value.length > 40) throw IOException("Invalid research timestamp")
        try { Instant.parse(value) } catch (_: Exception) { throw IOException("Invalid research timestamp") }
        return value
    }
}
