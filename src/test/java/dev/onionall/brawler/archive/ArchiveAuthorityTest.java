package dev.onionall.brawler.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * <b>Проверяет границу между specification плана и наблюдениями.</b>
 *
 * <p>Fixture SQL моделирует legacy observation в disposable H2; данные настоящего
 * архива и JDBC ownership production-компонентов не изменяются.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveAuthorityTest {
    private static final String URL =
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";

    private static String approved(ArchiveStore store) throws IOException {
        String id = (String) store.createPlan(List.of(URL), 1, 3, "authority-fixture").get("plan_id");
        store.approvePlan(id);
        return id;
    }

    private static void observation(Path root, String id, String json) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:file:" + root.resolve("catalog") +
            ";AUTO_SERVER=FALSE;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
            var insert = db.prepareStatement(
                "MERGE INTO admission_observations(job_id,observation_json,observed_at) KEY(job_id) VALUES(?,?,?)")) {
            insert.setString(1, id);
            insert.setString(2, json);
            insert.setString(3, "2026-11-14T00:00:00Z");
            insert.executeUpdate();
        }
    }

    @Test public void immutableSpecificationCannotBeShadowedByNewObservations() throws Exception {
        try (var store = new ArchiveStore(Files.createTempDirectory("archive-authority"))) {
            String id = approved(store);
            Map<String, Object> plan = store.plan(id);
            for (String key : List.of("plan_id", "job_id", "seeds", "origin", "scope", "depth",
                "max_pages", "provider", "approved", "created_at")) {
                assertThrows(key, IOException.class, () -> store.recordAdmission(id, Map.of(key, "conflict")));
                assertEquals(key, plan.get(key), store.job(id).get(key));
            }
        }
    }

    @Test public void matchingPlanIdentityAndLegitimateObservationsRetainTheirContracts() throws Exception {
        try (var store = new ArchiveStore(Files.createTempDirectory("archive-observation-contract"))) {
            String id = approved(store);
            store.recordAdmission(id, Map.of("plan_id", id, "session_id", id,
                "state", "WORKER_EXITED", "admission", "COMMITTED", "worker_exit_code", 0));
            var status = Map.<String, Object>of("plan_id", id, "job_status_version", 1,
                "job_state", "PAUSED", "completion", "INCOMPLETE", "state_basis", "FRONTIER_OBSERVATION",
                "observed_at", "2026-11-14T00:00:00Z");
            store.recordJobStatus(id, status);
            Map<String, Object> job = store.job(id);
            assertEquals(id, job.get("plan_id"));
            assertEquals(true, job.get("approved"));
            assertEquals("WORKER_EXITED", job.get("state"));
            assertEquals("COMMITTED", job.get("admission"));
            assertEquals(0, job.get("worker_exit_code"));
            assertEquals(status, job.get("job_status"));
            assertEquals(1, ((List<?>) job.get("status_transitions")).size());
            assertEquals("APPROVED", store.plan(id).get("state"));
        }
    }

    @Test public void legacyObservationCannotOverwriteAuthoritativeProjection() throws Exception {
        Path root = Files.createTempDirectory("archive-legacy-authority");
        String id;
        Map<String, Object> plan;
        try (var store = new ArchiveStore(root)) {
            id = approved(store);
            plan = store.plan(id);
        }
        observation(root, id, "{\"plan_id\":\"conflict\",\"job_id\":\"conflict\",\"seeds\":[]," +
            "\"origin\":\"https://outside.example\",\"scope\":\"global\",\"depth\":5,\"max_pages\":100," +
            "\"provider\":\"conflict\",\"approved\":false,\"created_at\":\"conflict\"," +
            "\"state\":\"WORKER_EXITED\",\"admission\":\"COMMITTED\"}");
        try (var store = new ArchiveStore(root)) {
            Map<String, Object> job = store.job(id);
            for (String key : List.of("plan_id", "job_id", "seeds", "origin", "scope", "depth",
                "max_pages", "provider", "approved", "created_at")) {
                assertEquals(key, plan.get(key), job.get(key));
            }
            assertEquals("WORKER_EXITED", job.get("state"));
            assertEquals("COMMITTED", job.get("admission"));
        }
    }

    @Test public void malformedStoredObservationIsAnIoFailure() throws Exception {
        for (String json : List.of("null", "[]", "\"scalar\"", "{")) {
            Path root = Files.createTempDirectory("archive-malformed-observation");
            String id;
            try (var store = new ArchiveStore(root)) {
                id = approved(store);
            }
            observation(root, id, json);
            try (var store = new ArchiveStore(root)) {
                assertThrows(json, IOException.class, () -> store.admissionObservation(id));
                assertThrows(json, IOException.class, () -> store.job(id));
                assertThrows(json, IOException.class, store::hasExecutionHistory);
            }
        }
    }

    @Test public void malformedMapInputsFailWithoutMutationAndAllowSubsequentWrite() throws Exception {
        try (var store = new ArchiveStore(Files.createTempDirectory("archive-invalid-observation-input"))) {
            String id = approved(store);
            assertThrows(IOException.class, () -> store.recordAdmission(id, null));
            assertThrows(IOException.class, () -> store.recordJobStatus(id, null));
            Map<String, Object> nullKey = new LinkedHashMap<>();
            nullKey.put(null, "invalid");
            assertThrows(IOException.class, () -> store.recordAdmission(id, nullKey));
            assertThrows(IOException.class, () -> store.recordJobStatus(id, Map.of("plan_id", id,
                "job_status_version", "1")));
            assertTrue(store.admissionObservation(id).isEmpty());
            store.recordAdmission(id, Map.of("state", "ADMITTED", "admission", "COMMITTED"));
            assertEquals("ADMITTED", store.job(id).get("state"));
        }
    }
}
