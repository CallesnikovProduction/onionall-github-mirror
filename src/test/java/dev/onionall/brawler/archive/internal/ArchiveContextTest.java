package dev.onionall.brawler.archive.internal;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Детерминированные проверки владения JDBC и неуспешного cleanup.</b>
 *
 * <p>Управляемый proxy позволяет различать ошибку body, commit и restoration;
 * это unit-проверки coordinator, не интеграционная проверка H2.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveContextTest {
    @Test public void successfulTransactionRestoresStateAndReadRequiresOwnership() throws Exception {
        var jdbc = new Jdbc();
        try (var context = context(jdbc)) {
            assertThrows(IllegalStateException.class, context::connection);
            assertSame(jdbc.connection, context.serializableRead(context::connection));
            assertEquals("saved", context.serializableRead(() -> context.transaction(() -> "saved")));
            assertEquals(List.of("begin", "guard", "commit", "restore"), jdbc.events);
            assertTrue(jdbc.autoCommit);
        }
        assertEquals("close", jdbc.events.getLast());
    }

    @Test public void uncheckedFailureRollsBackAndNextTransactionSucceeds() throws Exception {
        var jdbc = new Jdbc();
        try (var context = context(jdbc)) {
            var failure = new IllegalStateException("body failure");
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> context.transaction(() -> { throw failure; })));
            assertEquals(List.of("begin", "guard", "rollback", "restore"), jdbc.events);
            assertEquals("next", context.transaction(() -> "next"));
            assertTrue(jdbc.autoCommit);
        }
    }

    @Test public void rollbackFailureNeverEnablesAutocommitAndQuarantinesConnection() throws Exception {
        var jdbc = new Jdbc();
        jdbc.failRollback = true;
        var context = context(jdbc);
        var failure = new IOException("body failure");
        assertSame(failure, assertThrows(IOException.class,
            () -> context.transaction(() -> { throw failure; })));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of("begin", "guard", "rollback", "close"), jdbc.events);
        assertTrue(jdbc.closed);
        int calls = jdbc.events.size();
        assertThrows(IOException.class, () -> context.serializableRead(() -> "unreachable"));
        assertThrows(IOException.class, () -> context.transaction(() -> "unreachable"));
        context.close();
        assertEquals(calls, jdbc.events.size());
    }

    @Test public void commitFailureIsUnknownEvenIfRollbackCleanupSucceeds() throws Exception {
        var jdbc = new Jdbc();
        jdbc.failCommit = true;
        var context = context(jdbc);
        var failure = assertThrows(IOException.class, () -> context.transaction(() -> "saved"));
        assertTrue(failure.getMessage().contains("unknown"));
        assertEquals(List.of("begin", "guard", "commit", "rollback", "close"), jdbc.events);
        assertThrows(IOException.class, () -> context.serializableRead(() -> "unreachable"));
    }

    @Test public void restorationAfterCommitReportsCommittedAndDoesNotRollback() throws Exception {
        var jdbc = new Jdbc();
        jdbc.failRestore = true;
        var context = context(jdbc);
        var failure = assertThrows(IOException.class, () -> context.transaction(() -> "saved"));
        assertTrue(failure.getMessage().contains("committed"));
        assertEquals(List.of("begin", "guard", "commit", "restore", "close"), jdbc.events);
    }

    @Test public void restorationAndCloseFailuresDoNotMaskBodyFailure() throws Exception {
        var jdbc = new Jdbc();
        jdbc.failRestore = true;
        jdbc.failClose = true;
        var context = context(jdbc);
        var failure = new IOException("body failure");
        assertSame(failure, assertThrows(IOException.class,
            () -> context.transaction(() -> { throw failure; })));
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(List.of("begin", "guard", "rollback", "restore", "close"), jdbc.events);
    }

    @Test public void failedCloseBlocksOperationsAndCanBeRetriedUntilReleased() throws Exception {
        var jdbc = new Jdbc();
        jdbc.failClose = true;
        var context = context(jdbc);
        assertThrows(IOException.class, context::close);
        assertEquals(List.of("close"), jdbc.events);
        assertThrows(IOException.class, () -> context.serializableRead(() -> "unreachable"));
        assertThrows(IOException.class, () -> context.transaction(() -> "unreachable"));
        assertEquals(List.of("close"), jdbc.events);
        jdbc.failClose = false;
        context.close();
        assertEquals(List.of("close", "close"), jdbc.events);
        context.close();
        assertEquals(List.of("close", "close"), jdbc.events);
    }

    @Test public void failedQuarantineCloseCanBeRetriedWithoutReusingConnection() throws Exception {
        var jdbc = new Jdbc();
        jdbc.failCommit = true;
        jdbc.failClose = true;
        var context = context(jdbc);
        var failure = assertThrows(IOException.class, () -> context.transaction(() -> "saved"));
        assertTrue(failure.getMessage().contains("unknown"));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of("begin", "guard", "commit", "rollback", "close"), jdbc.events);
        assertThrows(IOException.class, () -> context.serializableRead(() -> "unreachable"));
        jdbc.failClose = false;
        context.close();
        assertEquals(List.of("begin", "guard", "commit", "rollback", "close", "close"), jdbc.events);
        context.close();
        assertEquals(6, jdbc.events.size());
    }

    @Test public void nestedTransactionIsRejectedWithoutEarlyCommit() throws Exception {
        var jdbc = new Jdbc();
        try (var context = context(jdbc)) {
            assertThrows(IOException.class,
                () -> context.transaction(() -> context.transaction(() -> "nested")));
            assertEquals(List.of("begin", "guard", "rollback", "restore"), jdbc.events);
        }
    }

    @Test public void reentrantCloseCannotInvalidateAnActiveTransaction() throws Exception {
        var jdbc = new Jdbc();
        try (var context = context(jdbc)) {
            assertThrows(IOException.class, () -> context.transaction(() -> {
                context.close();
                return "unreachable";
            }));
            assertFalse(jdbc.closed);
            assertEquals(List.of("begin", "guard", "rollback", "restore"), jdbc.events);
            assertEquals("next", context.transaction(() -> "next"));
        }
    }

    @Test public void fatalBodyFailureRemainsPrimaryAndRollsBack() throws Exception {
        var jdbc = new Jdbc();
        try (var context = context(jdbc)) {
            var fatal = new AssertionError("fatal body failure");
            assertSame(fatal, assertThrows(AssertionError.class,
                () -> context.transaction(() -> { throw fatal; })));
            assertEquals(List.of("begin", "guard", "rollback", "restore"), jdbc.events);
        }
    }

    @Test public void fatalCommitFailureRemainsPrimaryWithUnknownOutcome() throws Exception {
        var jdbc = new Jdbc();
        jdbc.commitError = new AssertionError("fatal commit failure");
        var context = context(jdbc);
        assertSame(jdbc.commitError, assertThrows(AssertionError.class,
            () -> context.transaction(() -> "saved")));
        assertTrue(jdbc.commitError.getSuppressed()[0].getMessage().contains("unknown"));
        assertEquals(List.of("begin", "guard", "commit", "rollback", "close"), jdbc.events);
    }

    @Test public void closeAndOtherOperationsWaitForActiveOwner() throws Exception {
        var jdbc = new Jdbc();
        var context = context(jdbc);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var readRan = new AtomicBoolean();
        try (var workers = Executors.newFixedThreadPool(3)) {
            var owner = workers.submit(() -> context.serializableRead(() -> {
                entered.countDown();
                await(release);
                assertFalse(jdbc.closed);
                assertFalse(readRan.get());
                return "active";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var close = workers.submit(() -> {
                closeStarted.countDown();
                context.close();
                return null;
            });
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            var reader = workers.submit(() -> {
                try {
                    return context.serializableRead(() -> { readRan.set(true); return "serializableRead"; });
                } catch (IOException closed) {
                    return "closed";
                }
            });
            release.countDown();
            assertEquals("active", owner.get(5, TimeUnit.SECONDS));
            close.get(5, TimeUnit.SECONDS);
            assertTrue(List.of("serializableRead", "closed").contains(reader.get(5, TimeUnit.SECONDS)));
            assertTrue(jdbc.closed);
            assertThrows(IOException.class, () -> context.serializableRead(context::connection));
        } finally {
            release.countDown();
            context.close();
        }
    }

    private static ArchiveContext context(Jdbc jdbc) {
        return new ArchiveContext(Path.of(System.getProperty("java.io.tmpdir")), jdbc.connection);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Test interrupted", interrupted);
        }
    }

    private static final class Jdbc {
        final List<String> events = new ArrayList<>();
        boolean autoCommit = true;
        boolean closed;
        boolean failCommit;
        boolean failRollback;
        boolean failRestore;
        boolean failClose;
        Error commitError;
        final Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if (closed && !method.getName().equals("close")) {
                    throw new AssertionError("JDBC called after close: " + method.getName());
                }
                return switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit;
                    case "setAutoCommit" -> {
                        boolean next = (boolean) args[0];
                        events.add(next ? "restore" : "begin");
                        if (next && failRestore) throw new SQLException("restore failed");
                        autoCommit = next;
                        yield null;
                    }
                    case "prepareStatement" -> {
                        events.add("guard");
                        yield statement();
                    }
                    case "commit" -> {
                        events.add("commit");
                        if (commitError != null) throw commitError;
                        if (failCommit) throw new SQLException("commit failed");
                        yield null;
                    }
                    case "rollback" -> {
                        events.add("rollback");
                        if (failRollback) throw new SQLException("rollback failed");
                        yield null;
                    }
                    case "close" -> {
                        events.add("close");
                        closed = true;
                        if (failClose) throw new SQLException("close failed");
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected JDBC call: " + method.getName());
                };
            });

        private PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                        new Class<?>[] {ResultSet.class}, (rows, call, values) -> switch (call.getName()) {
                            case "next" -> true;
                            case "close" -> null;
                            default -> throw new AssertionError("Unexpected ResultSet call: " + call.getName());
                        });
                    case "close" -> null;
                    default -> throw new AssertionError("Unexpected statement call: " + method.getName());
                });
        }
    }
}
