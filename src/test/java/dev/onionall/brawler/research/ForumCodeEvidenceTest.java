package dev.onionall.brawler.research;

import dev.onionall.brawler.archive.ArchiveStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/**
 * Verifies F06 using persisted manual HTML snapshots and normal thread readback.
 * Synthetic URLs are used only as provenance.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
public class ForumCodeEvidenceTest {
    private static final String URL =
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/topic/42";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 10_000)
    public void preservesLiteralMainAndQuotedCodeInTheirOwnEvidence() throws Exception {
        String main = "for item in items:\r\n    prepare(item)\r\n\r\n\r\n    run(item)  \r\n";
        String quoted = "def other():\n\treturn 3\n\n";
        var fixture = persist("Public reply.<pre><code>" + main + "</code></pre>"
            + "<blockquote><cite>A prior post</cite><pre><code>" + quoted + "</code></pre></blockquote>"
            + "<div hidden><pre><code>This is not public.</code></pre></div><p>Use <code>x</code>.</p>");
        var post = posts(fixture.thread()).getFirst();
        assertEquals("p42", post.get("source_post_id"));
        var code = rows(post.get("code_blocks"), "Structured code missing; normalized body: " + post.get("text"));
        assertEquals(1, code.size());
        assertEquals(main, code.getFirst().get("text"));
        assertEquals(false, code.getFirst().get("text_truncated"));
        assertEquals(1, code.getFirst().get("ordinal"));
        var quotes = rows(post.get("quotes"), "No quote evidence");
        assertEquals(1, quotes.size());
        var quotedCode = rows(quotes.getFirst().get("code_blocks"), "Quoted code missing");
        assertEquals(1, quotedCode.size());
        assertEquals(quoted, quotedCode.getFirst().get("text"));
        assertEquals(false, quotedCode.getFirst().get("text_truncated"));
        assertEquals(2, quotedCode.getFirst().get("ordinal"));
        assertFalse(post.get("text").toString().contains("def other"));
        assertFalse(post.get("text").toString().contains("This is not public."));
        assertEquals(fixture.pages().getFirst().get("snapshot_id"), post.get("snapshot_id"));
        var provenance = rows(post.get("provenance"), "No source provenance").getFirst();
        assertEquals(URL, provenance.get("source_page_url"));
        assertEquals(fixture.pages().getFirst().get("page_id"), provenance.get("source_page_id"));
        assertEquals(fixture.pages().getFirst().get("job_id"), provenance.get("job_id"));
        assertEquals(fixture.pages().getFirst().get("snapshot_id"), provenance.get("snapshot_id"));
        assertEquals("MANUAL_IMPORT", provenance.get("source_kind"));
        assertNull(provenance.get("fetched_at"));
    }

    @Test(timeout = 10_000)
    public void doesNotMergeSourcePostIdsWithDistinctLiteralCode() throws Exception {
        var fixture = persist("<pre><code>first\n\nsecond</code></pre>", "<pre><code>first\n\n\nsecond</code></pre>");
        var posts = posts(fixture.thread());
        assertEquals(2, posts.size());
        assertEquals(posts.get(0).get("text"), posts.get(1).get("text"));
        assertEquals(List.of("p42"), fixture.thread().get("conflicting_source_post_ids"));
        assertEquals(List.of("first\n\nsecond", "first\n\n\nsecond"),
            posts.stream().map(post -> rows(post.get("code_blocks"), "Missing code").getFirst().get("text")).toList());
        assertNotEquals(posts.get(0).get("snapshot_id"), posts.get(1).get("snapshot_id"));
    }

    @Test(timeout = 10_000)
    public void reportsCodeBudgetCutWithoutSplittingAUnicodePair() throws Exception {
        String prefix = "x".repeat(ForumExtractor.MAX_POST_TEXT - 1);
        var fixture = persist("<pre><code>" + prefix + "&#x1F9C5;</code></pre>");
        var post = posts(fixture.thread()).getFirst();
        var code = rows(post.get("code_blocks"), "Missing code").getFirst();
        assertEquals(prefix, code.get("text"));
        assertEquals(true, code.get("text_truncated"));
        assertEquals(true, ((Map<?, ?>) post.get("evidence")).get("code_blocks_truncated"));
        assertEquals("PARTIAL", fixture.thread().get("extraction_status"));
    }

    @Test(timeout = 10_000)
    public void sharesCodeCountBudgetAcrossPostAndQuote() throws Exception {
        var fixture = persist("<pre><code>main</code></pre>".repeat(31)
            + "<blockquote><pre><code>quoted</code></pre><pre><code>over budget</code></pre></blockquote>");
        var post = posts(fixture.thread()).getFirst();
        assertEquals(31, rows(post.get("code_blocks"), "Missing code").size());
        var quote = rows(post.get("quotes"), "Missing quote").getFirst();
        var quoteBlocks = rows(quote.get("code_blocks"), "Missing quoted code");
        assertEquals(1, quoteBlocks.size());
        assertEquals("quoted", quoteBlocks.getFirst().get("text"));
        assertEquals(32, quoteBlocks.getFirst().get("ordinal"));
        assertEquals(true, ((Map<?, ?>) post.get("evidence")).get("code_blocks_truncated"));
        assertEquals("PARTIAL", fixture.thread().get("extraction_status"));
    }

    private Fixture persist(String... bodies) throws Exception {
        Path root = temporary.newFolder().toPath().resolve("archive");
        String id = UUID.randomUUID().toString();
        var pageIds = new ArrayList<String>();
        try (var archive = new ArchiveStore(root)) {
            String job = (String) archive.createPlan(List.of(URL), 2, 5, "public discussion fixture").get("plan_id");
            archive.approvePlan(job);
            for (int i = 0; i < bodies.length; i++) {
                String url = i == 0 ? URL : URL + "?page=" + (i + 1);
                String html = "<html><title>Public coding thread</title><body>"
                    + "<div class='post' id='p42'><div class='postbody'><div class='content'>"
                    + bodies[i] + "</div></div></div></body></html>";
                var captured = archive.importHtml(job, url, html.getBytes(StandardCharsets.UTF_8), List.of());
                assertEquals("MANUAL_IMPORT", captured.get("source_kind"));
                pageIds.add((String) captured.get("page_id"));
            }
        }
        try (var archive = new ArchiveStore(root)) {
            var pages = new ArrayList<Map<String, Object>>();
            for (String pageId : pageIds) {
                pages.add(archive.page(pageId));
            }
            var session = Map.<String, Object>of("session_id", id, "mode", "THREAD", "thread_seed_url", URL);
            var threads = ResearchThreads.INSTANCE.extract(session, pages, root);
            assertEquals(1, threads.size());
            return new Fixture(threads.getFirst(), pages);
        }
    }

    private static List<Map<String, Object>> posts(Map<String, Object> thread) {
        return rows(thread.get("posts"), "No posts");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value, String message) {
        assertTrue(message, value instanceof List<?>);
        return (List<Map<String, Object>>) value;
    }

    private record Fixture(Map<String, Object> thread, List<Map<String, Object>> pages) {}
}
