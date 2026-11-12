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
 * Additive research-документы в том же H2-каталоге, которым владеет ArchiveStore.
 * Соединение заимствовано: этот адаптер его не закрывает и не коммитит внешнюю транзакцию.
 * Документ хранит пользовательский workflow; reservations и scheduling остаются у Frontier.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
class ResearchCatalog @Throws(IOException::class) constructor(private val connection: Connection) {
    companion object {
        const val MAX_DOCUMENT_BYTES: Int = 4 * 1024 * 1024
        const val MAX_SESSIONS: Int = 100
        private val json = ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        private val documentType = object : TypeReference<LinkedHashMap<String, Any?>>() {}
    }

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

    /** Создаёт документ revision=0; неизвестные JSON-поля сохраняются без интерпретации. */
    @Throws(IOException::class)
    fun create(document: Map<String, Any?>): Map<String, Any?> = synchronized(connection) {
        val id = identifier(document["session_id"] as? String)
        validateVersion(document)
        if (document.containsKey("revision") && revision(document["revision"]) != 0L) {
            throw IOException("New research document requires revision zero")
        }
        val created = document["created_at"]?.let(::timestamp) ?: Instant.now().toString()
        val value = LinkedHashMap(document).apply {
            put("session_id", id)
            put("schema_version", 1)
            put("revision", 0L)
            put("created_at", created)
            put("updated_at", Instant.now().toString())
        }
        val encoded = encode(value)
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
            read(id)
        }
    }

    /** Читает сохранённый документ и проверяет envelope против SQL revision/id. */
    @Throws(IOException::class)
    fun get(id: String): Map<String, Any?> = synchronized(connection) {
        try {
            read(identifier(id))
        } catch (error: IOException) {
            throw error
        } catch (_: Exception) {
            throw IOException("Research catalog read unavailable")
        }
    }

    /**
     * Compare-and-set update под общим catalog_guard.
     * Старый revision, id/version или изменение created_at отклоняются без потери записи.
     */
    @Throws(IOException::class)
    fun update(id: String, expectedRevision: Long, document: Map<String, Any?>): Map<String, Any?> =
        synchronized(connection) {
            val checkedId = identifier(id)
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
                if (revision(old["revision"]) != expectedRevision) {
                    throw IOException("Research document changed; reload before retrying")
                }
                if (document.containsKey("created_at") && timestamp(document["created_at"]) != old["created_at"]) {
                    throw IOException("Research creation timestamp is immutable")
                }
                val next = expectedRevision + 1
                val value = LinkedHashMap(document).apply {
                    put("revision", next)
                    put("created_at", old["created_at"])
                    put("updated_at", Instant.now().toString())
                }
                val encoded = encode(value)
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

    /** Проверяет наличие session без изменения его revision или timestamps. */
    @Throws(IOException::class)
    fun exists(id: String): Boolean = synchronized(connection) {
        val checked = identifier(id)
        try {
            connection.prepareStatement("SELECT 1 FROM research_sessions_v1 WHERE session_id=?").use { query ->
                query.setString(1, checked)
                query.executeQuery().use { it.next() }
            }
        } catch (_: Exception) {
            throw IOException("Research catalog read unavailable")
        }
    }

    private fun read(id: String): Map<String, Any?> {
        connection.prepareStatement("SELECT revision,document_json,updated_at,CHAR_LENGTH(document_json) " +
            "FROM research_sessions_v1 WHERE session_id=?").use { query ->
            query.setString(1, id)
            query.executeQuery().use { rows ->
                if (!rows.next()) throw IOException("Unknown research session")
                if (rows.getLong(4) > MAX_DOCUMENT_BYTES) throw IOException("Research document size limit reached")
                val raw = rows.getString(2)
                if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_DOCUMENT_BYTES) {
                    throw IOException("Research document size limit reached")
                }
                val value = try {
                    json.readValue(raw, documentType)
                } catch (_: Exception) {
                    throw IOException("Malformed research document")
                }
                validateVersion(value)
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

    private fun <T> mutate(action: () -> T): T {
        val original = try { connection.autoCommit } catch (_: Exception) {
            throw IOException("Research catalog transaction unavailable")
        }
        var savepoint: Savepoint? = null
        try {
            if (original) connection.autoCommit = false else savepoint = connection.setSavepoint()
            connection.prepareStatement("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").use { lock ->
                lock.executeQuery().use { rows ->
                    if (!rows.next()) throw IOException("Research catalog guard unavailable")
                }
            }
            val result = action()
            if (original) connection.commit() else connection.releaseSavepoint(savepoint)
            return result
        } catch (error: Throwable) {
            try {
                if (original) {
                    connection.rollback()
                } else if (savepoint != null) {
                    connection.rollback(savepoint)
                    connection.releaseSavepoint(savepoint)
                }
            } catch (_: Exception) {
                if (error is Error) throw error
                throw IOException("Research catalog rollback unavailable")
            }
            if (error is Error) throw error
            if (error is IOException) throw error
            throw IOException("Research catalog transaction unavailable")
        } finally {
            if (original) {
                try { connection.autoCommit = true } catch (_: Exception) {
                    throw IOException("Research catalog transaction restoration unavailable")
                }
            }
        }
    }

    private fun encode(value: Map<String, Any?>): String {
        val output = ByteArrayOutputStream()
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

    private fun identifier(id: String?): String {
        if (id == null || !id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) ||
            try { UUID.fromString(id).toString() != id } catch (_: IllegalArgumentException) { true }) {
            throw IOException("Invalid research session identifier")
        }
        return id
    }

    private fun validateVersion(value: Map<String, Any?>) {
        if (revision(value["schema_version"]) != 1L) throw IOException("Unsupported research document version")
    }

    private fun revision(value: Any?): Long {
        if (value !is Byte && value !is Short && value !is Int && value !is Long) {
            throw IOException("Invalid research revision")
        }
        return (value as Number).toLong().also { if (it < 0) throw IOException("Invalid research revision") }
    }

    private fun timestamp(value: Any?): String {
        if (value !is String || value.length > 40) throw IOException("Invalid research timestamp")
        try { Instant.parse(value) } catch (_: Exception) { throw IOException("Invalid research timestamp") }
        return value
    }
}
