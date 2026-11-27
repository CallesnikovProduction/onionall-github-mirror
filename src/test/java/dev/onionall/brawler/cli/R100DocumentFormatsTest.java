package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * R100 B01, C01 and D01 archived evidence checks on synthetic HTML.
 * Uses the production local search and snapshot-pinned document reader.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
public class R100DocumentFormatsTest {
    private static final URI ORIGIN = URI.create(
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private record Page(String path, String title, String body) {}
    private record Identity(String pageId, String snapshotId, String hash) {}

    @Test(timeout = 15_000)
    public void b01FindsBookAuthorDescriptionAndOrderedContents() throws Exception {
        var excerpts = evidence("books", "Introduction to distributed systems", List.of(
            new Page("primer.html", "Introduction to distributed systems", """
                <h1>Introduction to distributed systems</h1>
                <p>Author: Mira Calder.</p>
                <p>An introductory book about replication and fault tolerance.</p>
                <h2>Table of contents</h2>
                <ol><li>Time and ordering</li><li>Replication and faults</li></ol>
                """),
            new Page("schedule.html", "Distributed systems lecture schedule", "<p>Room allocations and times.</p>")),
            List.of("primer.html"));
        String markdown = excerpts.get("primer.html");
        check(markdown, "Author: Mira Calder\\.", "An introductory book about replication and fault tolerance\\.",
            "## Table of contents", "1. Time and ordering", "2. Replication and faults");
        assertTrue(markdown, markdown.indexOf("1. Time and ordering") < markdown.indexOf("2. Replication and faults"));
    }

    @Test(timeout = 15_000)
    public void c01PreservesVerbatimFlowRetryCodeInAnInertFence() throws Exception {
        String code = "flowOf(\"fixture\")\n"
            + "    .retry(3) { cause ->\n"
            + "\n"
            + "        cause is IOException\n"
            + "    }";
        var excerpts = evidence("examples", "Kotlin Flow retry example", List.of(
            new Page("flow.html", "Kotlin Flow retry example", """
                <h1>Retry an IO failure</h1>
                <pre><code>%s</code></pre>
                <script>fixtureScriptMustBeOmitted</script>
                """.formatted(code)),
            new Page("faq.html", "Coroutine guide", "<p>Cancel flows that no longer have a collector.</p>")),
            List.of("flow.html"));
        String markdown = excerpts.get("flow.html");
        check(markdown, "```\n" + code + "\n```");
        assertFalse(markdown.contains("fixtureScriptMustBeOmitted"));
    }

    @Test(timeout = 15_000)
    public void d01SeparatesProjectStackPurposeAndReadmeEvidence() throws Exception {
        var excerpts = evidence("projects", "Kotlin HTTP client libraries", List.of(
            new Page("cedar.html", "Kotlin HTTP client libraries: Cedar", """
                <h1>Cedar</h1>
                <p>Stack: Kotlin and JDK HttpClient.</p>
                <p>Purpose: Simple JSON requests on the JVM.</p>
                <h2>README</h2>
                <p>Maintainer: Cedar Tools.</p>
                <p>License: Apache License 2.0.</p>
                """),
            new Page("fjord.html", "Kotlin HTTP client libraries: Fjord", """
                <h1>Fjord</h1>
                <p>Stack: Kotlin and a multiplatform transport.</p>
                <p>Purpose: Composable requests for desktop and native clients.</p>
                <h2>README</h2>
                <p>Maintainer: Fjord Authors.</p>
                <p>License: MIT.</p>
                """),
            new Page("modules.html", "Kotlin utilities", "<p>Command line modules without an HTTP client.</p>")),
            List.of("cedar.html", "fjord.html"));
        String cedar = excerpts.get("cedar.html");
        String fjord = excerpts.get("fjord.html");
        check(cedar, "Stack: Kotlin and JDK HttpClient\\.", "Purpose: Simple JSON requests on the JVM\\.",
            "## README", "Maintainer: Cedar Tools\\.", "License: Apache License 2\\.0\\.");
        check(fjord, "Stack: Kotlin and a multiplatform transport\\.",
            "Purpose: Composable requests for desktop and native clients\\.",
            "## README", "Maintainer: Fjord Authors\\.", "License: MIT\\.");
        assertFalse(cedar.contains("Fjord Authors"));
        assertFalse(fjord.contains("Cedar Tools"));
    }

    private Map<String, String> evidence(String site, String query, List<Page> pages, List<String> expected) throws Exception {
        Path root = temporary.newFolder().toPath();
        Path archive = root.resolve("archive");
        URI base = ORIGIN.resolve(site + "/");
        String job = cli(archive, "research", "plan", "--url", base.toString(), "--max-pages",
            Integer.toString(pages.size())).required("plan_id").asText();
        cli(archive, "crawl", "approve", job);
        var imported = new LinkedHashMap<String, Identity>();
        for (var page : pages) {
            String url = base.resolve(page.path()).toString();
            Path source = Files.writeString(root.resolve(page.path()), """
                <!doctype html><html><head><meta charset="UTF-8"><title>%s</title></head><body>%s</body></html>
                """.formatted(page.title(), page.body()));
            var receipt = cli(archive, "archive", "import", source.toString(), "--plan", job, "--url", url);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            assertEquals(sha, receipt.required("content_hash").asText());
            assertEquals("MANUAL_IMPORT", receipt.required("import_mode").asText());
            imported.put(url, new Identity(receipt.required("page_id").asText(), receipt.required("snapshot_id").asText(), sha));
        }
        assertEquals(pages.size(), cli(archive, "research", "results", job).required("pages").size());

        var search = cli(archive, "research", "search", "--query", query, "--job", job, "--host", base.getHost());
        assertEquals(0, search.required("network_requests").asInt(-1));
        var hits = search.required("results");
        assertEquals(expected.size(), hits.size());
        var remaining = new HashSet<>(expected);
        var evidence = new LinkedHashMap<String, String>();
        for (var hit : hits) {
            String url = hit.required("canonical_url").asText();
            String path = base.relativize(URI.create(url)).toString();
            assertTrue("Unexpected or repeated result: " + url, remaining.remove(path));
            var identity = imported.get(url);
            assertNotNull("Search result must equal an imported source", identity);
            assertEquals(identity.pageId(), hit.required("page_id").asText());
            assertEquals(identity.snapshotId(), hit.required("snapshot_id").asText());
            var doc = cli(archive, "research", "document", identity.pageId(), "--snapshot", identity.snapshotId());
            assertEquals(0, doc.required("network_requests").asInt(-1));
            assertFalse(doc.required("body_truncated").asBoolean(true));
            assertEquals("MANUAL_IMPORT", doc.required("source_kind").asText());
            assertEquals(job, doc.required("job_id").asText());
            assertEquals(url, doc.required("canonical_url").asText());
            assertEquals(identity.snapshotId(), doc.required("snapshot_id").asText());
            assertEquals(identity.hash(), doc.required("content_hash").asText());
            evidence.put(path, doc.required("body_markdown").asText());
        }
        assertTrue(remaining.toString(), remaining.isEmpty());
        return evidence;
    }

    private static void check(String markdown, String... quotes) {
        assertNotNull(markdown);
        for (var quote : quotes) {
            assertTrue("Missing expected evidence: " + quote + "\n" + markdown, markdown.contains(quote));
        }
    }

    private static JsonNode cli(Path archive, String... command) throws IOException {
        var args = new ArrayList<>(List.of("--json", "--archive-dir", archive.toString()));
        args.addAll(List.of(command));
        var output = new StringWriter();
        var errors = new StringWriter();
        int exit = Onionall.execute(args.toArray(String[]::new), new PrintWriter(output, true),
            new PrintWriter(errors, true), false, Map.of(),
            (config, url) -> { throw new AssertionError("Local research evidence must not fetch"); });
        assertEquals("CLI failed: " + String.join(" ", command) + "\n" + output + errors, 0, exit);
        assertEquals("", errors.toString());
        return JSON.readTree(output.toString());
    }
}
