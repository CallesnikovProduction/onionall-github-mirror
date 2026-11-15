package dev.onionall.brawler.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Real H2/blob/export проверки Archive-only document workflow.</b>
 *
 * <p>Контролируемые source fixtures не являются сетевым crawl. Сравнивает raw
 * bytes, JSON/Markdown provenance и границы сообщений после reopen.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveDocumentWorkflowTest {
    private static final String URL = "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";
    private static final String MARKDOWN = "# Протокол 🧅\n\n[Next](?page=2)\n\n| Key | Value |\n| --- | --- |\n| α | β |\n\n```java\nif (a < b && c > d) {}\n```\n\nInline `<script>` is code.\n";
    private static final String FORUM = """
        <html><head><title>Fixture: bounded archive discussion</title></head>
        <body data-thread-id="fixture-thread-7" data-forum-name="Archive fixture forum">
        <h1>Bounded archive discussion</h1>
        <div class="post" id="p100"><div class="postbody">
        <p class="author"><a class="username">alice-fixture</a><time datetime="2026-11-14T08:00:00Z"></time></p>
        <div class="content"><h2>Question</h2><p>How should we preserve code?</p>
        <pre><code>if (a &lt; b) { preserve(); }</code></pre>
        <table><tr><th>Format</th><th>Evidence</th></tr><tr><td>JSON</td><td>versioned</td></tr></table>
        <p><a href="guide.md">Related guide</a></p></div></div></div>
        <div class="post" id="p101" data-reply-to="p100"><div class="postbody">
        <p class="author"><a class="username">bob-fixture</a></p>
        <div class="content"><blockquote cite="#p100"><p>How should we preserve code?</p></blockquote>
        <p>Keep original bytes and separate the readable view.</p><a href="manual.pdf">Attachment hint</a>
        <script>must_not_execute()</script></div></div></div></body></html>
        """;
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String plan(ArchiveStore store, int max) throws IOException {
        String id = (String) store.createPlan(List.of(URL), 2, max, "controlled-archive-fixture").get("plan_id");
        store.approvePlan(id); return id;
    }
    private static List<?> rows(Map<String, Object> map, String key) { return (List<?>) map.get(key); }

    @Test public void markdownSourceSearchVersionsAndFormatsSurviveReopen() throws Exception {
        Path root = Files.createTempDirectory("archive-md");
        String job, id, snapshot;
        try (var store = new ArchiveStore(root)) {
            job = plan(store, 3);
            var saved = store.importDocument(job, URL + "notes.md", "text/markdown; charset=UTF-8", bytes(MARKDOWN));
            id = (String) saved.get("document_id"); snapshot = (String) saved.get("snapshot_id");
            assertEquals("MARKDOWN", saved.get("document_format"));
            assertEquals(MARKDOWN, saved.get("source_text"));
            assertEquals("MANUAL_IMPORT", saved.get("source_kind")); assertNull(saved.get("fetched_at"));
            assertEquals(0, saved.get("http_status"));
            assertArrayEquals(bytes(MARKDOWN), Files.readAllBytes(Path.of((String) saved.get("storage_location"))));
            assertEquals(snapshot, store.importDocument(job, URL + "notes.md", "text/markdown", bytes(MARKDOWN)).get("snapshot_id"));
            assertEquals(1, rows(saved, "history").size());
            assertEquals(URL + "notes.md?page=2", ((Map<?, ?>) rows(saved, "links").getFirst()).get("target_url"));
            assertTrue(((String) saved.get("body_markdown")).contains("a < b && c > d"));
            assertTrue(((String) saved.get("body_markdown")).contains("[Next](<" + URL + "notes.md?page=2>)"));
            store.importDocument(job, URL + "notes.md", "text/markdown", bytes(MARKDOWN + "\nChanged\n"));
            assertEquals(1, rows(store.search("Протокол", job, 100), "results").size());
            assertEquals(1L, store.job(job).get("archived_pages"));
            assertEquals(2L, store.job(job).get("document_versions"));
        }
        try (var store = new ArchiveStore(root)) {
            var restored = store.researchDocument(id);
            assertEquals(MARKDOWN + "\nChanged\n", restored.get("source_text"));
            assertEquals(2, rows(restored, "history").size());
            assertEquals(1, rows(store.search("Changed", job, java.net.URI.create(URL).getHost(), 1), "results").size());
            try (var db = DriverManager.getConnection("jdbc:h2:file:" + root.resolve("catalog") + ";AUTO_SERVER=FALSE", "sa", "");
                var sql = db.createStatement(); var versions = sql.executeQuery("SELECT MAX(version) FROM schema_version")) {
                assertTrue(versions.next()); assertEquals(2, versions.getInt(1));
            }
        }
    }

    @Test public void mediaTypeNotFilenameAndInvalidTextRejectsWithoutMutation() throws Exception {
        try (var store = new ArchiveStore(Files.createTempDirectory("archive-text-types"))) {
            String job = plan(store, 4);
            var plain = store.importDocument(job, URL + "looks.md", "text/plain", bytes(MARKDOWN));
            assertEquals("PLAIN_TEXT", plain.get("document_format"));
            assertEquals(MARKDOWN, plain.get("source_text"));
            var html = store.importDocument(job, URL + "html.md", "text/html", bytes("<html><title>Actual HTML</title>safe</html>"));
            assertEquals("HTML", store.researchDocument((String) html.get("page_id")).get("document_format"));
            for (String type : new String[] {null, "", "application/octet-stream", "text/markdown;charset=windows-1251", "text/markdown; garbage", "text/plain;charset=utf-8;charset=utf-8"}) {
                assertThrows(IOException.class, () -> store.importDocument(job, URL + "bad", type, bytes("x")));
            }
            assertThrows(IOException.class, () -> store.importDocument(job, URL + "bad", "text/markdown", new byte[] {(byte) 0xc3, 0x28}));
            assertThrows(IOException.class, () -> store.importDocument(job, URL + "bad", "text/plain", new byte[] {0}));
            assertThrows(IOException.class, () -> store.importDocument(job, URL + "bad", "text/plain", new byte[131073]));
            assertThrows(IOException.class, () -> store.archiveDocument(job, URL + "bad", 3, URL, 200, "text/plain", bytes("x")));
            assertThrows(IOException.class, () -> store.archiveDocument(job, "http://outside.example/", 0, URL, 200, "text/plain", bytes("x")));
            assertEquals(2L, store.job(job).get("archived_pages"));
        }
    }

    @Test public void maximumTextBudgetAndHtmlShareDistinctUrlBudget() throws Exception {
        try (var store = new ArchiveStore(Files.createTempDirectory("archive-text-limit"))) {
            String job = plan(store, 1);
            byte[] maximum = bytes("x".repeat(131072));
            var text = store.importDocument(job, URL, "text/plain", maximum);
            assertEquals(131072, text.get("content_size"));
            store.importHtml(job, URL, bytes("<html>same URL</html>"), List.of());
            assertEquals(1L, store.job(job).get("archived_pages"));
            assertThrows(IOException.class, () -> store.importHtml(job, URL + "extra", bytes("<html>x</html>"), List.of()));
            assertThrows(IOException.class, () -> store.importDocument(job, URL + "extra", "text/plain", bytes("x")));
        }
    }

    @Test public void markdownReaderNeverExecutesRawHtmlImagesOrUnsafeLinkDefinitions() throws Exception {
        var source = "# Safe\n<script>alert(1)</script>\n![pixel](https://outside.example/pixel.png)\n\n```html\n<script>literal code</script>\n```\n";
        var decoded = ArchiveTextDocuments.decode(URL, "text/markdown", bytes(source));
        String markdown = (String) decoded.get("body_markdown");
        assertEquals(source, decoded.get("source_text"));
        assertTrue(markdown.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(markdown.contains("![pixel]"));
        for (String scheme : List.of("javascript", "file", "data", "vbscript")) {
            String unsafe = "# Source\n[click][bad]\n\n[bad]:\n " + scheme + ":payload\n";
            var literal = ArchiveTextDocuments.decode(URL, "text/markdown", bytes(unsafe));
            assertEquals(unsafe, literal.get("source_text"));
            assertEquals(ArchiveTextDocuments.fencedText(unsafe), literal.get("body_markdown"));
            assertTrue(((List<?>) literal.get("limitations")).contains("UNSUPPORTED_LINK_SCHEME_RENDERED_AS_LITERAL_SOURCE"));
        }
        assertTrue(markdown.contains("```html\n<script>literal code</script>\n```"));
    }

    @Test public void actualBundleKeepsPostsEvidenceAndJsonMarkdownInAgreement() throws Exception {
        Path parent = Files.createTempDirectory("archive-bundle-fixture");
        Path root = parent.resolve("catalog");
        Path output = System.getProperty("archive.example.output") == null ? parent.resolve("research-export") : Path.of(System.getProperty("archive.example.output"));
        try (var store = new ArchiveStore(root)) {
            String job = plan(store, 3);
            var page = store.importHtml(job, URL + "thread", bytes(FORUM), List.of(Map.of("target_url", URL + "guide.md", "disposition", "OBSERVED")));
            var text = store.importDocument(job, URL + "guide.md", "text/markdown", bytes(MARKDOWN));
            var manifest = store.exportResearch(job, output, 20);
            assertEquals(2, manifest.get("document_count")); assertEquals(false, manifest.get("truncated"));
            assertTrue(Files.isRegularFile(output.resolve("manifest.json"))); assertFalse(Files.exists(output.resolve("INCOMPLETE")));
            var mapper = new ObjectMapper();
            var entry = (Map<?, ?>) rows(manifest, "documents").getFirst();
            var json = mapper.readTree(output.resolve((String) entry.get("json")).toFile());
            String markdown = Files.readString(output.resolve((String) entry.get("markdown")));
            assertEquals(page.get("snapshot_id"), json.path("snapshot_id").asText());
            assertEquals(2, json.path("forum_thread").path("posts").size());
            var second = json.path("forum_thread").path("posts").get(1);
            assertEquals("p100", second.path("reply_to").asText()); assertTrue(second.path("posted_at").isNull());
            assertTrue(markdown.contains("Observed post #1")); assertTrue(markdown.contains("Observed post #2"));
            assertTrue(markdown.contains("alice-fixture")); assertTrue(markdown.contains("bob-fixture"));
            assertTrue(markdown.contains("**Reply to:** p100")); assertTrue(markdown.contains("UNKNOWN"));
            assertTrue(markdown.contains("```")); assertTrue(markdown.contains("| Format | Evidence |"));
            assertTrue(markdown.contains("> How should we preserve code?"));
            assertFalse(markdown.contains("must_not_execute()"));
            assertTrue(markdown.contains("PARTIAL_OR_UNKNOWN"));
            assertThrows(FileAlreadyExistsException.class, () -> store.exportResearch(job, output, 20));
            assertEquals(MARKDOWN, Files.readString(output.resolve("documents/document-002.source.txt")));
            assertEquals("UNOBSERVED", store.job(job).get("admission"));
            assertEquals("MARKDOWN", store.researchDocument((String) text.get("document_id")).get("document_format"));
        }
    }

    @Test public void corruptTextAndSqlFailureRetainEvidenceAndAllowRecovery() throws Exception {
        Path root = Files.createTempDirectory("archive-text-recovery");
        String job, id; Path blob;
        try (var store = new ArchiveStore(root)) {
            job = plan(store, 2);
            var text = store.importDocument(job, URL, "text/plain", bytes("retained"));
            id = (String) text.get("document_id"); blob = Path.of((String) text.get("storage_location"));
            Files.writeString(blob, "corrupted");
            assertThrows(IOException.class, () -> store.researchDocument(id));
            assertThrows(IOException.class, () -> store.importDocument(job, URL, "text/plain", bytes("retained")));
            assertEquals("corrupted", Files.readString(blob));
            Files.delete(blob);
            assertEquals(id, store.importDocument(job, URL, "text/plain", bytes("retained")).get("document_id"));
            try (var db = DriverManager.getConnection("jdbc:h2:file:" + root.resolve("catalog") + ";AUTO_SERVER=FALSE", "sa", ""); var sql = db.createStatement()) {
                sql.execute("ALTER TABLE archive_document_versions_v1 ADD CONSTRAINT fixture_doc_reject CHECK(title<>'reject')");
            }
            assertThrows(IOException.class, () -> store.importDocument(job, URL + "bad.md", "text/markdown", bytes("# reject")));
            assertEquals(1L, store.job(job).get("archived_pages"));
        }
        try (var store = new ArchiveStore(root)) {
            assertEquals("retained", store.researchDocument(id).get("source_text"));
            assertEquals(1L, store.job(job).get("document_versions"));
        }
    }

    @Test public void concurrentHtmlTextWritersShareOneGuardedUrlBudget() throws Exception {
        Path root = Files.createTempDirectory("archive-text-html-race");
        try (var left = new ArchiveStore(root); var right = new ArchiveStore(root);
                var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            String job = plan(left, 1);
            var start = new java.util.concurrent.CyclicBarrier(2);
            var html = workers.submit(() -> {
                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try { left.importHtml(job, URL + "html", bytes("<html>retained</html>"), List.of()); return true; }
                catch (IOException e) { if (!e.getMessage().contains("page limit")) throw e; return false; }
            });
            var text = workers.submit(() -> {
                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try { right.importDocument(job, URL + "text.md", "text/markdown", bytes(MARKDOWN)); return true; }
                catch (IOException e) { if (!e.getMessage().contains("page limit")) throw e; return false; }
            });
            boolean htmlWon = html.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean textWon = text.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(htmlWon, textWon);
            assertEquals(1L, left.job(job).get("archived_pages"));
        }
    }

    @Test public void ambiguousCodeTokensNeverExposeRemoteHtmlOrInventTitles() throws Exception {
        for (String unsafe : List.of("``` bad`info\n<img src=x onerror=alert(1)>\n```",
                "\\`<img src=x onerror=alert(1)>\\`", "`open\n`<img src=x onerror=alert(1)>`")) {
            var decoded = ArchiveTextDocuments.decode(URL, "text/markdown", bytes(unsafe));
            assertEquals(ArchiveTextDocuments.fencedText(unsafe), decoded.get("body_markdown"));
            assertTrue(((List<?>) decoded.get("limitations")).contains("AMBIGUOUS_CODE_DELIMITERS_RENDERED_AS_LITERAL_SOURCE"));
        }
        var title = ArchiveTextDocuments.decode(URL, "text/markdown", bytes("```md\n# Not a title\n```\n# Actual title\n"));
        assertEquals("Actual title", title.get("title"));
    }
}
