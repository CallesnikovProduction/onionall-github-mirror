package dev.onionall.brawler.research;

import dev.onionall.brawler.archive.ArchiveStore;
import dev.onionall.brawler.crawl.CrawlService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * Управляемое interleaving на unit Backend seam с настоящим H2/CAS.
 * Start/status observations здесь подставлены явно: тест не является crawl/RPC E2E.
 * Ни одной fake archived page или успешного Frontier reservation не создаётся.
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
public class ResearchWorkflowConcurrencyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String URL = "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/start";

    @FunctionalInterface private interface StartAction {
        Map<String, Object> start() throws Exception;
    }

    private static final class Backend implements ResearchWorkflow.Backend {
        private final Path root;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger staleSaves = new AtomicInteger();
        private final AtomicBoolean raceOnAttemptedSave = new AtomicBoolean();
        private String sessionId;
        private StartAction startAction = () -> Map.of("passed", true, "worker_exit_code", 0);

        private Backend(Path root) { this.root = root; }
        @Override public Path archiveRoot() { return root; }
        @Override public Map<String, Object> create(Map<String, ? extends Object> document) throws Exception {
            try (var store = new ArchiveStore(root)) { return store.createResearchSession(document); }
        }
        @Override public Map<String, Object> load(String id) throws Exception {
            try (var store = new ArchiveStore(root)) { return store.researchSession(id); }
        }
        @Override public Map<String, Object> save(String id, long revision,
                Map<String, ? extends Object> document) throws Exception {
            if (hasSeedState(document, "ATTEMPTED") && raceOnAttemptedSave.compareAndSet(true, false)) {
                observerUpdate("OBSERVER_BETWEEN_READ_AND_CAS");
            }
            try (var store = new ArchiveStore(root)) {
                try { return store.updateResearchSession(id, revision, document); }
                catch (IOException failure) {
                    if ("Research document changed; reload before retrying".equals(failure.getMessage())) staleSaves.incrementAndGet();
                    throw failure;
                }
            }
        }
        @Override public boolean exists(String id) throws Exception {
            try (var store = new ArchiveStore(root)) { return store.researchSessionExists(id); }
        }
        @Override public Map<String, Object> plan(String url, int depth, int pages, String provider) throws Exception {
            return new CrawlService(root).createPlan(List.of(url), depth, pages, provider);
        }
        @Override public Map<String, Object> approve(String id) throws Exception { return new CrawlService(root).approve(id); }
        @Override public Map<String, Object> start(String id, Path runtime, Path token) throws Exception {
            starts.incrementAndGet();
            return startAction.start();
        }
        @Override public Map<String, Object> status(String id, Path runtime, Path token) {
            return Map.of("authoritative_readback_current", false, "job_state", "UNKNOWN");
        }
        @Override public Map<String, Object> pages(String id) throws Exception {
            try (var store = new ArchiveStore(root)) { return store.pages(id, 100); }
        }
        @Override public Map<String, Object> page(String id) throws Exception {
            try (var store = new ArchiveStore(root)) { return store.page(id); }
        }
        @Override public Map<String, Object> links(String id) throws Exception {
            try (var store = new ArchiveStore(root)) { return store.links(id, 100); }
        }
        @Override public Map<String, Object> errors(String id) throws Exception {
            try (var store = new ArchiveStore(root)) { return store.failureHistory(id, null, 100); }
        }
        @Override public void print(Map<String, ? extends Object> value) { throw new AssertionError("Unit workflow does not print"); }

        private void observerUpdate(String marker) throws Exception {
            try (var store = new ArchiveStore(root)) {
                var fresh = store.researchSession(sessionId);
                var updated = new LinkedHashMap<>(fresh);
                updated.put("observer_cache", Map.of("marker", marker));
                updated.put("file_candidates", List.of());
                store.updateResearchSession(sessionId, ((Number) fresh.get("revision")).longValue(), updated);
            }
        }
    }

    private record Fixture(Backend backend, ResearchWorkflow workflow, String session, Path runtime, Path token) { }

    private Fixture ready() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Backend backend = new Backend(directory.resolve("archive"));
        ResearchWorkflow workflow = new ResearchWorkflow(backend,
            (runtime, query, limit) -> { throw new AssertionError("Unit test must not contact provider"); });
        String id = (String) workflow.importSeeds(List.of(URL), "unit scope").get("session_id");
        backend.sessionId = id;
        workflow.select(id, List.of(), true, List.of());
        workflow.plan(id, 2, 5, 5);
        workflow.approve(id);
        Path runtime = directory.resolve("runtime.json");
        Files.writeString(runtime, "{\"unit_fixture\":true}");
        Path token = directory.resolve("token");
        Files.writeString(token, "unit-only-unused-token");
        return new Fixture(backend, workflow, id, runtime, token);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstSeed(Map<String, ?> document) {
        return ((List<Map<String, Object>>) document.get("seeds")).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static boolean hasSeedState(Map<String, ?> document, String state) {
        return ((List<Map<String, Object>>) document.get("seeds")).stream()
            .anyMatch(seed -> state.equals(seed.get("execution_state")));
    }

    @Test public void observerRevisionChangeDuringStartDoesNotLoseSuccessfulChildObservation() throws Exception {
        Fixture fixture = ready();
        fixture.backend.startAction = () -> {
            assertEquals("RUNNING", firstSeed(fixture.backend.load(fixture.session)).get("execution_state"));
            fixture.backend.observerUpdate("OBSERVER_DURING_START");
            return Map.of("passed", true, "worker_exit_code", 0);
        };
        Map<String, Object> result = fixture.workflow.run(fixture.session, fixture.runtime, fixture.token,
            600, 10, 1000, false);
        var persisted = fixture.backend.load(fixture.session);
        assertEquals(Map.of("marker", "OBSERVER_DURING_START"), persisted.get("observer_cache"));
        assertEquals("ATTEMPTED", firstSeed(persisted).get("execution_state"));
        assertEquals(0, ((Number) firstSeed(persisted).get("last_worker_exit_code")).intValue());
        assertFalse(firstSeed(persisted).containsKey("error_category"));
        assertEquals(1, fixture.backend.starts.get());
        assertEquals("UNKNOWN", result.get("session_status"));
        assertTrue(((List<?>) result.get("pages")).isEmpty());
        assertTrue(((List<?>) result.get("links")).isEmpty());
        @SuppressWarnings("unchecked") var metrics = (Map<String, Object>) result.get("metrics");
        assertNull(metrics.get("completed"));
        assertNull(metrics.get("seeds_succeeded"));
    }

    @Test public void compareAndSetConflictReloadsFreshObserverFieldsBeforeRetryingPatch() throws Exception {
        Fixture fixture = ready();
        fixture.backend.raceOnAttemptedSave.set(true);
        fixture.workflow.run(fixture.session, fixture.runtime, fixture.token, 600, 10, 1000, false);
        var persisted = fixture.backend.load(fixture.session);
        assertEquals(1, fixture.backend.staleSaves.get());
        assertEquals(1, fixture.backend.starts.get());
        assertEquals("ATTEMPTED", firstSeed(persisted).get("execution_state"));
        assertEquals(Map.of("marker", "OBSERVER_BETWEEN_READ_AND_CAS"), persisted.get("observer_cache"));
    }

    @Test public void startIOExceptionIsExecutionFailureWithoutInventingTerminalSuccess() throws Exception {
        Fixture fixture = ready();
        fixture.backend.startAction = () -> { throw new IOException("unit worker startup failure"); };
        Map<String, Object> result = fixture.workflow.run(fixture.session, fixture.runtime, fixture.token,
            600, 10, 1000, false);
        var persisted = fixture.backend.load(fixture.session);
        assertEquals("EXECUTION_FAILED", firstSeed(persisted).get("execution_state"));
        assertEquals("IOException", firstSeed(persisted).get("error_category"));
        assertEquals("EXECUTION_FAILED", result.get("session_status"));
        assertEquals(1, fixture.backend.starts.get());
        @SuppressWarnings("unchecked") var metrics = (Map<String, Object>) result.get("metrics");
        assertEquals(1, ((Number) metrics.get("seeds_execution_failed")).intValue());
        assertNull(metrics.get("completed"));
        assertNull(metrics.get("seeds_failed"));
        assertNull(metrics.get("seeds_succeeded"));
        assertTrue(((List<?>) result.get("pages")).isEmpty());
    }

    @Test public void insufficientTotalBudgetRejectsBeforeStartingOrWritingRuntimeState() throws Exception {
        Fixture fixture = ready();
        Map<String, Object> before = fixture.backend.load(fixture.session);
        var rejected = assertThrows(IllegalArgumentException.class, () -> fixture.workflow.run(
            fixture.session, fixture.runtime.resolveSibling("missing-runtime"), fixture.token,
            30, 45, 1000, false));
        assertTrue(rejected.getMessage().contains("budget"));
        assertEquals(0, fixture.backend.starts.get());
        assertEquals(before, fixture.backend.load(fixture.session));
        assertFalse(Files.exists(fixture.backend.root.resolve("research-run.lock")));
        assertFalse(Files.exists(fixture.backend.root.resolve("research-runs")));
    }

    @Test public void symlinkResearchRunsParentRejectsWithoutWritingIntoItsTarget() throws Exception {
        Fixture fixture = ready();
        Path outside = temporary.newFolder().toPath();
        Path link = fixture.backend.root.resolve("research-runs");
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | IOException unavailable) { Assume.assumeNoException("Symlink creation unavailable", unavailable); }
        Map<String, Object> before = fixture.backend.load(fixture.session);
        assertThrows(IllegalArgumentException.class, () -> fixture.workflow.run(
            fixture.session, fixture.runtime, fixture.token, 600, 10, 1000, false));
        assertEquals(0, fixture.backend.starts.get());
        assertEquals(before, fixture.backend.load(fixture.session));
        try (var entries = Files.list(outside)) { assertEquals(0, entries.count()); }
        assertTrue(Files.isSymbolicLink(link));
    }
}
