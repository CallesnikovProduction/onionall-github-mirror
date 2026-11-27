package dev.onionall.brawler.archive;

import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
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
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
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

    @Test public void nativeProjectionPreservesOrdinalsAndRejectsPaddedTableExpansion() throws Exception {
        String list = "<ol start='-2'><li>a<li value='9'>b<li hidden>hidden<li>d</ol>";
        assertEquals("-2. a\n\n9. b\n\n1. d", extract(list).get("body_markdown"));
        String longList = (String) extract("<ol>" + "<li>x</li>".repeat(5000) + "</ol>").get("body_markdown");
        assertTrue(longList.endsWith("5000. x"));
        String table = "<table><tr>" + "<td>x</td>".repeat(120) + "</tr>" +
            "<tr><td>y</td></tr>".repeat(900) + "</table>";
        var result = extract(table);
        assertEquals("x".repeat(120) + "y".repeat(900), result.get("body_markdown"));
        assertTrue(result.get("limitations").toString().contains("ARCHIVE_MARKDOWN_TRUNCATED"));
    }

    @Test public void nativeProjectionUsesExistingCharsetRecoveryAndAnExplicitDepthLimit() throws Exception {
        byte[] cp1251 = "<meta charset=windows-1251><p>Привет".getBytes(Charset.forName("windows-1251"));
        assertEquals("Привет", ArchiveForumExtractor.extract(URL, cp1251, SNAPSHOT).get("body_markdown"));
        assertEquals("�\\(", ArchiveForumExtractor.extract(URL,
            new byte[]{'<', 'p', '>', (byte) 0xc3, '('}, SNAPSHOT).get("body_markdown"));
        assertNull(extract("").get("body_markdown"));
        var deep = extract("<div>".repeat(360) + "</div>".repeat(360) + "<p>end");
        assertEquals("end", deep.get("body_markdown"));
        assertTrue(deep.get("limitations").toString().contains("ARCHIVE_MARKDOWN_TRUNCATED"));
        assertEquals("```\nvisible\nkeep\n```",
            extract("<pre>visible<span hidden>exclude</span><br>keep</pre>").get("body_markdown"));
    }

    @Test public void repeatedLongResolvedLinksUseBoundedInertOutput() throws Exception {
        String html = "<base href='https://example.invalid/" + "a".repeat(1800) + "'>" +
            "<a href='#'>x</a>".repeat(1200);
        var result = extract(html);
        assertEquals("x".repeat(1200), result.get("body_markdown"));
        assertTrue(result.get("limitations").toString().contains("ARCHIVE_MARKDOWN_TRUNCATED"));
    }

    @Test public void inlineAndTransformedCodeCannotEmitLiteralMarkup() throws Exception {
        String marker = "&lt;span data-marker=x&gt;literal&lt;/span&gt;";
        for (String html : List.of(
                "<code>a&#13;&#13;" + marker + "</code>",
                "<ol start=200><li><pre>" + marker + "</pre></li></ol>",
                "<table><tr><td><pre>" + marker + "</pre></td></tr></table>")) {
            String body = extract(html).get("body_markdown").toString();
            assertFalse(body, body.contains("<span"));
            assertTrue(body, body.contains("literal"));
        }
        String list = extract("<ol start=200><li>before<pre>" + marker + "</pre>after</li></ol>")
            .get("body_markdown").toString();
        assertTrue(list, list.contains("before\n"));
        assertTrue(list, list.contains("\n  &lt;span"));
        assertTrue(list, list.contains("\n  after"));
        String link = extract("<a href='https://safe.example/'><code>a</code>" +
            "<code>`](https://example.invalid)</code></a>").get("body_markdown").toString();
        assertTrue(link, link.contains("\\]\\(https://example\\.invalid\\)"));
        assertTrue(link, link.endsWith("](<https://safe.example/>)"));
    }

    @Test public void tablesThatFitTheOutputKeepAllColumnsAndRows() throws Exception {
        var wide = extract("<table><tr>" + "<td>x</td>".repeat(257) + "</tr></table>");
        var tall = extract("<table>" + "<tr><td>x</td></tr>".repeat(4097) + "</table>");
        assertEquals(257, wide.get("body_markdown").toString().split("x", -1).length - 1);
        assertEquals(4097, tall.get("body_markdown").toString().split("x", -1).length - 1);
        assertFalse(wide.get("limitations").toString().contains("ARCHIVE_MARKDOWN_TRUNCATED"));
        assertFalse(tall.get("limitations").toString().contains("ARCHIVE_MARKDOWN_TRUNCATED"));
    }

    @Test public void formattedTextThatFitsAfterTrimmingIsNotShortened() throws Exception {
        var result = extract("<p>" + "*".repeat(60000) + " ".repeat(15000) + "</p>");
        assertEquals("\\*".repeat(60000), result.get("body_markdown"));
        assertFalse(result.get("limitations").toString().contains("ARCHIVE_MARKDOWN_TRUNCATED"));
    }

    @Test public void largeBatchedResponseKeepsIndividualPostTablesInOrder() throws Exception {
        String table = "<table><tr>" + "<td>x".repeat(210) + "<tr><td>row".repeat(115) + "</table>";
        var html = new StringBuilder();
        for (int i = 1; i <= 34; i++) html.append(post("p" + i, "", table));
        var result = posts(extract(html.toString()));
        assertEquals(34, result.size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals("p" + (i + 1), result.get(i).get("post_id"));
            String markdown = result.get(i).get("body_markdown").toString();
            assertTrue(markdown.length() > 65000);
            assertTrue(markdown.endsWith("|"));
            assertEquals("STRUCTURED_POST", result.get(i).get("extraction_status"));
        }
    }

    @Test public void nativeLoaderRequiresExplicitPathOutsideTheManagedLauncher() throws Exception {
        Path cwd = temporary.newFolder().toPath();
        Path candidate = cwd.resolve("target/content-native/release").resolve(System.mapLibraryName("onionall_content"));
        Files.createDirectories(candidate.getParent());
        Files.writeString(candidate, "This file must never be selected implicitly.");
        Path log = cwd.resolve("diagnostic.txt");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
            MissingNativePathProbe.class.getName()).directory(cwd.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("ONIONALL_CONTENT_LIBRARY");
        var process = builder.start();
        try {
            assertTrue("Probe timed out", process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(Files.readString(log), 0, process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static final class MissingNativePathProbe {
        public static void main(String[] args) throws Exception {
            try {
                new ArchiveForumExtractionTest().extract("<p>archived text</p>");
                throw new AssertionError("Implicit library load succeeded");
            } catch (NativeContentException expected) {
                if (!(expected.getCause() instanceof IllegalArgumentException) ||
                    !"An explicit native library path is required".equals(expected.getCause().getMessage())) throw expected;
            }
        }
    }
}
