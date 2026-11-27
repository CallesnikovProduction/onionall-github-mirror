package dev.onionall.brawler.research;

import dev.onionall.brawler.archive.ArchiveStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class StoredResearchRouteTest {
    private static final String ORIGIN =
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";
    private static final String OBSERVED = "2026-11-27T08:00:00Z";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void shortestRouteUsesDeterministicTiesAndPreservesOriginalEdges() throws Exception {
        try (var archive = archive()) {
            String job = approve(archive);
            String start = crawl(archive, job, "", link("b", "RESERVED"), link("a", "RESERVED"));
            String left = crawl(archive, job, "a", link("", "ALREADY_RESERVED"), link("target", "RESERVED"));
            crawl(archive, job, "b", link("target", "ALREADY_RESERVED"));
            String target = crawl(archive, job, "target");
            var graph = archive.links(job, 100);
            var storedEdges = links(graph);
            var expected = List.of(edge(storedEdges, start, left), edge(storedEdges, left, target));
            var reversed = new ArrayList<>(storedEdges);
            Collections.reverse(reversed);
            var reversedGraph = Map.<String, Object>of("job_id", job, "limit", 100, "links", reversed);

            var result = StoredResearchRoute.find(job, start, target, 5, archive.pages(job, 100), reversedGraph);
            assertEquals("FOUND", result.get("status"));
            assertEquals(2, result.get("hop_count"));
            assertEquals(List.of(start, left, target), result.get("page_ids"));
            assertEquals(expected, result.get("path"));
            assertEquals("CRAWLER", result.get("provenance"));
            assertEquals(false, result.get("traversal_verified"));
            assertEquals(0, result.get("network_requests"));
        }
    }

    @Test public void rejectedAndUnresolvedLinksCannotShortenOrCreateARoute() throws Exception {
        try (var archive = archive()) {
            String job = approve(archive);
            String another = approve(archive);
            crawl(archive, another, "outside-job");
            String start = crawl(archive, job, "", link("target", "DEPTH_REJECTED"),
                link("outside-job", "ALREADY_RESERVED"), link("metadata", "METADATA_ONLY"));
            String target = crawl(archive, job, "target");
            crawl(archive, job, "metadata");

            var graph = archive.links(job, 100);
            assertTrue(links(graph).stream().anyMatch(edge -> (ORIGIN + "outside-job").equals(edge.get("target_url"))
                && edge.get("target_page_id") == null));
            var result = StoredResearchRoute.find(job, start, target, 5, archive.pages(job, 100), graph);
            assertEquals("NO_ROUTE_IN_STORED_GRAPH", result.get("status"));
            assertNull(result.get("hop_count"));
            assertEquals(List.of(), result.get("path"));
            assertEquals("ENDPOINT_NOT_OBSERVED", StoredResearchRoute.find(job, start, "absent", 5,
                archive.pages(job, 100), graph).get("status"));
        }
    }

    @Test public void importedAndHistoricalSnapshotsDoNotBecomeFetchEvidence() throws Exception {
        try (var archive = archive()) {
            String job = approve(archive);
            var observation = archive.importHtml(job, ORIGIN, html("manual"), List.of(link("topic", "MANUAL_UNSCHEDULED")));
            String start = (String) observation.get("page_id");
            String target = (String) archive.importHtml(job, ORIGIN + "topic", html("topic"), List.of()).get("page_id");
            var manual = find(archive, job, start, target, 5, 100, 100);
            assertEquals("FOUND", manual.get("status"));
            assertEquals("MANUAL_IMPORT", manual.get("provenance"));
            assertFalse((Boolean) manual.get("traversal_verified"));

            assertEquals(start, crawl(archive, job, ""));
            var historical = find(archive, job, start, target, 5, 100, 100);
            assertEquals("FOUND", historical.get("status"));
            assertEquals(manual.get("path"), historical.get("path"));
            assertEquals("MIXED_OR_HISTORICAL", historical.get("provenance"));
            assertFalse((Boolean) historical.get("traversal_verified"));
        }
    }

    @Test public void prefersTheObservationFromTheSourceSnapshotInThePageView() throws Exception {
        try (var archive = archive()) {
            String job = approve(archive);
            String target = crawl(archive, job, "topic");
            var manual = archive.importHtml(job, ORIGIN, html("manual topic link"),
                List.of(link("topic", "MANUAL_UNSCHEDULED")));
            var earlierPages = archive.pages(job, 100);
            String start = crawl(archive, job, "", link("topic", "ALREADY_RESERVED"));
            var currentPages = archive.pages(job, 100);
            var graph = archive.links(job, 100);
            var current = archive.page(start);
            var before = StoredResearchRoute.find(job, start, target, 1, earlierPages, graph);
            var after = StoredResearchRoute.find(job, start, target, 1, currentPages, graph);

            // Both historical observations are present in the same graph. Change only the page view.
            assertEquals(manual.get("snapshot_id"), links(Map.of("links", before.get("path"))).getFirst().get("snapshot_id"));
            assertEquals(current.get("snapshot_id"), links(Map.of("links", after.get("path"))).getFirst().get("snapshot_id"));
            assertEquals("CRAWLER", after.get("provenance"));
        }
    }

    @Test public void hopAndCatalogBoundsNeverClaimAnUnprovenNegative() throws Exception {
        try (var archive = archive()) {
            String job = approve(archive);
            String start = crawl(archive, job, "", link("a", "RESERVED"));
            crawl(archive, job, "a", link("target", "RESERVED"));
            String target = crawl(archive, job, "target");
            var hopLimited = find(archive, job, start, target, 1, 100, 100);
            assertEquals("LIMITED", hopLimited.get("status"));
            assertNull(hopLimited.get("hop_count"));
            assertEquals(true, ((Map<?, ?>) hopLimited.get("limits")).get("hop_limit_reached"));

            var edgeCapped = find(archive, job, start, target, 5, 100, 2);
            assertEquals("LIMITED", edgeCapped.get("status"));
            assertEquals(2, edgeCapped.get("hop_count"));
            assertEquals("SHORTEST_IN_LOADED_GRAPH", edgeCapped.get("path_optimality"));
            assertEquals(true, ((Map<?, ?>) edgeCapped.get("limits")).get("edge_cap_reached"));

            var pageCapped = find(archive, job, start, target, 5, 1, 100);
            assertEquals("LIMITED", pageCapped.get("status"));
            assertNull(pageCapped.get("hop_count"));
            assertEquals(true, ((Map<?, ?>) pageCapped.get("limits")).get("page_cap_reached"));
            assertThrows(IllegalArgumentException.class, () -> find(archive, job, start, target, 101, 100, 100));
        }
    }

    private ArchiveStore archive() throws Exception {
        return new ArchiveStore(temporary.newFolder().toPath());
    }

    private static String approve(ArchiveStore archive) throws Exception {
        String job = (String) archive.createPlan(List.of(ORIGIN), 3, 20, "fixture").get("plan_id");
        archive.approvePlan(job);
        return job;
    }

    private static Map<String, Object> link(String path, String disposition) {
        return Map.of("target_url", ORIGIN + path, "disposition", disposition, "discovered_at", OBSERVED);
    }

    @SafeVarargs
    private static String crawl(ArchiveStore archive, String job, String path, Map<String, Object>... links) throws Exception {
        return (String) archive.archive(job, ORIGIN + path, path.isEmpty() ? 0 : 1, ORIGIN,
            200, "text/html", html("crawler " + path), List.of(links)).get("page_id");
    }

    private static byte[] html(String body) {
        return ("<html><body>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> links(Map<String, Object> graph) {
        return (List<Map<String, Object>>) graph.get("links");
    }

    private static Map<String, Object> edge(List<Map<String, Object>> edges, String from, String to) {
        return edges.stream()
            .filter(row -> from.equals(row.get("source_page_id")) && to.equals(row.get("target_page_id")))
            .findFirst().orElseThrow();
    }

    private static Map<String, Object> find(
        ArchiveStore archive, String job, String from, String to, int hops, int pages, int links
    ) throws Exception {
        return StoredResearchRoute.find(job, from, to, hops, archive.pages(job, pages), archive.links(job, links));
    }
}
