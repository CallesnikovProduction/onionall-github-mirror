package dev.onionall.brawler.archive.internal;

import dev.onionall.brawler.archive.ArchiveStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Проверяет lifecycle, provenance и ownership research/observations.</b>
 *
 * <p>Использует disposable H2 и временные каталоги без внешних сетевых обращений.
 * Capacity fixture — sparse файл рядом с отдельной in-memory БД, а не порча H2.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveOwnershipHardeningTest {
    private static final String URL = "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";

    @Test public void readCallbackCannotCloseJdbcBeforeReturning() throws Exception {
        try (var context = new ArchiveContext(Files.createTempDirectory("archive-read-close"))) {
            context.serializableRead(() -> {
                assertThrows(IOException.class, context::close);
                return context.serializableRead(() -> {
                    assertThrows(IOException.class, context::close);
                    try (var statement = context.connection().createStatement();
                        var rows = statement.executeQuery("SELECT 1")) {
                        assertTrue(rows.next());
                    }
                    return null;
                });
            });
        }
    }

    @Test public void readDepthUnwindsAfterUncheckedFailure() throws Exception {
        var context = new ArchiveContext(Files.createTempDirectory("archive-read-depth"));
        var failure = new IllegalStateException("controlled callback failure");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> context.serializableRead(() -> context.serializableRead(() -> { throw failure; }))));
        context.close();
        assertThrows(IOException.class, () -> context.serializableRead(() -> null));
    }

    @Test public void statusOnlyRowsDoNotClaimFrontierAdmissionProvenance() throws Exception {
        Path root = Files.createTempDirectory("archive-status-provenance");
        String id;
        try (var store = new ArchiveStore(root)) {
            id = plan(store);
            var status = status(id, "RUNNING");
            store.recordJobStatus(id, status);
            var observation = store.admissionObservation(id);
            assertEquals(status, observation.get("job_status"));
            assertFalse(observation.containsKey("admission_authority"));
            assertFalse(observation.containsKey("admission_observed_at"));
        }
        try (var store = new ArchiveStore(root)) {
            assertFalse(store.admissionObservation(id).containsKey("admission_authority"));
            store.recordAdmission(id, Map.of());
            var admitted = store.admissionObservation(id);
            assertEquals("URLFrontier", admitted.get("admission_authority"));
            Object clock = admitted.get("admission_observed_at");
            Instant.parse((String) clock);
            store.recordJobStatus(id, status(id, "PAUSED"));
            assertEquals(clock, store.admissionObservation(id).get("admission_observed_at"));
            assertTrue(store.admissionObservation(id).keySet().stream().noneMatch(key -> key.startsWith("_archive_")));
        }
    }

    @Test public void admissionCannotInjectManagedAuditOrProvenanceFields() throws Exception {
        try (var store = new ArchiveStore(Files.createTempDirectory("archive-managed-audit"))) {
            String id = plan(store);
            for (String key : List.of("job_status", "status_transitions", "status_transitions_dropped",
                "admission_observed_at", "admission_authority", "_archive_admission_observed_at_v1")) {
                assertThrows(key, IOException.class,
                    () -> store.recordAdmission(id, Map.of(key, "injected")));
            }
            assertEquals(Map.of(), store.admissionObservation(id));
            var original = status(id, "RUNNING");
            store.recordJobStatus(id, original);
            var saved = store.admissionObservation(id);
            assertThrows(IOException.class, () -> store.recordAdmission(id, Map.of("job_status", Map.of())));
            assertEquals(saved, store.admissionObservation(id));
        }
    }

    @Test public void admissionReadModifyWriteCanEchoCurrentAuditWithoutOwningItsFields() throws Exception {
        try (var store = new ArchiveStore(Files.createTempDirectory("archive-managed-echo"))) {
            String id = plan(store);
            store.recordAdmission(id, Map.of("session_id", id, "admission", "COMMITTED", "state", "ADMITTED"));
            store.recordJobStatus(id, status(id, "RUNNING"));
            var before = store.admissionObservation(id);
            var echo = new LinkedHashMap<>(before);
            echo.put("state", "WORKER_EXITED");
            store.recordAdmission(id, echo);
            var saved = store.admissionObservation(id);
            assertEquals("WORKER_EXITED", saved.get("state"));
            for (String field : List.of("job_status", "status_transitions", "status_transitions_dropped")) {
                assertEquals(before.get(field), saved.get(field));
            }
            assertEquals("URLFrontier", saved.get("admission_authority"));
            Instant.parse((String) saved.get("admission_observed_at"));

            Map<String, Object> conflicts = Map.of(
                "job_status", Map.of("job_state", "FORGED"),
                "status_transitions", List.of(Map.of("job_state", "FORGED")),
                "status_transitions_dropped", 77,
                "admission_observed_at", "2000-01-01T00:00:00Z",
                "admission_authority", "FORGED",
                "_archive_admission_observed_at_v1", saved.get("admission_observed_at"));
            for (var conflict : conflicts.entrySet()) {
                var changed = new LinkedHashMap<>(saved);
                changed.put(conflict.getKey(), conflict.getValue());
                assertThrows(conflict.getKey(), IOException.class, () -> store.recordAdmission(id, changed));
                assertEquals(saved, store.admissionObservation(id));
            }
            var stale = new LinkedHashMap<>(saved);
            store.recordJobStatus(id, status(id, "PAUSED"));
            var current = store.admissionObservation(id);
            assertThrows(IOException.class, () -> store.recordAdmission(id, stale));
            assertEquals(current, store.admissionObservation(id));
        }
    }

    @Test public void observationWritesApplyCapacityBeforeChangingStoredState() throws Exception {
        Path root = Files.createTempDirectory("archive-observation-capacity");
        try (var db = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID());
            var context = new ArchiveContext(root, db)) {
            ArchiveSchema.migrate(db);
            var plans = new PlanRepository(context);
            var observations = new ObservationRepository(context, plans);
            String id = context.serializableRead(() -> (String) plans.createPlan(List.of(URL), 1, 2, "fixture").get("plan_id"));
            context.serializableRead(() -> plans.approvePlan(id));
            context.serializableRead(() -> { observations.recordAdmission(id, Map.of("admission", "COMMITTED")); return null; });
            var saved = context.serializableRead(() -> observations.admissionObservation(id));
            try (var sparse = FileChannel.open(root.resolve("catalog.mv.db"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                sparse.position(128L * 1024 * 1024);
                sparse.write(ByteBuffer.wrap(new byte[] {0}));
            }
            assertThrows(IOException.class, () -> context.serializableRead(() -> {
                observations.recordAdmission(id, Map.of("admission", "REJECTED"));
                return null;
            }));
            assertThrows(IOException.class, () -> context.serializableRead(() -> {
                observations.recordJobStatus(id, status(id, "RUNNING"));
                return null;
            }));
            assertEquals(saved, context.serializableRead(() -> observations.admissionObservation(id)));
            try (var sparse = FileChannel.open(root.resolve("catalog.mv.db"), StandardOpenOption.WRITE)) {
                sparse.truncate(128L * 1024 * 1024);
            }
            context.serializableRead(() -> { observations.recordJobStatus(id, status(id, "RUNNING")); return null; });
            assertEquals("RUNNING", ((Map<?, ?>) context.serializableRead(() -> observations.admissionObservation(id))
                .get("job_status")).get("job_state"));
        }
    }

    @Test public void legacyAdmissionRowsKeepClockButLegacyStatusOnlyRowsDoNotAcquireOne() throws Exception {
        try (var context = new ArchiveContext(Files.createTempDirectory("archive-legacy-provenance"))) {
            var plans = new PlanRepository(context);
            var observations = new ObservationRepository(context, plans);
            String id = context.serializableRead(() -> (String) plans.createPlan(List.of(URL), 1, 2, "fixture").get("plan_id"));
            String clock = "2026-11-14T00:00:00Z";
            insertObservation(context, id, "{\"admission\":\"COMMITTED\",\"state\":\"ADMITTED\"}", clock);
            var legacy = context.serializableRead(() -> observations.admissionObservation(id));
            assertEquals(clock, legacy.get("admission_observed_at"));
            assertEquals("URLFrontier", legacy.get("admission_authority"));
            context.serializableRead(() -> { observations.recordJobStatus(id, status(id, "RUNNING")); return null; });
            assertEquals(clock, context.serializableRead(() -> observations.admissionObservation(id)).get("admission_observed_at"));

            insertObservation(context, id, "{\"job_status\":{\"job_state\":\"UNKNOWN\"},\"status_transitions\":[],\"status_transitions_dropped\":0}", clock);
            var statusOnly = context.serializableRead(() -> observations.admissionObservation(id));
            assertFalse(statusOnly.containsKey("admission_authority"));
            assertFalse(statusOnly.containsKey("admission_observed_at"));
            insertObservation(context, id, "{}", clock);
            assertEquals(Map.of(), context.serializableRead(() -> observations.admissionObservation(id)));
        }
    }

    @Test public void malformedPrivateProvenanceMarkerFailsWithoutLeakingIt() throws Exception {
        try (var context = new ArchiveContext(Files.createTempDirectory("archive-invalid-provenance"))) {
            var plans = new PlanRepository(context);
            var observations = new ObservationRepository(context, plans);
            String id = context.serializableRead(() -> (String) plans.createPlan(List.of(URL), 1, 2, "fixture").get("plan_id"));
            insertObservation(context, id, "{\"_archive_admission_observed_at_v1\":false}", "2026-11-14T00:00:00Z");
            assertThrows(IOException.class, () -> context.serializableRead(() -> observations.admissionObservation(id)));
        }
    }

    @Test public void directResearchBridgeReadsUseContextOwnershipAndRespectClose() throws Exception {
        var context = new ArchiveContext(Files.createTempDirectory("archive-research-reads"));
        var sessions = new ResearchSessions(context);
        String id = UUID.randomUUID().toString();
        try {
            var created = sessions.createResearchSession(document(id));
            assertTrue(sessions.researchSessionExists(id));
            assertEquals(created, sessions.researchSession(id));
        } finally {
            context.close();
        }
        assertThrows(IOException.class, () -> sessions.researchSession(id));
        assertThrows(IOException.class, () -> sessions.researchSessionExists(id));
    }

    @Test public void researchSavepointDoesNotCommitCallerTransactionAndCasRemainsUsable() throws Exception {
        Path root = Files.createTempDirectory("archive-research-savepoint");
        String discarded = UUID.randomUUID().toString();
        String id = UUID.randomUUID().toString();
        try (var context = new ArchiveContext(root)) {
            var sessions = new ResearchSessions(context);
            var failure = new IOException("controlled outer rollback");
            assertSame(failure, assertThrows(IOException.class, () -> context.transaction(() -> {
                context.research().create(document(discarded));
                assertFalse(context.connection().getAutoCommit());
                throw failure;
            })));
            assertFalse(sessions.researchSessionExists(discarded));
            var first = sessions.createResearchSession(document(id));
            assertThrows(IOException.class, () -> context.transaction(() -> {
                context.research().update(id, 0, first);
                throw failure;
            }));
            assertEquals(first, sessions.researchSession(id));
            var second = sessions.updateResearchSession(id, 0, first);
            assertEquals(1L, second.get("revision"));
            assertThrows(IOException.class, () -> sessions.updateResearchSession(id, 0, first));
            assertEquals(second, sessions.researchSession(id));
            assertEquals(2L, sessions.updateResearchSession(id, 1, second).get("revision"));
            assertTrue(context.serializableRead(() -> context.connection().getAutoCommit()));
        }
        try (var context = new ArchiveContext(root)) {
            var sessions = new ResearchSessions(context);
            assertFalse(sessions.researchSessionExists(discarded));
            assertEquals(2L, sessions.researchSession(id).get("revision"));
        }
    }

    @Test public void consistentReadComposesWithoutNestedTransactionOrEarlyCommit() throws Exception {
        try (var context = new ArchiveContext(Files.createTempDirectory("archive-consistent-read"))) {
            context.consistentRead(() -> {
                assertFalse(context.connection().getAutoCommit());
                context.consistentRead(() -> {
                    assertFalse(context.connection().getAutoCommit());
                    assertThrows(IOException.class, () -> context.transaction(() -> null));
                    assertThrows(IOException.class, context::close);
                    return null;
                });
                return null;
            });
            assertTrue(context.serializableRead(() -> context.connection().getAutoCommit()));
            assertThrows(IOException.class, () -> context.consistentRead(() -> { throw new IOException("read failure"); }));
            assertTrue(context.serializableRead(() -> context.connection().getAutoCommit()));
        }
    }

    @Test public void mutationGuardRequiresOwnedActiveContextTransaction() throws Exception {
        var context = new ArchiveContext(Files.createTempDirectory("archive-required-transaction"));
        try {
            assertThrows(IllegalStateException.class, context::requireTransaction);
            context.serializableRead(() -> {
                assertThrows(IOException.class, context::requireTransaction);
                return null;
            });
            context.transaction(() -> {
                context.requireTransaction();
                return null;
            });
        } finally {
            context.close();
        }
        synchronized (context) {
            assertThrows(IOException.class, context::requireTransaction);
        }
    }

    private static void insertObservation(ArchiveContext context, String id, String json, String clock) throws IOException {
        context.transaction(() -> {
            try (var statement = context.connection().prepareStatement(
                "MERGE INTO admission_observations(job_id,observation_json,observed_at) KEY(job_id) VALUES(?,?,?)")) {
                statement.setString(1, id);
                statement.setString(2, json);
                statement.setString(3, clock);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static String plan(ArchiveStore store) throws IOException {
        String id = (String) store.createPlan(List.of(URL), 1, 2, "fixture").get("plan_id");
        store.approvePlan(id);
        return id;
    }

    private static Map<String, Object> status(String id, String state) {
        return Map.of("plan_id", id, "job_status_version", 1, "job_state", state,
            "completion", "INCOMPLETE", "state_basis", "LOCAL_OBSERVATION", "observed_at", Instant.now().toString());
    }

    private static Map<String, Object> document(String id) {
        var value = new LinkedHashMap<String, Object>();
        value.put("session_id", id);
        value.put("schema_version", 1);
        value.put("query", "fixture");
        return value;
    }
}
