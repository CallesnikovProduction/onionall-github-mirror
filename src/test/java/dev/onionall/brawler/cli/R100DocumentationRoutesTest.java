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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * R100 documentation paths through observed links and verified local snapshots.
 * Every page is a synthetic manual import, without runtime traversal.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
public class R100DocumentationRoutesTest {
    private static final URI ORIGIN = URI.create(
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private record Page(String path, String title, String html) {}
    private record Observed(String pageId, String snapshotId, String contentHash, String url) {}
    private record Replay(Path directory, Path archive, String job, URI base, Map<String, Observed> pages) {}

    @Test(timeout = 15_000)
    public void a03FindsTopologySectionThroughGuideAndLabelsHistoricalRoute() throws Exception {
        var replay = replay("storm-docs", List.of(
            new Page("index.html", "Apache Storm topology configuration documentation", """
                <h1>Topology documentation</h1>
                <a href="guides/topology.html">Topology guide</a>
                <a href="glossary.html">Glossary</a>
                """),
            new Page("guides/topology.html", "Topology guide", """
                <h1>Build a topology</h1>
                <a href="../index.html">Homepage</a>
                <a href="configuration.html">Configuration section</a>
                """),
            new Page("guides/configuration.html", "Configuration section", """
                <h1>Topology configuration</h1>
                <p>Set topology.workers to select the number of worker processes.</p>
                """),
            new Page("glossary.html", "Storm glossary", "<p>Workers are processes.</p>")));
        var home = selected(replay, "Apache Storm topology configuration", "index.html");
        var guide = replay.pages().get("guides/topology.html");
        var section = replay.pages().get("guides/configuration.html");

        verifyRoute(replay, 2, home, guide, section);
        assertMarkdown(replay, section, "Set topology\\.workers to select the number of worker processes\\.");
        verifyNoRoute(route(replay, home, section, 1), Set.of("LIMITED", "NO_ROUTE_IN_STORED_GRAPH"));

        // Restore a page with changed content and no guide link. The older edge
        // remains usable as historical evidence, never as a route of latest snapshots.
        var changed = importPage(replay, new Page("index.html", "Topology documentation", "<p>No current guide.</p>"));
        assertEquals(home.pageId(), changed.pageId());
        assertNotEquals(home.snapshotId(), changed.snapshotId());
        var historical = route(replay, changed, section, 2);
        assertEquals("FOUND", historical.required("status").asText());
        assertEquals("MIXED_OR_HISTORICAL", historical.required("provenance").asText());
        assertEquals(home.snapshotId(), historical.required("path").get(0).required("snapshot_id").asText());
        assertEquals(changed.snapshotId(), historical.required("page_evidence").get(0).required("snapshot_id").asText());
    }

    @Test(timeout = 15_000)
    public void a07FindsConfigurationExampleLinkedByApiReference() throws Exception {
        var replay = replay("ktor-docs", List.of(
            new Page("api/timeout.html", "Ktor client timeout configuration API reference", """
                <h1>HttpTimeout API reference</h1>
                <p>The HttpTimeout plugin takes a request timeout and a connection timeout.</p>
                <a href="../examples/timeout.html">Client configuration example</a>
                """),
            new Page("examples/timeout.html", "Client configuration example", """
                <h1>Configure the timeouts</h1>
                <pre><code>requestTimeoutMillis = 1500
                connectTimeoutMillis = 500</code></pre>
                """)));
        var api = selected(replay, "Ktor client timeout configuration", "api/timeout.html");
        var example = replay.pages().get("examples/timeout.html");

        verifyRoute(replay, 1, api, example);
        assertMarkdown(replay, api, "The HttpTimeout plugin takes a request timeout and a connection timeout\\.");
        assertMarkdown(replay, example, "requestTimeoutMillis = 1500\nconnectTimeoutMillis = 500");
    }

    @Test(timeout = 15_000)
    public void a10FindsChangelogAndIncompatibleChangeLinkedByMigrationGuide() throws Exception {
        var replay = replay("migration-docs", List.of(
            new Page("migration/2-to-3.html", "Migration guide from library version 2 to version 3", """
                <h1>Upgrade to version 3</h1>
                <p>Replace the old profile key with a named profile reference.</p>
                <a href="../changelog/v3.html">Version 3 changelog</a>
                """),
            new Page("changelog/v3.html", "Version 3 changelog", """
                <h1>Incompatible changes</h1>
                <p>Version 3 rejects the old profile key.</p>
                """),
            new Page("changelog/v2.html", "Version 2 changelog", "<p>The old profile key is supported.</p>")));
        var migration = selected(replay, "Migration guide from library version 2 to version 3", "migration/2-to-3.html");
        var changelog = replay.pages().get("changelog/v3.html");

        verifyRoute(replay, 1, migration, changelog);
        assertMarkdown(replay, migration, "Replace the old profile key with a named profile reference\\.");
        assertMarkdown(replay, changelog, "Version 3 rejects the old profile key\\.");
    }

    private Replay replay(String site, List<Page> pages) throws Exception {
        Path root = temporary.newFolder().toPath();
        Path archive = root.resolve("archive");
        URI base = ORIGIN.resolve(site + "/");
        // All input pages must belong to the same explicitly approved archive plan.
        String seed = base.resolve(pages.getFirst().path()).toString();
        String job = cli(archive, "research", "plan", "--url", seed, "--max-pages", Integer.toString(pages.size()))
            .required("plan_id").asText();
        cli(archive, "crawl", "approve", job);
        var replay = new Replay(root, archive, job, base, new LinkedHashMap<>());
        for (var page : pages) {
            replay.pages().put(page.path(), importPage(replay, page));
        }
        assertEquals(pages.size(), cli(archive, "research", "results", job).required("pages").size());
        return replay;
    }

    private Observed importPage(Replay replay, Page page) throws Exception {
        Path source = Files.createTempFile(replay.directory(), "source-", ".html");
        Files.writeString(source, """
            <!doctype html><html><head><meta charset="UTF-8"><title>%s</title></head><body>%s</body></html>
            """.formatted(page.title(), page.html()));
        String url = replay.base().resolve(page.path()).toString();
        JsonNode imported = cli(replay.archive(), "archive", "import", source.toString(),
            "--plan", replay.job(), "--url", url);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        assertEquals(url, imported.required("canonical_url").asText());
        assertEquals(hash, imported.required("content_hash").asText());
        assertEquals("MANUAL_IMPORT", imported.required("import_mode").asText());
        assertEquals(0, imported.required("network_requests").asInt(-1));
        return new Observed(imported.required("page_id").asText(), imported.required("snapshot_id").asText(), hash, url);
    }

    private static Observed selected(Replay replay, String query, String path) throws IOException {
        var rows = cli(replay.archive(), "research", "search", "--job", replay.job(), "--query", query,
            "--host", replay.base().getHost()).required("results");
        assertEquals(1, rows.size());
        var expected = replay.pages().get(path);
        var row = rows.get(0);
        assertEquals(expected.pageId(), row.required("page_id").asText());
        assertEquals(expected.snapshotId(), row.required("snapshot_id").asText());
        assertEquals(expected.contentHash(), row.required("content_hash").asText());
        return expected;
    }

    private static JsonNode route(Replay replay, Observed from, Observed to, int hops) throws IOException {
        JsonNode result = cli(replay.archive(), "research", "route", "--job", replay.job(),
            "--from", from.pageId(), "--to", to.pageId(), "--max-hops", Integer.toString(hops));
        assertEquals(0, result.required("network_requests").asInt(-1));
        assertFalse(result.required("traversal_verified").asBoolean(true));
        return result;
    }

    private static void verifyRoute(Replay replay, int hops, Observed... pages) throws IOException {
        var result = route(replay, pages[0], pages[pages.length - 1], hops);
        assertEquals("FOUND", result.required("status").asText());
        assertEquals("MANUAL_IMPORT", result.required("provenance").asText());
        assertEquals(pages.length - 1, result.required("hop_count").asInt(-1));
        assertEquals(pages.length, result.required("page_ids").size());
        var path = result.required("path");
        assertEquals(pages.length - 1, path.size());
        for (int i = 0; i < pages.length; i++) {
            assertEquals(pages[i].pageId(), result.required("page_ids").get(i).asText());
            var captured = result.required("page_evidence").get(i);
            assertEquals(pages[i].pageId(), captured.required("page_id").asText());
            assertEquals(pages[i].snapshotId(), captured.required("snapshot_id").asText());
            assertEquals(pages[i].contentHash(), captured.required("content_hash").asText());
            assertEquals("MANUAL_IMPORT", captured.required("source_kind").asText());
            if (i == pages.length - 1) break;
            var edge = path.get(i);
            assertEquals(replay.job(), edge.required("job_id").asText());
            assertEquals(pages[i].pageId(), edge.required("source_page_id").asText());
            assertEquals(pages[i + 1].pageId(), edge.required("target_page_id").asText());
            assertEquals(pages[i].url(), edge.required("source_url").asText());
            assertEquals(pages[i + 1].url(), edge.required("target_url").asText());
            assertEquals(pages[i].snapshotId(), edge.required("snapshot_id").asText());
            assertEquals("MANUAL_UNSCHEDULED", edge.required("disposition").asText());
        }
    }

    private static void verifyNoRoute(JsonNode result, Set<String> expected) {
        assertTrue(result.toString(), expected.contains(result.required("status").asText()));
        assertTrue(result.required("path").isEmpty());
    }

    private static void assertMarkdown(Replay replay, Observed page, String expected) throws IOException {
        var doc = cli(replay.archive(), "research", "document", page.pageId(), "--snapshot", page.snapshotId());
        assertEquals(page.url(), doc.required("canonical_url").asText());
        assertEquals(replay.job(), doc.required("job_id").asText());
        assertEquals(page.snapshotId(), doc.required("snapshot_id").asText());
        assertEquals(page.contentHash(), doc.required("content_hash").asText());
        assertEquals("MANUAL_IMPORT", doc.required("source_kind").asText());
        assertFalse(doc.required("body_truncated").asBoolean(true));
        String markdown = doc.required("body_markdown").asText();
        assertTrue(markdown, markdown.contains(expected));
    }

    private static JsonNode cli(Path archive, String... command) throws IOException {
        var args = new ArrayList<>(List.of("--json", "--archive-dir", archive.toString()));
        args.addAll(List.of(command));
        var output = new StringWriter();
        var errors = new StringWriter();
        int exit = Onionall.execute(args.toArray(String[]::new), new PrintWriter(output, true),
            new PrintWriter(errors, true), false, Map.of(),
            (config, url) -> { throw new AssertionError("Archive replay must not fetch"); });
        assertEquals("CLI failed: " + String.join(" ", command) + "\n" + output + errors, 0, exit);
        assertEquals("", errors.toString());
        return JSON.readTree(output.toString());
    }
}
