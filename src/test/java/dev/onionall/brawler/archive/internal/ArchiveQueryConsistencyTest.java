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

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Проверяет единый read boundary metadata export на настоящем H2.</b>
 *
 * <p>JDBC proxy пытается записать страницу между job projection и pages query.
 * H2 lock timeout детерминированно доказывает удержание catalog_guard; sleeps нет.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveQueryConsistencyTest {
    private static final String URL =
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";
    private static final byte[] HTML = "<html>consistent export</html>".getBytes(StandardCharsets.UTF_8);

    @Test public void exportHoldsGuardAcrossJobPagesAndLinksUntilReadCompletes() throws Exception {
        var root = Files.createTempDirectory("archive-export-consistency");
        Files.createDirectories(root.resolve("objects"));
        String jdbc = "jdbc:h2:file:" + root.resolve("catalog") +
            ";AUTO_SERVER=FALSE;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE";
        Connection realReader = DriverManager.getConnection(jdbc, "sa", "");
        Connection realWriter = DriverManager.getConnection(jdbc, "sa", "");
        ArchiveSchema.migrate(realReader);
        try (var statement = realWriter.createStatement()) {
            statement.execute("SET LOCK_TIMEOUT 100");
        }
        var armed = new AtomicBoolean();
        var intercepted = new AtomicBoolean();
        String[] job = new String[1];
        try (var writer = new ArchiveContext(root, realWriter)) {
            var writerPlans = new PlanRepository(writer);
            var writerGraph = new GraphRepository(writer, writerPlans);
            var writerSnapshots = new SnapshotRepository(writer, writerPlans, writerGraph);
            Connection proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (ignored, method, arguments) -> {
                    if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql &&
                        sql.startsWith(SnapshotRepository.LATEST) && armed.compareAndSet(true, false)) {
                        intercepted.set(true);
                        IOException rejected = assertThrows(IOException.class, () -> writer.serializableRead(() ->
                            writerSnapshots.archive(job[0], URL, 0, URL, 200, "text/html", HTML, List.of())));
                        Throwable cause = rejected;
                        while (cause != null && !(cause instanceof SQLException)) {
                            cause = cause.getCause();
                        }
                        assertTrue("Writer must fail because export owns catalog_guard", cause instanceof SQLException);
                        assertEquals(50200, ((SQLException) cause).getErrorCode());
                    }
                    try {
                        return method.invoke(realReader, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            try (var reader = new ArchiveContext(root, proxy)) {
                var plans = new PlanRepository(reader);
                var observations = new ObservationRepository(reader, plans);
                var graph = new GraphRepository(reader, plans);
                var snapshots = new SnapshotRepository(reader, plans, graph);
                var queries = new ArchiveQueryService(reader, plans, observations, snapshots, graph);
                job[0] = reader.serializableRead(() ->
                    (String) plans.createPlan(List.of(URL), 1, 1, "consistency-fixture").get("plan_id"));
                reader.serializableRead(() -> plans.approvePlan(job[0]));
                armed.set(true);
                Map<String, Object> exported = reader.serializableRead(() -> queries.exportSummary(job[0], 10));
                assertTrue(intercepted.get());
                assertEquals(0L, ((Map<?, ?>) exported.get("job")).get("archived_pages"));
                assertTrue(((List<?>) exported.get("pages")).isEmpty());
                assertTrue(((List<?>) exported.get("links")).isEmpty());
                writer.serializableRead(() -> writerSnapshots.archive(job[0], URL, 0, URL, 200,
                    "text/html", HTML, List.of()));
                Map<String, Object> after = reader.serializableRead(() -> queries.exportSummary(job[0], 10));
                assertEquals(1L, ((Map<?, ?>) after.get("job")).get("archived_pages"));
                assertEquals(1, ((List<?>) after.get("pages")).size());
            }
        }
    }
}
