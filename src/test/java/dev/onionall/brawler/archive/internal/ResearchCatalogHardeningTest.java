package dev.onionall.brawler.archive.internal;

import dev.onionall.brawler.research.ResearchCatalog;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Проверяет ошибки standalone/savepoint research persistence без потери primary.</b>
 *
 * <p>Proxy инъецирует JDBC faults поверх настоящего disposable H2. Независимый
 * connection проверяет фактическую видимость записи после ошибочного ответа.
 * Borrowed connection освобождает исключительно тестовый внешний владелец.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ResearchCatalogHardeningTest {
    @Test public void failedRollbackCannotImplicitlyCommitOrReuseBorrowedConnection() throws Exception {
        try (var fixture = new Jdbc()) {
            var catalog = new ResearchCatalog(fixture.borrowed);
            String id = UUID.randomUUID().toString();
            fixture.failRead = true;
            fixture.failRollback = true;
            var failure = assertThrows(IOException.class, () -> catalog.create(document(id)));
            assertTrue(failure.getMessage().contains("transaction unavailable"));
            assertEquals(1, failure.getSuppressed().length);
            assertSanitized(failure);
            assertFalse(fixture.actual.getAutoCommit());
            assertFalse(fixture.events.contains("restore"));
            assertFalse(fixture.events.contains("close"));
            try (var observer = DriverManager.getConnection(fixture.url)) {
                assertFalse(new ResearchCatalog(observer).exists(id));
            }
            assertThrows(IOException.class, () -> catalog.get(id));
            assertThrows(IOException.class, () -> catalog.exists(id));
            assertThrows(IOException.class, () -> catalog.create(document(UUID.randomUUID().toString())));
            fixture.actual.rollback();
            fixture.actual.setAutoCommit(true);
            assertFalse(new ResearchCatalog(fixture.borrowed).exists(id));
        }
    }

    @Test public void lostCommitAckReportsUnknownAndRequiresCallerRecovery() throws Exception {
        try (var fixture = new Jdbc()) {
            var catalog = new ResearchCatalog(fixture.borrowed);
            String id = UUID.randomUUID().toString();
            fixture.failCommitAfter = true;
            var failure = assertThrows(IOException.class, () -> catalog.create(document(id)));
            assertTrue(failure.getMessage().contains("unknown"));
            assertSanitized(failure);
            assertFalse(fixture.events.contains("restore"));
            assertFalse(fixture.events.contains("close"));
            assertThrows(IOException.class, () -> catalog.exists(id));
            try (var observer = DriverManager.getConnection(fixture.url)) {
                assertEquals(id, new ResearchCatalog(observer).get(id).get("session_id"));
            }
            fixture.actual.rollback();
            fixture.actual.setAutoCommit(true);
            assertEquals(id, new ResearchCatalog(fixture.borrowed).get(id).get("session_id"));
        }
    }

    @Test public void restorationFailureAfterCommitKeepsCommittedOutcomeAndDoesNotRollback() throws Exception {
        try (var fixture = new Jdbc()) {
            var catalog = new ResearchCatalog(fixture.borrowed);
            String id = UUID.randomUUID().toString();
            fixture.failRestore = true;
            var failure = assertThrows(IOException.class, () -> catalog.create(document(id)));
            assertTrue(failure.getMessage().contains("committed"));
            assertSanitized(failure);
            assertEquals(List.of("begin", "commit", "restore"), fixture.events);
            assertThrows(IOException.class, () -> catalog.exists(id));
            try (var observer = DriverManager.getConnection(fixture.url)) {
                assertTrue(new ResearchCatalog(observer).exists(id));
            }
        }
    }

    @Test public void savepointCleanupFailurePreservesPrimaryAndOuterTransactionOwnership() throws Exception {
        try (var fixture = new Jdbc()) {
            var catalog = new ResearchCatalog(fixture.borrowed);
            String id = UUID.randomUUID().toString();
            var saved = catalog.create(document(id));
            fixture.events.clear();
            fixture.actual.setAutoCommit(false);
            fixture.failRollback = true;
            // Omit document revision to reach persisted CAS check inside mutate.
            var replacement = new java.util.LinkedHashMap<String, Object>();
            saved.forEach(replacement::put);
            replacement.remove("revision");
            var failure = assertThrows(IOException.class, () -> catalog.update(id, 7, replacement));
            assertTrue(failure.getMessage().contains("changed"));
            assertEquals(1, failure.getSuppressed().length);
            assertSanitized(failure);
            assertEquals(List.of("rollback"), fixture.events);
            assertFalse(fixture.actual.getAutoCommit());
            assertThrows(IOException.class, () -> catalog.get(id));
            fixture.actual.rollback();
            fixture.actual.setAutoCommit(true);
            assertEquals(saved, new ResearchCatalog(fixture.borrowed).get(id));
        }
    }

    @Test public void fatalBodyFailureRemainsPrimaryWithSafeRollback() throws Exception {
        try (var fixture = new Jdbc()) {
            var catalog = new ResearchCatalog(fixture.borrowed);
            var fatal = new AssertionError("controlled fatal body failure");
            fixture.readError = fatal;
            String id = UUID.randomUUID().toString();
            assertSame(fatal, assertThrows(AssertionError.class, () -> catalog.create(document(id))));
            assertEquals(List.of("begin", "rollback", "restore"), fixture.events);
            assertFalse(catalog.exists(id));
        }
    }

    private static Map<String, Object> document(String id) {
        return Map.of("session_id", id, "schema_version", 1, "query", "fixture");
    }

    private static void assertSanitized(Throwable failure) {
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private-jdbc-diagnostic"));
        for (Throwable cleanup : failure.getSuppressed()) {
            assertNull(cleanup.getCause());
            assertFalse(cleanup.toString().contains("private-jdbc-diagnostic"));
        }
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException invocation) {
            throw invocation.getCause();
        }
    }

    private static final class Jdbc implements AutoCloseable {
        final String url = "jdbc:h2:mem:" + UUID.randomUUID();
        final Connection actual;
        final Connection borrowed;
        final List<String> events = new ArrayList<>();
        boolean failRead;
        boolean failRollback;
        boolean failCommitAfter;
        boolean failRestore;
        Error readError;

        Jdbc() throws Exception {
            actual = DriverManager.getConnection(url);
            try (var statement = actual.createStatement()) {
                statement.execute("CREATE TABLE catalog_guard(id INT PRIMARY KEY)");
                statement.execute("INSERT INTO catalog_guard VALUES(1)");
            }
            borrowed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setAutoCommit" -> {
                            boolean next = (boolean) args[0];
                            events.add(next ? "restore" : "begin");
                            if (next && failRestore) throw new SQLException("private-jdbc-diagnostic restore");
                        }
                        case "commit" -> {
                            events.add("commit");
                            actual.commit();
                            if (failCommitAfter) throw new SQLException("private-jdbc-diagnostic lost ack");
                            return null;
                        }
                        case "rollback" -> {
                            events.add("rollback");
                            if (failRollback) throw new SQLException("private-jdbc-diagnostic rollback");
                        }
                        case "close" -> events.add("close");
                        case "prepareStatement" -> {
                            var prepared = (PreparedStatement) invoke(method, actual, args);
                            if (((String) args[0]).startsWith("SELECT revision,document_json")) {
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                    new Class<?>[] {PreparedStatement.class}, (statement, call, values) -> {
                                        if (call.getName().equals("executeQuery")) {
                                            if (readError != null) {
                                                Error error = readError;
                                                readError = null;
                                                throw error;
                                            }
                                            if (failRead) {
                                                failRead = false;
                                                throw new SQLException("private-jdbc-diagnostic read");
                                            }
                                        }
                                        return invoke(call, prepared, values);
                                    });
                            }
                            return prepared;
                        }
                        default -> { }
                    }
                    return invoke(method, actual, args);
                });
        }

        @Override public void close() throws SQLException {
            actual.close();
        }
    }
}
