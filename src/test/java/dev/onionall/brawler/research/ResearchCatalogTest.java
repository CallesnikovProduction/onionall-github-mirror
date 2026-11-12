package dev.onionall.brawler.research;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * Настоящий H2 catalog: CAS, restart и сохранение владения JDBC-транзакцией.
 * Это persistence regression, не доказательство внешнего discovery или crawl.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
public class ResearchCatalogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Connection open(Path directory) throws Exception {
        var connection = DriverManager.getConnection("jdbc:h2:file:" + directory.resolve("catalog") +
            ";AUTO_SERVER=FALSE;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS catalog_guard (id INT PRIMARY KEY)");
            statement.execute("MERGE INTO catalog_guard KEY(id) VALUES(1)");
            statement.execute("CREATE TABLE IF NOT EXISTS owner_values (id INT PRIMARY KEY)");
        }
        return connection;
    }

    private Map<String, Object> document(String id) {
        var document = new LinkedHashMap<String, Object>();
        document.put("schema_version", 1);
        document.put("session_id", id);
        document.put("query", "catalog fixture");
        document.put("candidates", List.of(Map.of("original_result_url", "fixture-only", "selected", false)));
        return document;
    }

    @Test public void documentSurvivesConnectionRestartAndRetainsUnknownFields() throws Exception {
        Path directory = temporary.newFolder().toPath();
        String id = UUID.randomUUID().toString();
        Map<String, Object> saved;
        try (var connection = open(directory)) {
            var catalog = new ResearchCatalog(connection);
            assertFalse(catalog.exists(id));
            saved = catalog.create(document(id));
            assertEquals(0L, saved.get("revision"));
            Instant.parse((String) saved.get("created_at"));
            assertTrue(catalog.exists(id));
            assertEquals(saved, catalog.get(id));
            assertFalse(connection.isClosed());
            assertTrue(connection.getAutoCommit());
        }
        try (var connection = open(directory)) {
            assertEquals(saved, new ResearchCatalog(connection).get(id));
        }
    }

    @Test public void compareAndSetRejectsStaleRevisionWithoutLosingProvenance() throws Exception {
        try (var connection = open(temporary.newFolder().toPath())) {
            var catalog = new ResearchCatalog(connection);
            String id = UUID.randomUUID().toString();
            var original = catalog.create(document(id));
            var changed = new LinkedHashMap<>(original);
            changed.put("approval", Map.of("scope", "fixture", "approved", true));
            var saved = catalog.update(id, 0L, changed);
            assertEquals(1L, saved.get("revision"));
            assertEquals(original.get("created_at"), saved.get("created_at"));
            assertEquals(original.get("candidates"), saved.get("candidates"));
            assertThrows(IOException.class, () -> catalog.update(id, 0L, changed));
            assertEquals(saved, catalog.get(id));
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test public void invalidEnvelopeAndUtf8SizeCannotInsertOrReplaceDocuments() throws Exception {
        try (var connection = open(temporary.newFolder().toPath())) {
            var catalog = new ResearchCatalog(connection);
            assertThrows(IOException.class, () -> catalog.create(document("invalid")));
            String id = UUID.randomUUID().toString();
            var wrong = document(id);
            wrong.put("schema_version", 2);
            assertThrows(IOException.class, () -> catalog.create(wrong));
            assertFalse(catalog.exists(id));
            var huge = document(id);
            huge.put("snippet", "я".repeat(ResearchCatalog.MAX_DOCUMENT_BYTES / 2));
            assertThrows(IOException.class, () -> catalog.create(huge));
            assertFalse(catalog.exists(id));
            var saved = catalog.create(document(id));
            var update = new LinkedHashMap<>(saved);
            update.put("created_at", "2020-01-01T00:00:00Z");
            assertThrows(IOException.class, () -> catalog.update(id, 0L, update));
            update.put("created_at", saved.get("created_at"));
            update.put("session_id", UUID.randomUUID().toString());
            assertThrows(IOException.class, () -> catalog.update(id, 0L, update));
            assertEquals(saved, catalog.get(id));
        }
    }

    @Test public void failedSqlUpdateRollsBackAndDoesNotExposePayload() throws Exception {
        try (var connection = open(temporary.newFolder().toPath())) {
            var catalog = new ResearchCatalog(connection);
            String id = UUID.randomUUID().toString();
            var saved = catalog.create(document(id));
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE research_sessions_v1 ADD CONSTRAINT fixture_reject_update CHECK (revision=0)");
            }
            var update = new LinkedHashMap<>(saved);
            update.put("private_fixture_value", "must-not-appear-in-errors");
            IOException error = assertThrows(IOException.class, () -> catalog.update(id, 0L, update));
            assertFalse(error.toString().contains("must-not-appear-in-errors"));
            assertNull(error.getCause());
            assertEquals(saved, catalog.get(id));
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test public void callerTransactionUsesSavepointAndRemainsOwnedByCaller() throws Exception {
        try (var connection = open(temporary.newFolder().toPath())) {
            var catalog = new ResearchCatalog(connection);
            String id = UUID.randomUUID().toString();
            var saved = catalog.create(document(id));
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO owner_values VALUES(1)")) {
                insert.executeUpdate();
            }
            var updated = catalog.update(id, 0L, saved);
            assertEquals(1L, updated.get("revision"));
            assertFalse(connection.getAutoCommit());
            assertThrows(IOException.class, () -> catalog.update(id, 0L, saved));
            assertFalse(connection.getAutoCommit());
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM owner_values")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
            connection.rollback();
            connection.setAutoCommit(true);
            assertEquals(saved, catalog.get(id));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM owner_values")) {
                rows.next();
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test public void constructorCannotImplicitlyCommitCallerWorkWhenTableIsMissing() throws Exception {
        try (var connection = open(temporary.newFolder().toPath())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) { statement.executeUpdate("INSERT INTO owner_values VALUES(1)"); }
            assertThrows(IOException.class, () -> new ResearchCatalog(connection));
            assertFalse(connection.getAutoCommit());
            connection.rollback();
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM owner_values")) {
                rows.next();
                assertEquals(0, rows.getInt(1));
            }
        }
    }
}
