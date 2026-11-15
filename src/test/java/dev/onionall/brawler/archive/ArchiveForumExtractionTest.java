package dev.onionall.brawler.archive;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Проверки observed forum metadata и inert структурного Markdown.</b>
 *
 * <p>Локальные controlled DOM fixtures не являются проверкой live forum engines.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveForumExtractionTest {
    private static final String URL = "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/topic/42";
    private static final String SNAPSHOT = "3a9218ea-6633-46df-970e-ad3c45608657";

    private Map<String, Object> extract(String html) throws Exception {
        return ArchiveForumExtractor.extract(URL, html.getBytes(StandardCharsets.UTF_8), SNAPSHOT);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> posts(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("posts");
    }

    private String post(String id, String attributes, String body) {
        return "<div class=post id='" + id + "' " + attributes + "><div class=postbody>" +
            "<p class=author><a class=username>Alice</a><time datetime='2026-11-14T10:00:00Z'>today</time></p>" +
            "<div class=content>" + body + "</div></div></div>";
    }

    @Test public void controlledPostsPreserveCodeTablesLinksAndExplicitReplyEvidence() throws Exception {
        String html = "<html><title>Backup discussion</title><body data-thread-id='topic-42' data-forum-name='Storage'>" +
            post("p1", "", "<h2>Original</h2><pre><code>if (a &lt; b) {\n  print(`value`);\n}</code></pre>" +
                "<table><tr><th>Choice</th><th>Value</th></tr><tr><td>A | B</td><td>1</td></tr></table>" +
                "<a href='../guide.md'>Guide</a>") +
            post("p2", "data-reply-to='p1'", "Reply <blockquote cite='#p1'>quoted <strong>original</strong></blockquote>") + "</body></html>";
        var result = extract(html);
        var first = posts(result).getFirst();
        var second = posts(result).get(1);
        assertEquals("topic-42", result.get("thread_id"));
        assertEquals("Storage", result.get("forum"));
        assertEquals("p1", first.get("post_id"));
        assertEquals("Alice", first.get("author"));
        assertEquals(URL + "#p1", first.get("canonical_url"));
        assertEquals("p1", second.get("reply_to"));
        assertEquals(List.of("p1"), second.get("quoted_post_ids"));
        String body = (String) first.get("body_markdown");
        assertTrue(body.contains("## Original"));
        assertTrue(body.contains("```\nif (a < b) {\n  print(`value`);\n}\n```"));
        assertTrue(body.contains("| Choice | Value |"));
        assertTrue(body.contains("A \\| B"));
        assertTrue(body.contains("[Guide](<http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/guide.md>)"));
        assertTrue(second.get("body_markdown").toString().contains("> quoted **original**"));
        assertEquals(first.get("observation_id"), posts(extract(html)).getFirst().get("observation_id"));
        assertNotEquals(first.get("observation_id"), second.get("observation_id"));
        assertEquals(false, result.get("full_thread_coverage_verified"));
    }

    @Test public void semanticLayoutLeavesUnknownMetadataNullAndNeverExecutesRemoteMarkup() throws Exception {
        var result = extract("<article itemscope itemtype='https://schema.org/DiscussionForumPosting'>" +
            "<div itemprop=text>Observed <script>DO_NOT_EXECUTE</script><img src='https://tracker/x' onerror='evil()' alt='diagram'>" +
            "<a href='javascript:evil()'>unsafe link</a><pre>```\n&lt;script&gt;literal code&lt;/script&gt;</pre>" +
            "&lt;img src=x onerror=evil()&gt;</div></article>");
        var post = posts(result).getFirst();
        assertNull(post.get("author"));
        assertNull(post.get("posted_at"));
        assertNull(post.get("post_id"));
        assertNull(post.get("canonical_url"));
        assertNull(post.get("thread_id"));
        assertNull(post.get("reply_to"));
        String body = post.get("body_markdown").toString();
        assertFalse(body.contains("DO_NOT_EXECUTE"));
        assertFalse(body.contains("javascript:"));
        assertFalse(body.contains("https://tracker"));
        assertTrue(body.contains("````\n```\n<script>literal code</script>\n````"));
        assertTrue(body.contains("&lt;img src=x onerror=evil\\(\\)&gt;"));
    }

    @Test public void duplicateIdsAndUnresolvedRepliesCannotCreateFalseRelationships() throws Exception {
        var result = extract(post("p1", "", "First") + post("p1", "", "Second") +
            post("p3", "data-reply-to='p1'", "Third <blockquote data-post-id='p1'>quoted</blockquote>"));
        assertNull(posts(result).getFirst().get("canonical_url"));
        assertNull(posts(result).get(2).get("reply_to"));
        assertEquals(List.of(), posts(result).get(2).get("quoted_post_ids"));
        assertEquals("PARTIAL", result.get("extraction_status"));
        assertTrue(result.get("limitations").toString().contains("UNRESOLVED_EXPLICIT_REPLY_REFERENCE"));
    }

    @Test public void genericPageHasNoInventedPostsOrThreadIdentity() throws Exception {
        var result = extract("<html><title>Guide</title><body><h1>Guide</h1><p>Привет 🧅</p></body></html>");
        assertTrue(posts(result).isEmpty());
        assertNull(result.get("thread_id"));
        assertNull(result.get("forum"));
        assertEquals("PAGE_TEXT_ONLY", result.get("extraction_status"));
        assertTrue(result.get("body_markdown").toString().contains("Привет 🧅"));
    }

    @Test public void boundedRenderingCannotExposeLiteralHtmlByCuttingCodeDelimiters() throws Exception {
        var result = extract(post("p1", "", "*".repeat(70000) + "<code>&lt;img src=x onerror=evil()&gt;</code>"));
        String body = posts(result).getFirst().get("body_markdown").toString();
        assertTrue(body.length() <= 131072);
        assertFalse(body.contains("<img"));
        assertEquals("PARTIAL", result.get("extraction_status"));
        assertTrue(result.get("limitations").toString().contains("ARCHIVE_MARKDOWN_TRUNCATED"));
    }
}
