package dev.onionall.brawler.research;

import dev.onionall.brawler.archive.ArchiveStore;
import dev.onionall.brawler.tools.OnionSmoke;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * Aggregation над настоящими H2/HTML MANUAL_IMPORT snapshots; HTTP fetch не симулируется.
 * Проверяет source identity, сохранение разных сообщений и явную неполноту extraction.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
public class ResearchThreadsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String URL = "http://" + OnionSmoke.HOST + "/topic/42";

    private class Fixture implements AutoCloseable {
        final Path root;
        final ArchiveStore store;
        final String job;
        final String sessionId = UUID.randomUUID().toString();

        Fixture() throws Exception {
            root = temporary.newFolder().toPath().resolve("archive");
            store = new ArchiveStore(root);
            job = (String) store.createPlan(List.of(URL), 2, 20, "manual-thread-test").get("plan_id");
            store.approvePlan(job);
        }

        void add(String url, String html) throws IOException {
            var imported = store.importHtml(job, url, html.getBytes(StandardCharsets.UTF_8), List.of());
            assertEquals("MANUAL_IMPORT", imported.get("source_kind"));
            assertNull(imported.get("fetched_at"));
            assertEquals(0, ((Number) imported.get("http_status")).intValue());
        }

        @SuppressWarnings("unchecked") List<Map<String, Object>> rows() throws IOException {
            return (List<Map<String, Object>>) store.pages(job, 100).get("pages");
        }

        List<Map<String, Object>> details() throws IOException {
            return rows().stream().map(row -> {
                try { return store.page((String) row.get("page_id")); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            }).toList();
        }

        List<Map<String, Object>> threads() throws IOException {
            return ResearchThreads.INSTANCE.extract(session(), details(), root);
        }

        Map<String, Object> session() {
            return Map.of("session_id", sessionId, "mode", "THREAD", "thread_seed_url", URL);
        }

        @Override public void close() throws IOException { store.close(); }
    }

    @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private String post(String id, String body) {
        return "<div class='post' id='" + id + "'><div class='postbody'><p class='author'>" +
            "<a class='username'>Alice</a><time datetime='2026-11-13T10:00:00Z'>Today</time>" +
            "</p><div class='content'>" + body + "</div></div></div>";
    }

    private String html(String body) { return "<html><head><title>Controlled discussion</title></head><body>" + body + "</body></html>"; }

    @Test public void uniqueObservedIdDeduplicatesIdenticalPaginatedMessagesWithBothSources() throws Exception {
        try (var fixture = new Fixture()) {
            String message = post("p101", "Actual repeated pagination message.");
            fixture.add(URL, html(message));
            fixture.add(URL + "?page=2", html(message));
            var thread = fixture.threads().getFirst();
            var posts = rows(thread.get("posts"));
            assertEquals(1, posts.size());
            assertEquals("p101", posts.getFirst().get("source_post_id"));
            assertEquals("INTERNAL_OBSERVATION_ID", posts.getFirst().get("post_id_basis"));
            var provenance = rows(posts.getFirst().get("provenance"));
            assertEquals(2, provenance.size());
            assertEquals(2, provenance.stream().map(p -> p.get("source_page_id")).distinct().count());
            for (var source : provenance) {
                assertEquals("MANUAL_IMPORT", source.get("source_kind"));
                assertNull(source.get("fetched_at"));
                assertNotNull(source.get("recorded_at"));
                assertEquals(fixture.job, source.get("job_id"));
            }
        }
    }

    @Test public void duplicateDomIdsOnSamePageAreSeparateEvenWhenTheirVisibleFieldsMatch() throws Exception {
        try (var fixture = new Fixture()) {
            String message = post("p777", "Same visible message with ambiguous DOM IDs.");
            fixture.add(URL, html(message + message));
            var thread = fixture.threads().getFirst();
            var posts = rows(thread.get("posts"));
            assertEquals(2, posts.size());
            assertEquals("PARTIAL", thread.get("extraction_status"));
            assertNotEquals(posts.get(0).get("post_id"), posts.get(1).get("post_id"));
            for (var post : posts) {
                assertEquals("p777", post.get("source_post_id"));
                assertEquals(false, ((Map<?, ?>) post.get("evidence")).get("source_post_id_unique_on_page"));
            }
        }
    }

    @Test public void sameIdAndTextWithChangedLinkRetainsBothObservedAttachmentVariants() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.add(URL, html(post("p101", "Read the <a href='/first.pdf' type='application/pdf'>manual</a>.")));
            fixture.add(URL + "?page=2", html(post("p101", "Read the <a href='/second.pdf' type='application/pdf'>manual</a>.")));
            var thread = fixture.threads().getFirst();
            var posts = rows(thread.get("posts"));
            assertEquals(2, posts.size());
            assertEquals("PARTIAL", thread.get("extraction_status"));
            assertEquals(posts.get(0).get("text"), posts.get(1).get("text"));
            assertNotEquals(rows(posts.get(0).get("attachments")).getFirst().get("target_url"),
                rows(posts.get(1).get("attachments")).getFirst().get("target_url"));
            assertTrue(((List<?>) thread.get("conflicting_source_post_ids")).contains("p101"));
        }
    }

    @Test public void unsupportedDynamicPageCannotHideBehindStructuredPageSuccess() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.add(URL, html(post("p101", "One real static message.")));
            fixture.add(URL + "?page=2", html("<div id='app'>Loading discussions...</div><script src='/app.js'></script>"));
            var thread = fixture.threads().getFirst();
            assertEquals("PARTIAL", thread.get("extraction_status"));
            assertEquals(1, rows(thread.get("posts")).size());
            var pages = rows(thread.get("captured_pages"));
            assertEquals(2, pages.size());
            assertEquals(List.of("STRUCTURED_POSTS", "UNSUPPORTED_DYNAMIC_CONTENT"),
                pages.stream().map(page -> page.get("extraction_status")).toList());
            assertEquals(false, thread.get("full_thread_coverage_verified"));
        }
    }

    @Test public void ordinaryResearchRetainsUnstructuredCapturesOfAnIdentifiedDiscussion() throws Exception {
        for (String secondBody : List.of(
                "<div id='app'>Loading discussions...</div><script src='/app.js'></script>",
                "<main>Readable discussion continuation without identifiable post containers.</main>")) {
            try (var fixture = new Fixture()) {
                fixture.add(URL, html(post("p101", "One static discussion message.") +
                    "<a rel='next' href='?page=2'>Next discussion page</a>"));
                fixture.add(URL + "?page=2", html(secondBody));
                fixture.add(URL.substring(0, URL.lastIndexOf('/') + 1) + "category",
                    html("<main>Unrelated category page must not become a discussion.</main>"));
                var ordinarySession = Map.<String, Object>of("session_id", fixture.sessionId, "provider", "manual");
                var threads = ResearchThreads.INSTANCE.extract(ordinarySession, fixture.details(), fixture.root);
                assertEquals(1, threads.size());
                var thread = threads.getFirst();
                assertEquals("PARTIAL", thread.get("extraction_status"));
                assertEquals("PARTIAL_OR_UNKNOWN", thread.get("completeness_status"));
                assertEquals(false, thread.get("full_thread_coverage_verified"));
                assertEquals(1, rows(thread.get("posts")).size());
                var pages = rows(thread.get("captured_pages"));
                assertEquals(List.of(URL, URL + "?page=2"), pages.stream().map(page -> page.get("url")).toList());
                assertEquals("STRUCTURED_POSTS", pages.getFirst().get("extraction_status"));
                assertEquals(secondBody.contains("id='app'") ? "UNSUPPORTED_DYNAMIC_CONTENT" : "PAGE_TEXT_ONLY",
                    pages.get(1).get("extraction_status"));
                var pagination = rows(thread.get("observed_pagination"));
                assertEquals(1, pagination.size());
                assertEquals(URL + "?page=2", pagination.getFirst().get("target_url"));
                assertEquals(true, pagination.getFirst().get("captured"));
                for (var page : pages) {
                    assertEquals("MANUAL_IMPORT", page.get("source_kind"));
                    assertNull(page.get("fetched_at"));
                }
            }
        }
    }

    @Test public void mixedPaginationUnitsRetainCaptureAndDomOrderWithoutClaimingVerifiedOrdering() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.add(URL, html(post("p100", "First captured page message.") +
                "<a href='?start=20'>Offset continuation</a><a href='?page=3'>Numbered continuation</a>"));
            fixture.add(URL + "?start=20", html(post("p200", "Offset capture precedes the numbered capture.") +
                "<a href='?page=3'>Next</a>"));
            fixture.add(URL + "?page=3", html(post("p300", "Numbered capture must not sort ahead by incomparable units.") +
                "<a href='?start=20'>Previous</a>"));
            var thread = fixture.threads().getFirst();
            assertEquals("AMBIGUOUS_PAGINATION_COUNTERS", thread.get("ordering_status"));
            assertEquals("CAPTURE_ORDER_AND_DOM_ORDER", thread.get("post_order_basis"));
            assertEquals("PARTIAL", thread.get("extraction_status"));
            assertEquals("PARTIAL_OR_UNKNOWN", thread.get("completeness_status"));
            assertEquals(false, thread.get("full_thread_coverage_verified"));
            assertEquals(List.of(URL, URL + "?start=20", URL + "?page=3"),
                rows(thread.get("captured_pages")).stream().map(page -> page.get("url")).toList());
            assertEquals(List.of("p100", "p200", "p300"),
                rows(thread.get("posts")).stream().map(post -> post.get("source_post_id")).toList());
            assertTrue(rows(thread.get("observed_pagination")).stream().allMatch(edge -> Boolean.TRUE.equals(edge.get("captured"))));
        }
    }

    @Test public void changedExternalReferenceCannotCollapseToTheSameNullOnionCanonicalValue() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.add(URL, html(post("p101", "Read the <a href='https://example.org/first#section'>reference</a>.")));
            fixture.add(URL + "?page=2", html(post("p101", "Read the <a href='https://example.org/second#section'>reference</a>.")));
            var thread = fixture.threads().getFirst();
            var posts = rows(thread.get("posts"));
            assertEquals(2, posts.size());
            assertEquals("PARTIAL", thread.get("extraction_status"));
            assertNotEquals(rows(posts.get(0).get("referenced_links")).getFirst().get("target_url"),
                rows(posts.get(1).get("referenced_links")).getFirst().get("target_url"));
        }
    }

    @Test public void explicitThreadNeverAbsorbsAnotherTopicFromTheSameOrigin() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.add(URL, html(post("p101", "Selected discussion message.")));
            fixture.add(URL.substring(0, URL.lastIndexOf('/') + 1) + "999", html(post("p999", "Unrelated discussion must remain separate.")));
            var threads = fixture.threads();
            assertEquals(1, threads.size());
            var thread = threads.getFirst();
            assertEquals(1, rows(thread.get("captured_pages")).size());
            assertEquals(1, rows(thread.get("posts")).size());
            assertEquals("p101", rows(thread.get("posts")).getFirst().get("source_post_id"));
            assertFalse(thread.toString().contains("Unrelated discussion"));
        }
    }

    @Test public void fileAssociationUsesCanonicalDefaultPortAndHostnameIdentity() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.add(URL, html(post("p101", "Read <a href='http://" + OnionSmoke.HOST +
                ":80/manual.pdf?edition=1&amp;lang=en'>manual</a> and <a href='http://" +
                OnionSmoke.HOST.toUpperCase(Locale.ROOT) + ":80/uppercase.pdf'>second manual</a>.")));
            var files = ResearchFiles.extract(fixture.sessionId, fixture.rows(), id -> {
                try { return fixture.store.page(id); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            }, fixture.root);
            assertEquals(2, files.size());
            var linked = ResearchThreads.INSTANCE.attachPostSources(files, fixture.threads());
            for (var file : linked) {
                assertEquals("p101", file.get("source_post_id"));
                assertEquals(1, ((List<?>) file.get("source_post_observation_ids")).size());
                assertFalse(file.get("target_url").toString().contains(":80/"));
                assertEquals("MANUAL_IMPORT", file.get("source_kind"));
                assertNull(file.get("content_type"));
                assertNull(file.get("content_length"));
            }
        }
    }
}
