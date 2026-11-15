package dev.onionall.brawler.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Проверяет конкурентный публичный API на настоящем disposable H2.</b>
 *
 * <p>Barriers запускают независимых callers вместе; проверки состояния после reopen
 * отделены от proxy unit tests coordinator. Сетевых запросов и Tor здесь нет.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveConcurrencyTest {
    private static final String URL =
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";
    private static final byte[] HTML = "<html><title>fixture</title>archive</html>".getBytes(StandardCharsets.UTF_8);

    private static String plan(ArchiveStore store, int pages) throws IOException {
        String id = (String) store.createPlan(List.of(URL), 1, pages, "concurrency-fixture").get("plan_id");
        store.approvePlan(id);
        return id;
    }

    private static <T> List<T> together(List<Callable<T>> actions) throws Exception {
        var barrier = new CyclicBarrier(actions.size());
        try (var workers = Executors.newFixedThreadPool(actions.size())) {
            var futures = new ArrayList<java.util.concurrent.Future<T>>();
            for (var action : actions) {
                futures.add(workers.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return action.call();
                }));
            }
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    @Test public void sharedStoreSerializesSnapshotsPlansResearchAndReadsAcrossReopen() throws Exception {
        Path root = Files.createTempDirectory("archive-mixed-concurrency");
        String job;
        var sessions = new ArrayList<String>();
        try (var store = new ArchiveStore(root)) {
            job = plan(store, 4);
            var actions = new ArrayList<Callable<Object>>();
            for (int i = 0; i < 12; i++) {
                int index = i;
                String session = UUID.randomUUID().toString();
                sessions.add(session);
                actions.add(() -> {
                    store.archive(job, URL + "page" + index % 4, 1, URL, 200, "text/html", HTML, List.of());
                    var saved = store.createResearchSession(Map.of("schema_version", 1, "session_id", session));
                    assertEquals(1L, store.updateResearchSession(session, 0L, saved).get("revision"));
                    assertTrue(store.researchSessionExists(session));
                    String another = plan(store, 1);
                    assertTrue((Boolean) store.plan(another).get("approved"));
                    store.pages(job, 100);
                    store.search("archive", job, 100);
                    return null;
                });
            }
            together(actions);
            assertEquals(4L, store.job(job).get("archived_pages"));
            assertEquals(4L, store.job(job).get("snapshots"));
            assertEquals(13, ((List<?>) store.plans(null, 100).get("plans")).size());
        }
        try (var store = new ArchiveStore(root)) {
            assertEquals(4L, store.job(job).get("snapshots"));
            for (String session : sessions) {
                assertEquals(1L, store.researchSession(session).get("revision"));
            }
            assertEquals(4, ((List<?>) store.search("archive", job, 100).get("results")).size());
        }
    }

    @Test public void independentConnectionsSerializePageBudgetAndIdenticalSnapshots() throws Exception {
        Path root = Files.createTempDirectory("archive-two-connections");
        try (var left = new ArchiveStore(root); var right = new ArchiveStore(root)) {
            String job = plan(left, 2);
            var first = together(List.<Callable<Map<String, Object>>>of(
                () -> left.archive(job, URL, 0, URL, 200, "text/html", HTML, List.of()),
                () -> right.archive(job, URL, 0, URL, 200, "text/html", HTML, List.of())));
            assertEquals(first.get(0).get("snapshot_id"), first.get(1).get("snapshot_id"));
            var accepted = together(List.<Callable<Boolean>>of(
                () -> archiveOrLimit(left, job, URL + "one"),
                () -> archiveOrLimit(right, job, URL + "two")));
            assertEquals(1, accepted.stream().filter(Boolean::booleanValue).count());
            assertEquals(2L, left.job(job).get("archived_pages"));
            assertEquals(2L, right.job(job).get("snapshots"));
        }
    }

    private static boolean archiveOrLimit(ArchiveStore store, String job, String url) throws IOException {
        try {
            store.archive(job, url, 1, URL, 200, "text/html", HTML, List.of());
            return true;
        } catch (IOException rejected) {
            if (!rejected.getMessage().contains("page limit")) {
                throw rejected;
            }
            return false;
        }
    }

    @Test public void missingBlobReplayRepairsButCorruptBlobRejectsBeforeMetadataCommit() throws Exception {
        Path root = Files.createTempDirectory("archive-repair");
        String job, page;
        Path blob;
        try (var store = new ArchiveStore(root)) {
            job = plan(store, 1);
            var saved = store.archive(job, URL, 0, URL, 200, "text/html", HTML, List.of());
            page = (String) saved.get("page_id");
            blob = Path.of((String) saved.get("storage_location"));
            Files.delete(blob);
            assertThrows(IOException.class, () -> store.page(page));
            var replayed = store.archive(job, URL, 0, URL, 200, "text/html", HTML, List.of());
            assertEquals(saved.get("snapshot_id"), replayed.get("snapshot_id"));
            assertArrayEquals(HTML, Files.readAllBytes(blob));
            Object lastSeen = replayed.get("last_seen");
            byte[] corrupt = HTML.clone();
            corrupt[0] ^= 1;
            Files.write(blob, corrupt);
            assertThrows(IOException.class, () -> store.archive(job, URL, 0, URL, 200, "text/html", HTML, List.of()));
            assertArrayEquals(corrupt, Files.readAllBytes(blob));
            var rows = (List<?>) store.pages(job, 1).get("pages");
            assertEquals(lastSeen, ((Map<?, ?>) rows.getFirst()).get("last_seen"));
            assertEquals(1L, store.job(job).get("snapshots"));
            // Fixture restoration is explicit; production never overwrites a corrupt object.
            Files.delete(blob);
        }
        try (var store = new ArchiveStore(root)) {
            assertEquals(page, store.archive(job, URL, 0, URL, 200, "text/html", HTML, List.of()).get("page_id"));
            assertEquals(1L, store.job(job).get("snapshots"));
        }
    }

    @Test public void closedStoreRejectsEveryResourcePathAndNullLinkDoesNotWrite() throws Exception {
        var store = new ArchiveStore(Files.createTempDirectory("archive-close-boundary"));
        String job = plan(store, 1);
        assertThrows(IOException.class, () -> store.archive(job, URL, 0, URL, 200, "text/html", HTML,
            java.util.Arrays.asList((Map<String, Object>) null)));
        assertEquals(0L, store.job(job).get("archived_pages"));
        store.close();
        store.close();
        assertThrows(IOException.class, () -> store.plan(job));
        assertThrows(IOException.class, () -> store.approvePlan(job));
        assertThrows(IOException.class, () -> store.createPlan(List.of(URL), 1, 1, "fixture"));
        assertThrows(IOException.class, () -> store.plans(null, 1));
        assertThrows(IOException.class, () -> store.job(job));
        assertThrows(IOException.class, () -> store.admissionObservation(job));
        assertThrows(IOException.class, () -> store.recordAdmission(job, Map.of()));
        assertThrows(IOException.class, () -> store.recordJobStatus(job, Map.of()));
        assertThrows(IOException.class, store::hasExecutionHistory);
        assertThrows(IOException.class, () -> store.pages(job, 1));
        assertThrows(IOException.class, () -> store.page(job));
        assertThrows(IOException.class, () -> store.links(job, 1));
        assertThrows(IOException.class, () -> store.search("fixture", job, 1));
        assertThrows(IOException.class, () -> store.search("fixture", job, null, 1));
        assertThrows(IOException.class, () -> store.exportSummary(job, 1));
        assertThrows(IOException.class, () -> store.recordFailure(job, URL, "FETCH_ERROR"));
        assertThrows(IOException.class, () -> store.recordFailure(job, URL, "FETCH_ERROR", FailureObservation.unknown()));
        assertThrows(IOException.class, () -> store.failureHistory(job, null, 1));
        assertThrows(IOException.class, () -> store.archive(job, URL, 0, URL, 200, "text/html", HTML, List.of()));
        assertThrows(IOException.class, () -> store.importHtml(job, URL, HTML, List.of()));
        assertThrows(IOException.class, () -> store.createResearchSession(Map.of()));
        assertThrows(IOException.class, () -> store.researchSession(job));
        assertThrows(IOException.class, () -> store.updateResearchSession(job, 0, Map.of()));
        assertThrows(IOException.class, () -> store.researchSessionExists(job));
    }

    @Test public void hostAndJobSearchFiltersApplyBeforeLimitAndTreatQueryLiterally() throws Exception {
        String host = java.net.URI.create(URL).getHost();
        String otherHost = "aibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibejsqd.onion";
        String otherUrl = "http://" + otherHost + "/";
        try (var store = new ArchiveStore(Files.createTempDirectory("archive-host-search"))) {
            String job = plan(store, 1);
            store.archive(job, URL, 0, URL, 200, "text/html", HTML, List.of());
            String other = (String) store.createPlan(List.of(otherUrl), 0, 1, "fixture").get("plan_id");
            store.approvePlan(other);
            store.archive(other, otherUrl, 0, otherUrl, 200, "text/html", HTML, List.of());
            var filtered = (List<?>) store.search("archive", null, host.toUpperCase(Locale.ROOT), 1).get("results");
            assertEquals(1, filtered.size());
            assertEquals(job, ((Map<?, ?>) filtered.getFirst()).get("job_id"));
            assertTrue(((List<?>) store.search("archive", job, otherHost, 1).get("results")).isEmpty());
            assertEquals(1, ((List<?>) store.search("archive", other, otherHost, 1).get("results")).size());
            assertTrue(((List<?>) store.search("%", null, host, 10).get("results")).isEmpty());
            assertThrows(IOException.class, () -> store.search("archive", null, "outside.example", 1));
        }
    }

    @Test public void restartAfterSqlFailureRetainsOrphanAndReplaysWithoutDuplicateSnapshot() throws Exception {
        Path root = Files.createTempDirectory("archive-partial-restart");
        String job;
        Path blob;
        try (var store = new ArchiveStore(root)) {
            job = plan(store, 1);
            try (var db = java.sql.DriverManager.getConnection("jdbc:h2:file:" + root.resolve("catalog") +
                    ";AUTO_SERVER=FALSE;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
                var sql = db.createStatement()) {
                sql.execute("ALTER TABLE snapshots ADD CONSTRAINT fixture_restart_reject CHECK(http_status<>200)");
            }
            assertThrows(IOException.class, () -> store.archive(job, URL, 0, URL, 200, "text/html", HTML, List.of()));
            assertEquals(0L, store.job(job).get("snapshots"));
            try (var files = Files.list(root.resolve("objects"))) {
                var retained = files.toList();
                assertEquals(1, retained.size());
                blob = retained.getFirst();
                assertArrayEquals(HTML, Files.readAllBytes(blob));
            }
        }
        try (var store = new ArchiveStore(root)) {
            assertEquals(0L, store.job(job).get("archived_pages"));
            try (var db = java.sql.DriverManager.getConnection("jdbc:h2:file:" + root.resolve("catalog") +
                    ";AUTO_SERVER=FALSE;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
                var sql = db.createStatement()) {
                sql.execute("ALTER TABLE snapshots DROP CONSTRAINT fixture_restart_reject");
            }
            var saved = store.archive(job, URL, 0, URL, 200, "text/html", HTML, List.of());
            assertEquals(blob.toString(), saved.get("storage_location"));
            assertEquals(1L, store.job(job).get("snapshots"));
        }
    }
}
