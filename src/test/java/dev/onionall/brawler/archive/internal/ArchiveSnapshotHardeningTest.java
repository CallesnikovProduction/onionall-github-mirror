package dev.onionall.brawler.archive.internal;

import dev.onionall.brawler.archive.ArchiveStore;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Проверки immutable snapshot identity, transaction ownership и хронологии H2.</b>
 *
 * <p>Настоящий H2 проверяет committed effects. Proxy запускает rival writer точно
 * после первого commit и до readback, без sleeps и вероятностных interleavings.
 * SQL fixtures задают противонаправленные UUID/time порядки для проверки nanoseconds.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveSnapshotHardeningTest {
    private static final String URL =
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";

    private static byte[] html(String body) {
        return ("<html><body>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> value, String field) {
        return (List<Map<String, Object>>) value.get(field);
    }

    private static String approve(ArchiveContext context, PlanRepository plans, int pages) throws Exception {
        String job = context.serializableRead(() ->
            (String) plans.createPlan(List.of(URL), 2, pages, "archive-regression").get("plan_id"));
        context.serializableRead(() -> plans.approvePlan(job));
        return job;
    }

    @Test public void commitInterleavingReturnsOwnSnapshotWhileLatestBelongsToRival() throws Exception {
        var root = Files.createTempDirectory("snapshot-identity-readback");
        Files.createDirectories(root.resolve("objects"));
        try (Connection real = DriverManager.getConnection("jdbc:h2:file:" + root.resolve("catalog") +
            ";AUTO_SERVER=FALSE;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "")) {
            ArchiveSchema.migrate(real);
            try (var rival = new ArchiveStore(root)) {
                var interleave = new AtomicBoolean();
                var jobIdentity = new AtomicReference<String>();
                var rivalWrite = new AtomicReference<Map<String, Object>>();
                Connection proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (ignored, method, arguments) -> {
                        try {
                            Object result = method.invoke(real, arguments);
                            if (method.getName().equals("commit") && interleave.compareAndSet(true, false)) {
                                rivalWrite.set(rival.archive(jobIdentity.get(), URL, 0, URL, 200,
                                    "text/html", html("rival version"), List.of()));
                            }
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
                try (var context = new ArchiveContext(root, proxy)) {
                    var plans = new PlanRepository(context);
                    var graph = new GraphRepository(context, plans);
                    var snapshots = new SnapshotRepository(context, plans, graph);
                    String job = approve(context, plans, 1);
                    jobIdentity.set(job);
                    interleave.set(true);
                    var own = context.serializableRead(() -> snapshots.archive(job, URL, 0, URL, 200,
                        "text/html", html("original version"), List.of()));
                    assertNotNull("Rival write must occur at the controlled commit boundary", rivalWrite.get());
                    assertEquals(BlobStore.hash(html("original version")), own.get("content_hash"));
                    assertNotEquals(own.get("snapshot_id"), rivalWrite.get().get("snapshot_id"));
                    var latest = context.serializableRead(() -> snapshots.page((String) own.get("page_id")));
                    assertEquals(rivalWrite.get().get("snapshot_id"), latest.get("snapshot_id"));
                    assertEquals(BlobStore.hash(html("rival version")), latest.get("content_hash"));
                    assertEquals(2, rows(own, "history").size());
                    assertEquals(own.get("page_id"), latest.get("page_id"));
                }
            }
        }
    }

    @Test public void graphMutationRequiresActiveTransactionEvenWithMonitorOwnership() throws Exception {
        var root = Files.createTempDirectory("graph-transaction-contract");
        try (var context = new ArchiveContext(root)) {
            var plans = new PlanRepository(context);
            var graph = new GraphRepository(context, plans);
            var snapshots = new SnapshotRepository(context, plans, graph);
            String job = approve(context, plans, 1);
            var page = context.serializableRead(() -> snapshots.archive(job, URL, 0, URL, 200,
                "text/html", html("graph ownership"), List.of()));
            String snapshot = (String) page.get("snapshot_id");
            List<Map<String, String>> edges = List.of(Map.of("source_url", URL,
                "target_url", URL + "target", "disposition", "OBSERVED",
                "discovered_at", "2026-11-14T10:00:00Z", "discovery_time_basis", "DISCOVERY_REPORTED"));
            assertThrows(IllegalStateException.class, () -> graph.recordFirstLinks(snapshot, edges));
            IOException failure = assertThrows(IOException.class, () -> context.serializableRead(() -> {
                graph.recordFirstLinks(snapshot, edges);
                return null;
            }));
            assertTrue(failure.getMessage().contains("transaction"));
            assertTrue(rows(context.serializableRead(() -> graph.links(job, 100)), "links").isEmpty());
            context.transaction(() -> {
                graph.recordFirstLinks(snapshot, edges);
                return null;
            });
            assertEquals(1, rows(context.serializableRead(() -> graph.links(job, 100)), "links").size());
        }
    }

    @Test public void replayOfOldContentBecomesLatestWithoutRewritingFirstProvenanceOrEdges() throws Exception {
        try (var store = new ArchiveStore(Files.createTempDirectory("snapshot-reappearing-content"))) {
            String job = (String) store.createPlan(List.of(URL), 2, 1, "archive-regression").get("plan_id");
            store.approvePlan(job);
            var first = store.archive(job, URL, 1, URL + "first-referrer", 201, "text/html", html("A"),
                List.of(Map.of("target_url", URL + "first-edge", "disposition", "FIRST",
                    "discovered_at", "2026-11-14T10:00:00Z")));
            var second = store.archive(job, URL, 0, URL + "second-referrer", 200, "text/html", html("B"),
                List.of(Map.of("target_url", URL + "second-edge", "disposition", "SECOND")));
            var replay = store.archive(job, URL, 2, URL + "replay-referrer", 202, "text/html", html("A"),
                List.of(Map.of("target_url", URL + "discarded-replay-edge", "disposition", "REPLAY")));
            assertNotEquals(first.get("snapshot_id"), second.get("snapshot_id"));
            for (String field : List.of("snapshot_id", "content_hash", "fetched_at", "recorded_at",
                "http_status", "depth", "source_url")) {
                assertEquals("Replay must retain first snapshot " + field, first.get(field), replay.get(field));
            }
            assertFalse(Instant.parse((String) replay.get("last_seen"))
                .isBefore(Instant.parse((String) first.get("last_seen"))));
            assertEquals(first.get("snapshot_id"), store.page((String) first.get("page_id")).get("snapshot_id"));
            assertEquals(2, rows(replay, "history").size());
            assertEquals(1, rows(replay, "links").size());
            assertEquals(URL + "first-edge", rows(replay, "links").getFirst().get("target_url"));
            assertEquals(2, rows(store.links(job, 100), "links").size());
            assertEquals(2L, store.job(job).get("snapshots"));
        }
    }

    @Test public void pagesHistoryAndGraphUseInstantOrderIncludingNanosecondsAndOffsets() throws Exception {
        var clocks = List.of("2026-11-14T10:00:00Z", "2026-11-14T10:00:00.000000001Z",
            "2026-11-14T10:00:00.000000002Z", "2026-11-14T10:00:00.1Z",
            "2026-11-14T13:00:00.100000001+03:00");
        try (var context = new ArchiveContext(Files.createTempDirectory("archive-instant-order"))) {
            var plans = new PlanRepository(context);
            var graph = new GraphRepository(context, plans);
            var snapshots = new SnapshotRepository(context, plans, graph);
            String pagesJob = approve(context, plans, clocks.size());
            var pages = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < clocks.size(); i++) {
                String url = URL + "page-" + i;
                pages.add(context.serializableRead(() -> snapshots.archive(pagesJob, url, 0, URL,
                    200, "text/html", html("page"), List.of())));
            }
            pages.sort(Comparator.comparing((Map<String, Object> row) -> (String) row.get("page_id"), Comparator.reverseOrder()));
            String historyJob = approve(context, plans, 1);
            var versions = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < clocks.size(); i++) {
                byte[] content = html("version-" + i);
                versions.add(context.serializableRead(() -> snapshots.archive(historyJob, URL, 0, URL,
                    200, "text/html", content, List.of(Map.of("target_url", URL + "target",
                        "disposition", "OBSERVED")))));
            }
            versions.sort(Comparator.comparing((Map<String, Object> row) -> (String) row.get("snapshot_id"), Comparator.reverseOrder()));
            context.transaction(() -> {
                try (var snapshotClock = context.connection().prepareStatement("UPDATE snapshots SET recorded_at=? WHERE id=?");
                     var graphClock = context.connection().prepareStatement("UPDATE page_links SET discovered_at=? WHERE snapshot_id=?")) {
                    for (int i = 0; i < clocks.size(); i++) {
                        for (var row : List.of(pages.get(i), versions.get(i))) {
                            snapshotClock.setString(1, clocks.get(i));
                            snapshotClock.setString(2, (String) row.get("snapshot_id"));
                            assertEquals(1, snapshotClock.executeUpdate());
                        }
                        graphClock.setString(1, clocks.get(i));
                        graphClock.setString(2, (String) versions.get(i).get("snapshot_id"));
                        assertEquals(1, graphClock.executeUpdate());
                    }
                }
                return null;
            });
            var listed = rows(context.serializableRead(() -> snapshots.pages(pagesJob, 100)), "pages");
            assertEquals(pages.stream().map(row -> row.get("page_id")).toList(),
                listed.stream().map(row -> row.get("page_id")).toList());
            String page = (String) versions.getFirst().get("page_id");
            var history = rows(context.serializableRead(() -> snapshots.page(page)), "history");
            assertEquals(versions.stream().map(row -> row.get("snapshot_id")).toList(),
                history.stream().map(row -> row.get("snapshot_id")).toList());
            var edges = rows(context.serializableRead(() -> graph.links(historyJob, 100)), "links");
            assertEquals(versions.stream().map(row -> row.get("snapshot_id")).toList(),
                edges.stream().map(row -> row.get("snapshot_id")).toList());
            assertEquals(clocks, edges.stream().map(row -> row.get("discovered_at")).toList());
        }
    }
}
