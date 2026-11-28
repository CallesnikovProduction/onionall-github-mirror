package dev.onionall.brawler.archive.internal;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Отделяет committed persistence от неудачного response readback.</b>
 *
 * <p>Настоящий H2 коммитит запись; JDBC proxy отклоняет только следующий readback.
 * Проверяется отсутствие ложного rollback, сохранность данных и безопасный replay.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class SnapshotPersistenceTest {
    private static final String URL =
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";

    @Test public void committedWriteAndDedupReadbackFailuresNeverRollbackAndReplaySafely() throws Exception {
        var root = Files.createTempDirectory("archive-postcommit");
        Files.createDirectories(root.resolve("objects"));
        var real = DriverManager.getConnection("jdbc:h2:file:" + root.resolve("catalog") +
            ";AUTO_SERVER=FALSE;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        ArchiveSchema.migrate(real);
        var injectAfterCommit = new AtomicBoolean();
        var rejectReadback = new AtomicBoolean();
        var rollbacks = new AtomicInteger();
        Connection proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (ignored, method, arguments) -> {
                if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql &&
                    sql.startsWith(SnapshotRepository.LATEST.substring(0, SnapshotRepository.LATEST.indexOf(" ON "))) && rejectReadback.compareAndSet(true, false)) {
                    throw new SQLException("Controlled response readback failure");
                }
                if (method.getName().equals("rollback")) {
                    rollbacks.incrementAndGet();
                }
                try {
                    Object result = method.invoke(real, arguments);
                    if (method.getName().equals("commit") && injectAfterCommit.compareAndSet(true, false)) {
                        rejectReadback.set(true);
                    }
                    return result;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        String job, page;
        try (var context = new ArchiveContext(root, proxy)) {
            var plans = new PlanRepository(context);
            var graph = new GraphRepository(context, plans);
            var snapshots = new SnapshotRepository(context, plans, graph);
            job = context.serializableRead(() -> (String) plans.createPlan(List.of(URL), 1, 1, "fixture").get("plan_id"));
            context.serializableRead(() -> plans.approvePlan(job));
            byte[] html = "<html>postcommit</html>".getBytes(StandardCharsets.UTF_8);
            injectAfterCommit.set(true);
            IOException first = assertThrows(IOException.class, () -> context.serializableRead(() ->
                snapshots.archive(job, URL, 0, URL, 200, "text/html", html, List.of())));
            assertTrue(first.getMessage().contains("COMMITTED"));
            assertEquals(0, rollbacks.get());
            assertTrue(real.getAutoCommit());
            var committed = context.serializableRead(() -> snapshots.pages(job, 1));
            page = (String) ((Map<?, ?>) ((List<?>) committed.get("pages")).getFirst()).get("page_id");
            assertEquals(1, ((List<?>) context.serializableRead(() -> snapshots.page(page)).get("history")).size());
            injectAfterCommit.set(true);
            IOException duplicate = assertThrows(IOException.class, () -> context.serializableRead(() ->
                snapshots.archive(job, URL, 0, URL, 200, "text/html", html, List.of())));
            assertTrue(duplicate.getMessage().contains("COMMITTED"));
            assertEquals(0, rollbacks.get());
            var replayed = context.serializableRead(() -> snapshots.archive(job, URL, 0, URL, 200, "text/html", html, List.of()));
            assertEquals(page, replayed.get("page_id"));
            assertEquals(1, ((List<?>) replayed.get("history")).size());
        }
        try (var reopened = new dev.onionall.brawler.archive.ArchiveStore(root)) {
            assertEquals(1L, reopened.job(job).get("snapshots"));
            assertEquals(page, reopened.page(page).get("page_id"));
        }
    }
}
