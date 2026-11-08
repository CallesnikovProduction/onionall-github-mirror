package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.archive.ArchiveStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CrawlExecutionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Map<String, Object> plan(Path archive) throws IOException {
        try (var store = new ArchiveStore(archive)) {
            var input = CrawlAdmissionServiceTest.plan();
            var plan = store.createPlan((List<String>) input.get("seeds"), 2, 3, "review-fixture");
            return store.approvePlan((String) plan.get("plan_id"));
        }
    }

    private Path runtime(Path root, Map<String, Object> endpoint) throws IOException {
        Path jar = root.resolve("fixture.jar");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest)) { }
        var runtime = new LinkedHashMap<>(endpoint);
        // java rejects Storm arguments immediately; no crawler code or external network can run.
        runtime.put("storm_executable", Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        runtime.put("crawler_jar", jar.toString());
        runtime.put("work_directory", root.resolve("work").toString());
        runtime.put("socks_host", "127.0.0.1");
        runtime.put("socks_port", 19050);
        Path file = root.resolve("runtime.json");
        JSON.writeValue(file.toFile(), runtime);
        return file;
    }

    @Test public void planSnapshotCannotChangeStoredApprovalOrSpecAndIdsCannotEscapeWorkRoot() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = root.resolve("archive");
        var approved = plan(archive);
        String id = (String) approved.get("plan_id");
        approved.put("depth", 5);
        ((List<?>) approved.get("seeds")).clear();
        try (var store = new ArchiveStore(archive)) {
            var unchanged = store.plan(id);
            assertEquals(2, unchanged.get("depth"));
            assertEquals(1, ((List<?>) unchanged.get("seeds")).size());
            assertEquals(true, unchanged.get("approved"));
            for (String key : List.of("approved", "origin", "seeds", "max_pages", "depth")) {
                assertThrows(IOException.class, () -> store.recordAdmission(id, Map.of(key, "changed")));
            }
            assertEquals(unchanged, store.approvePlan(id));
        }
        for (String invalid : List.of("../escape", root.resolve("escape").toString(), "..", "x/y", "x\\y")) {
            assertThrows(IOException.class, () -> CrawlExecution.start(archive, invalid, null, null));
        }
        assertFalse(Files.exists(root.resolve("escape")));
        assertFalse(Files.exists(archive.resolve("crawl-start.lock")));
    }

    public static final class StartupOwner {
        public static void main(String[] args) throws Exception {
            try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                Files.writeString(Path.of(args[1]), "ready");
                Thread.sleep(30000);
            }
        }
    }

    @Test public void concurrentStartsWithinJvmAndAcrossProcessesStopBeforePreparationOrRpc() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = root.resolve("archive");
        String id = (String) plan(archive).get("plan_id");
        Path runtime = runtime(root, Map.of()), token = root.resolve("token");
        Files.writeString(token, "a".repeat(32));
        Path lock = archive.resolve("crawl-start.lock");
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class,
                () -> CrawlExecution.start(archive, id, runtime, token));
        }
        Path ready = root.resolve("ready");
        Process owner = new ProcessBuilder(CrawlWorkerTest.java(root, StartupOwner.class.getName(),
            lock.toString(), ready.toString())).redirectErrorStream(true)
            .redirectOutput(root.resolve("owner.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(ready) && owner.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(ready));
            IOException error = assertThrows(IOException.class, () -> CrawlExecution.start(archive, id, runtime, token));
            assertTrue(error.getMessage().contains("active local crawl start"));
            assertFalse(Files.exists(root.resolve("work")));
            assertFalse(Files.exists(archive.resolve("crawl-worker.lock")));
        } finally {
            owner.destroy();
            if (!owner.waitFor(5, TimeUnit.SECONDS)) {
                owner.destroyForcibly();
                assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void invalidFinalReadbackCannotReplaceVerifiedAdmissionAndStatusCanRecover() throws Exception {
        for (String invalid : List.of("spec", "outcome", "null")) {
            verifyRecoveryAfterInvalidReadback(invalid);
        }
    }

    private void verifyRecoveryAfterInvalidReadback(String invalid) throws Exception {
        Path root = temporary.newFolder().toPath(), archive = root.resolve("archive");
        var plan = plan(archive);
        String id = (String) plan.get("plan_id"), url = (String) ((List<?>) plan.get("seeds")).getFirst();
        int[] sessions = {0};
        boolean[] recover = {false};
        try (var fixture = new CrawlAdmissionServiceTest.Fixture(request -> {
            String action = (String) request.get("action");
            if (action.equals("reserve") || action.equals("reservation")) {
                return CrawlAdmissionServiceTest.reservation(id, url);
            }
            if (action.equals("counts")) {
                var counts = new LinkedHashMap<String, Object>();
                for (String key : List.of("reserved", "pending", "held", "scheduled_wait", "unscheduled", "unknown")) {
                    counts.put(key, Set.of("reserved", "pending").contains(key) ? 1 : 0);
                }
                for (String key : List.of("done", "success", "terminal", "retry_wait")) counts.put(key, null);
                return Map.of("outcome", "STATE_COUNTS", "state_version", 1, "session_id", id,
                    "readback_at", "2026-11-06T00:00:00Z", "next_attempt_at", "2026-11-06T00:00:00Z", "counts", counts);
            }
            var reply = new LinkedHashMap<>(CrawlAdmissionServiceTest.session(plan));
            if (action.equals("session") && ++sessions[0] > 1 && !recover[0]) {
                if (invalid.equals("null")) return null;
                if (invalid.equals("outcome")) {
                    reply.put("outcome", "SESSION_NOT_FOUND");
                    return reply;
                }
                var wrong = new LinkedHashMap<>((Map<String, Object>) reply.get("spec"));
                wrong.put("session_id", "other-session");
                reply.put("spec", wrong);
            }
            return reply;
        })) {
            Path runtime = runtime(root, fixture.runtime()), token = root.resolve("token");
            Files.writeString(token, "a".repeat(32));
            // Reconciliation fails after worker exit; persisted execution must remain an honest base observation.
            assertThrows(IOException.class, () -> CrawlExecution.start(archive, id, runtime, token));
            Path execution = root.resolve("work").resolve(id).resolve("execution.json");
            Map<?, ?> saved = JSON.readValue(execution.toFile(), Map.class);
            assertEquals(CrawlAdmissionServiceTest.session(plan), saved.get("authoritative_admission"));
            assertEquals("AUTHORITATIVE_READBACK_UNAVAILABLE", saved.get("admission_readback_error"));
            assertFalse(Boolean.TRUE.equals(saved.get("crawl_completed_verified")));
            try (var store = new ArchiveStore(archive)) {
                assertEquals(saved.get("authoritative_admission"), store.admissionObservation(id).get("authoritative_admission"));
                assertNull(store.admissionObservation(id).get("job_status"));
            }
            recover[0] = true;
            var status = CrawlExecution.status(archive, id, runtime, token);
            assertEquals(true, status.get("authoritative_readback_current"));
            assertEquals("INCOMPLETE", status.get("completion"));
            try (var store = new ArchiveStore(archive)) {
                assertEquals(JSON.readValue(JSON.writeValueAsBytes(status.get("job_status")), Map.class),
                    store.admissionObservation(id).get("job_status"));
            }
        }
    }
}
