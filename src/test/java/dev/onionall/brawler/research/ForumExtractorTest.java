package dev.onionall.brawler.research;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Контролируемые DOM fixtures доказывают конкретные контракты extraction, а не поддержку live engines.
 * Source/time/author/post IDs не выдумываются; raw HTML остаётся обязанностью существующего архива.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
public class ForumExtractorTest {
    private static final String URL = "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/topic/42";

    private Map<String, Object> fixture(String name) throws Exception {
        try (InputStream source = getClass().getResourceAsStream("/forum/" + name)) {
            assertNotNull("Missing fixture " + name, source);
            return ForumExtractor.extract(URL, source.readAllBytes());
        }
    }

    private Map<String, Object> parse(String html) {
        return ForumExtractor.extract(URL, html.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> posts(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("posts");
    }

    @Test public void traditionalMessagesKeepAuthorsDatesBodyAndObservedPdfSeparateFromNavigation() throws Exception {
        var page = fixture("traditional-page-1.html");
        assertEquals("STRUCTURED_POSTS", page.get("extraction_status"));
        assertEquals("CONTROLLED_POST_BODY_V1", page.get("extraction_method"));
        assertEquals("Reliable backups & recovery — page 1", page.get("title"));
        var posts = posts(page);
        assertEquals(2, posts.size());
        assertEquals("p101", posts.get(0).get("source_post_id"));
        assertEquals("Alice", posts.get(0).get("author"));
        assertEquals("2026-11-13T10:00:00Z", posts.get(0).get("posted_at"));
        assertEquals(1, posts.get(0).get("ordinal"));
        assertTrue(((String) posts.get(0).get("text")).contains("copies.\nVerify A & B"));
        var bob = posts.get(1);
        assertEquals("Bob", bob.get("author"));
        assertEquals("2026-11-13T10:05:00+03:00", bob.get("posted_at"));
        assertEquals(URL, bob.get("source_page_url"));
        assertFalse(((String) bob.get("text")).contains("Alice wrote:"));
        assertTrue(bob.get("quoted_text").toString().contains("Alice wrote:"));
        var links = (List<?>) bob.get("referenced_links");
        assertEquals(2, links.size());
        assertFalse(links.toString().contains("javascript:"));
        assertFalse(links.toString().contains("user:password"));
        assertFalse(links.toString().contains("/forum/"));
        var attachment = (Map<?, ?>) ((List<?>) bob.get("attachments")).getFirst();
        assertEquals("p102", attachment.get("source_post_id"));
        assertTrue(((String) attachment.get("target_url")).endsWith("/docs/checklist.pdf?version=2&language=en"));
        assertEquals("checklist.pdf", attachment.get("filename"));
        assertEquals("application/pdf", attachment.get("declared_content_type"));
        assertEquals("OBSERVED_LINK", attachment.get("status"));
        assertEquals(false, attachment.get("verified_resource"));
        assertNull(attachment.get("content_type"));
        assertNull(attachment.get("content_length"));
    }

    @Test public void nestedQuotesAreRetainedWithoutFabricatingNestedPostsOrMergingAuthors() throws Exception {
        var page = fixture("traditional-page-2.html");
        var posts = posts(page);
        assertEquals("STRUCTURED_POSTS", page.get("extraction_status"));
        assertEquals(2, posts.size());
        var carol = posts.get(0);
        assertEquals("Carol", carol.get("author"));
        assertEquals("p103", carol.get("source_post_id"));
        var quotes = (List<?>) carol.get("quotes");
        assertEquals(2, quotes.size());
        assertEquals(0, ((Map<?, ?>) quotes.get(0)).get("depth"));
        assertEquals(1, ((Map<?, ?>) quotes.get(1)).get("depth"));
        assertFalse(((Map<?, ?>) quotes.get(0)).get("text").toString().contains("Store a copy offline"));
        assertTrue(((Map<?, ?>) quotes.get(1)).get("text").toString().contains("Store a copy offline"));
        assertTrue(carol.get("text").toString().contains("# Markdown-like message"));
        assertTrue(carol.get("text").toString().contains("echo never-run-this"));
        var dana = posts.get(1);
        assertEquals("Dana", dana.get("author"));
        assertNull(dana.get("posted_at"));
        assertTrue(dana.get("quoted_text").toString().contains("Copied forum markup remains a quotation"));
        assertFalse(dana.get("text").toString().contains("Copied forum markup"));
        assertTrue(posts.stream().noneMatch(p -> "p999".equals(p.get("source_post_id"))));
        assertEquals("UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS", carol.get("content_trust"));
    }

    @Test public void semanticLayoutUsesDeclaredBoundariesAndKeepsMissingFieldsNull() throws Exception {
        var page = fixture("semantic-discussion.html");
        var posts = posts(page);
        assertEquals("STRUCTURED_POSTS", page.get("extraction_status"));
        assertEquals("SCHEMA_DISCUSSION_ARTICLE_V1", page.get("extraction_method"));
        assertEquals(2, posts.size());
        assertEquals("comment-201", posts.get(0).get("source_post_id"));
        assertEquals("Eve & Fred", posts.get(0).get("author"));
        var missing = posts.get(1);
        assertNull(missing.get("source_post_id"));
        assertNull(missing.get("author"));
        assertNull(missing.get("posted_at"));
        assertEquals(2, missing.get("ordinal"));
        assertEquals("epub", ((Map<?, ?>) ((List<?>) posts.get(0).get("attachments")).getFirst()).get("extension"));
        assertEquals("txt", ((Map<?, ?>) ((List<?>) missing.get("attachments")).getFirst()).get("extension"));
        assertEquals("DOM_BOUNDARIES_IDENTIFIED", missing.get("confidence"));
    }

    @Test public void repeatedDomIdsAreAmbiguousEvidenceAndDoNotMergeDifferentMessages() throws Exception {
        var page = fixture("repeated-ids.html");
        var posts = posts(page);
        assertEquals("PARTIAL", page.get("extraction_status"));
        assertEquals(2, posts.size());
        assertEquals("First author", posts.get(0).get("author"));
        assertEquals("Second author", posts.get(1).get("author"));
        for (var post : posts) {
            assertEquals("p777", post.get("source_post_id"));
            assertEquals(false, ((Map<?, ?>) post.get("evidence")).get("source_post_id_unique_on_page"));
            assertEquals(true, ((Map<?, ?>) post.get("evidence")).get("duplicate_dom_id"));
        }
        assertTrue(page.get("limitations").toString().contains("DUPLICATE_DOM_POST_IDS"));
    }

    @Test public void ordinaryPageFallbackNeverInventsMessagesAndRemovesObviousBoilerplate() throws Exception {
        var page = fixture("generic-page.html");
        assertEquals("PAGE_TEXT_ONLY", page.get("extraction_status"));
        assertTrue(posts(page).isEmpty());
        String text = (String) page.get("page_text");
        assertTrue(text.contains("Readable page-level advice & examples"));
        assertFalse(text.contains("DO_NOT_EXPORT_NAV"));
        assertFalse(text.contains("DO_NOT_EXPORT_FOOTER"));
        assertFalse(text.contains("HIDDEN_VALUE"));
        assertFalse(text.contains("SCRIPT_VALUE"));
    }

    @Test public void dynamicShellReportsStaticHtmlLimitationWithoutInventedPosts() throws Exception {
        var page = fixture("dynamic-shell.html");
        assertEquals("UNSUPPORTED_DYNAMIC_CONTENT", page.get("extraction_status"));
        assertTrue(posts(page).isEmpty());
        assertEquals("STATIC_DYNAMIC_SHELL_HEURISTIC_V1", page.get("extraction_method"));
        assertTrue(page.get("limitations").toString().contains("STATIC_HTML_DOES_NOT_EXPOSE_DISCUSSION_MESSAGES"));
    }

    @Test public void ambiguousMetadataRemainsNullAndUnparseableHumanDatesAreNotGuessed() {
        var page = parse("<div class='post' id='p1'><div class='postbody'><p class='author'>" +
            "<a class='username'>Alice</a><a class='username'>Bob</a><time datetime='first'>x</time><time datetime='second'>y</time>" +
            "</p><div class='content'>Actual message.</div></div></div>" +
            "<div class='post' id='p2'><div class='postbody'><p class='author'><a class='username'>Carol</a><time>Yesterday</time></p>" +
            "<div class='content'>Second actual message.</div></div></div>");
        assertEquals("PARTIAL", page.get("extraction_status"));
        var posts = posts(page);
        assertNull(posts.get(0).get("author"));
        assertNull(posts.get(0).get("posted_at"));
        assertNull(posts.get(1).get("posted_at"));
        assertEquals("Carol", posts.get(1).get("author"));
        assertEquals(true, ((Map<?, ?>) posts.get(0).get("evidence")).get("ambiguous_author"));
    }

    @Test public void entitiesAreDecodedOnceAtHtmlBoundaryAndMeaningfulQueriesRemainDistinct() {
        var page = parse("<div class='post' id='p1'><div class='postbody'><div class='content'>" +
            "A &amp; B; literal &amp;amp; remains. <a href='?page=2&amp;thread=42'>normal</a>" +
            "<a href='?page=2&amp;amp;thread=42'>intentional literal</a></div></div></div>");
        var post = posts(page).getFirst();
        assertTrue(post.get("text").toString().contains("A & B; literal &amp; remains."));
        var links = (List<?>) post.get("referenced_links");
        assertTrue(((Map<?, ?>) links.get(0)).get("target_url").toString().endsWith("?page=2&thread=42"));
        assertTrue(((Map<?, ?>) links.get(1)).get("target_url").toString().endsWith("?page=2&amp;thread=42"));
    }

    @Test public void queryOnlyAttachmentsAndFragmentReferencesKeepTheCurrentDocumentPath() {
        var page = parse("<div class='post' id='p1'><div class='postbody'><div class='content'>" +
            "<a href='?attachment=manual&amp;download=1' download='manual.pdf' type='application/pdf'>manual</a>" +
            "<a href='#p2'>post reference</a><a href='http://user:secret@example.org/private.pdf'>unsafe</a>" +
            "</div></div></div>");
        var post = posts(page).getFirst();
        var links = (List<?>) post.get("referenced_links");
        assertEquals(2, links.size());
        assertEquals(URL + "?attachment=manual&download=1", ((Map<?, ?>) links.get(0)).get("target_url"));
        assertEquals(URL + "#p2", ((Map<?, ?>) links.get(1)).get("target_url"));
        var attachment = (Map<?, ?>) ((List<?>) post.get("attachments")).getFirst();
        assertEquals(URL + "?attachment=manual&download=1", attachment.get("target_url"));
        assertEquals("manual.pdf", attachment.get("filename"));
    }

    @Test public void boundedMessagesAndTextReportPartialInsteadOfClaimingCompleteCapture() {
        var html = new StringBuilder("<html><body>");
        for (int i = 1; i <= ForumExtractor.MAX_POSTS + 1; i++) html.append("<div class='post' id='p")
            .append(i).append("'><div class='postbody'><div class='content'>Message ").append(i).append("</div></div></div>");
        html.append("</body></html>");
        var page = parse(html.toString());
        assertEquals("PARTIAL", page.get("extraction_status"));
        assertEquals(ForumExtractor.MAX_POSTS, posts(page).size());
        assertTrue(page.get("limitations").toString().contains("POST_LIMIT_REACHED"));
        var longPost = parse("<div class='post' id='p1'><div class='postbody'><div class='content'>" +
            "x".repeat(ForumExtractor.MAX_POST_TEXT + 1) + "</div></div></div>");
        assertEquals("PARTIAL", longPost.get("extraction_status"));
        assertEquals(ForumExtractor.MAX_POST_TEXT, posts(longPost).getFirst().get("text").toString().length());
        assertEquals(true, ((Map<?, ?>) posts(longPost).getFirst().get("evidence")).get("text_truncated"));
    }

    @Test public void invalidSourceOversizedAndEmptyHtmlReturnExplicitFailedWithoutSensitiveValues() {
        var empty = ForumExtractor.extract(URL, new byte[0]);
        assertEquals("FAILED", empty.get("extraction_status"));
        assertTrue(posts(empty).isEmpty());
        var huge = ForumExtractor.extract(URL, new byte[ForumExtractor.MAX_HTML_BYTES + 1]);
        assertEquals("FAILED", huge.get("extraction_status"));
        var invalid = ForumExtractor.extract("http://user:private@example.org/topic", "<p>text</p>".getBytes(StandardCharsets.UTF_8));
        assertEquals("FAILED", invalid.get("extraction_status"));
        assertNull(invalid.get("source_url"));
        assertFalse(invalid.toString().contains("user:private"));
    }

    @Test public void hiddenMessagesDoNotBecomeVisiblePosts() {
        var page = parse("<main><p>Public context.</p><div class='post' id='p1' hidden><div class='postbody'>" +
            "<div class='content'>Hidden message.</div></div></div></main>");
        assertTrue(posts(page).isEmpty());
        assertFalse(page.get("page_text").toString().contains("Hidden message"));
        assertEquals("PARTIAL", page.get("extraction_status"));
    }
}
