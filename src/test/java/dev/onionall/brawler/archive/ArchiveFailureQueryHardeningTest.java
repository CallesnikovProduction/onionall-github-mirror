package dev.onionall.brawler.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Проверяет failure bounds, projection ownership и хронологию legacy timestamps.</b>
 *
 * <p>SQL fixtures меняют только disposable catalogs; production schema и прочие
 * модули не изменяются. Observations остаются evidence, а не fetch counters.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveFailureQueryHardeningTest {
    private static final String URL =
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";
    private static final byte[] HTML = "<html><title>chronology</title>chronology</html>"
        .getBytes(StandardCharsets.UTF_8);

    private static Connection open(Path root) throws Exception {
        return DriverManager.getConnection("jdbc:h2:file:" + root.resolve("catalog") +
            ";AUTO_SERVER=FALSE;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
    }

    private static String approved(ArchiveStore store) throws IOException {
        String id = (String) store.createPlan(List.of(URL), 1, 3, "query-fixture").get("plan_id");
        store.approvePlan(id);
        return id;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result, String field) {
        return (List<Map<String, Object>>) result.get(field);
    }

    @Test public void explicitAggregateColumnsMatchExistingSchemaAndTolerateAdditiveColumns() throws Exception {
        Path root = Files.createTempDirectory("archive-failure-columns");
        try (var store = new ArchiveStore(root); var db = open(root); var sql = db.createStatement()) {
            String id = approved(store);
            sql.execute("ALTER TABLE failures ADD COLUMN fixture_extension VARCHAR(16) DEFAULT 'retained'");
            var first = store.recordFailure(id, URL, "FETCH_ERROR");
            var replay = store.recordFailure(id, URL, "FETCH_ERROR");
            assertEquals(first.get("failure_id"), replay.get("failure_id"));
            assertEquals(1L, store.job(id).get("failures"));
            assertEquals(2L, store.job(id).get("failure_observation_count"));
            try (var query = db.createStatement(); var result = query.executeQuery("SELECT occurred_at,fixture_extension FROM failures")) {
                assertTrue(result.next());
                assertNotNull(result.getString(1));
                assertEquals("retained", result.getString(2));
            }
        }
    }

    @Test public void observationSaturationRollsBackNewAggregateAndPreservesFrozenHistory() throws Exception {
        Path root = Files.createTempDirectory("archive-failure-saturated");
        try (var store = new ArchiveStore(root); var db = open(root)) {
            String id = approved(store);
            String failure = (String) store.recordFailure(id, URL, "FETCH_ERROR").get("failure_id");
            try (var insert = db.prepareStatement("INSERT INTO failure_observations_v1(job_id,failure_id,canonical_url,category,observed_at,phase,source,transport_category) VALUES(?,?,?,?,?,?,?,?)")) {
                for (int n = 1; n < 1000; n++) {
                    insert.setString(1, id); insert.setString(2, failure); insert.setString(3, URL);
                    insert.setString(4, "FETCH_ERROR"); insert.setString(5, "2026-11-14T00:00:00Z");
                    insert.setString(6, "UNKNOWN"); insert.setString(7, "UNKNOWN"); insert.setString(8, "UNKNOWN");
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            var rejected = assertThrows(IOException.class, () -> store.recordFailure(id, URL + "new", "NEW_FAILURE"));
            assertTrue(rejected.getMessage().contains("observation limit1000"));
            assertEquals(1L, store.job(id).get("failures"));
            assertEquals(1000L, store.job(id).get("failure_observation_count"));
            assertEquals(0L, store.job(id).get("snapshots"));
            store.archive(id, URL, 0, URL, 200, "text/html", HTML, List.of());
            assertEquals(1L, store.job(id).get("snapshots"));
            var first = store.failureHistory(id, null, 1);
            try (var insert = db.prepareStatement("INSERT INTO failures(id,job_id,canonical_url,category,occurred_at) VALUES(?,?,?,?,?)")) {
                insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, id);
                insert.setString(3, URL + "legacy"); insert.setString(4, "LEGACY_ONLY");
                insert.setString(5, "2026-11-14T00:00:00Z"); insert.executeUpdate();
            }
            var next = store.failureHistory(id, (String) first.get("next_cursor"), 100);
            assertEquals(first.get("through_sequence"), next.get("through_sequence"));
            assertEquals(1000L, next.get("observation_count"));
            assertEquals(1L, first.get("legacy_aggregate_count"));
            assertEquals(2L, next.get("legacy_aggregate_count"));
            assertEquals("THROUGH_SEQUENCE", next.get("observation_count_basis"));
            assertEquals("LIVE_AT_READ", next.get("legacy_aggregate_count_basis"));
            assertNull(next.get("fetch_attempt_count"));
        }
    }

    @Test public void aggregateSaturationRejectsNewIdentityButAllowsExistingIdentityObservation() throws Exception {
        Path root = Files.createTempDirectory("archive-aggregate-saturated");
        try (var store = new ArchiveStore(root); var db = open(root)) {
            String id = approved(store);
            String first = (String) store.recordFailure(id, URL, "FETCH_ERROR").get("failure_id");
            try (var insert = db.prepareStatement("INSERT INTO failures(id,job_id,canonical_url,category,occurred_at) VALUES(?,?,?,?,?)")) {
                for (int n = 1; n < 1000; n++) {
                    insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, id);
                    insert.setString(3, URL + "aggregate-" + n); insert.setString(4, "FETCH_ERROR");
                    insert.setString(5, "2026-11-14T00:00:00Z"); insert.addBatch();
                }
                insert.executeBatch();
            }
            IOException rejected = assertThrows(IOException.class, () -> store.recordFailure(id, URL + "overflow", "FETCH_ERROR"));
            assertTrue(rejected.getMessage().contains("record limit1000"));
            assertEquals(first, store.recordFailure(id, URL, "FETCH_ERROR").get("failure_id"));
            assertEquals(1000L, store.job(id).get("failures"));
            assertEquals(2L, store.job(id).get("failure_observation_count"));
        }
    }

    @Test public void variableFractionTimestampsSortChronologicallyInSearchAndFailures() throws Exception {
        Path root = Files.createTempDirectory("archive-query-clock");
        try (var store = new ArchiveStore(root); var db = open(root)) {
            String id = approved(store);
            var early = store.archive(id, URL, 0, URL, 200, "text/html", HTML, List.of());
            var late = store.archive(id, URL + "late", 1, URL, 200, "text/html", HTML, List.of());
            try (var update = db.prepareStatement("UPDATE snapshots SET recorded_at=? WHERE id=?")) {
                update.setString(1, "2026-11-14T00:00:00Z"); update.setString(2, (String) early.get("snapshot_id")); update.executeUpdate();
                update.setString(1, "2026-11-14T00:00:00.001Z"); update.setString(2, (String) late.get("snapshot_id")); update.executeUpdate();
            }
            assertEquals(late.get("page_id"), rows(store.search("chronology", id, 1), "results").getFirst().get("page_id"));
            // Exact nanoseconds must survive SQL conversion; ID tie-break must not hide rounding.
            Map<String, Object> lowerId = ((String) early.get("page_id")).compareTo((String) late.get("page_id")) < 0 ? early : late;
            Map<String, Object> higherId = lowerId == early ? late : early;
            try (var update = db.prepareStatement("UPDATE snapshots SET recorded_at=? WHERE id=?")) {
                update.setString(1, "2026-11-14T00:00:00Z"); update.setString(2, (String) lowerId.get("snapshot_id")); update.executeUpdate();
                update.setString(1, "2026-11-14T00:00:00.000000001Z"); update.setString(2, (String) higherId.get("snapshot_id")); update.executeUpdate();
            }
            assertEquals(higherId.get("page_id"), rows(store.search("chronology", id, 1), "results").getFirst().get("page_id"));
            var first = store.recordFailure(id, URL, "EARLY");
            var second = store.recordFailure(id, URL, "LATE");
            try (var update = db.prepareStatement("UPDATE failures SET occurred_at=? WHERE id=?")) {
                update.setString(1, "2026-11-14T00:00:00Z"); update.setString(2, (String) first.get("failure_id")); update.executeUpdate();
                update.setString(1, "2026-11-14T00:00:00.001Z"); update.setString(2, (String) second.get("failure_id")); update.executeUpdate();
            }
            assertEquals(second.get("failure_id"), rows(store.job(id), "failure_records").getFirst().get("failure_id"));
            assertEquals("2026-11-14T00:00:00Z", store.page((String) lowerId.get("page_id")).get("recorded_at"));
        }
    }

    @Test public void observationalExtensionsCannotShadowDerivedEvidenceOrAuthority() throws Exception {
        try (var store = new ArchiveStore(Files.createTempDirectory("archive-query-collision"))) {
            String id = approved(store);
            store.recordAdmission(id, Map.of("state", "WORKER_EXITED", "admission", "COMMITTED",
                "archived_pages", 999, "snapshots", 999, "failures", 999,
                "scheduling_owner", "archive", "archive_owner", "remote",
                "extension_hint", "retained"));
            var job = store.job(id);
            assertEquals(0L, job.get("archived_pages"));
            assertEquals(0L, job.get("snapshots"));
            assertEquals(0L, job.get("failures"));
            assertEquals("URLFrontier", job.get("scheduling_owner"));
            assertEquals("local catalog", job.get("archive_owner"));
            assertEquals("WORKER_EXITED", job.get("state"));
            assertEquals("COMMITTED", job.get("admission"));
            assertEquals("retained", job.get("extension_hint"));
            assertEquals(true, job.get("approved"));
        }
    }
}
