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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the local evidence path for five R100 documentation topics. The
 * corpus is manually imported, with synthetic URLs used only for provenance.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
public class R100TopicEvidenceTest {
    private static final URI ORIGIN = URI.create(
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/");
    private static final String RESOURCE = "/research/r100/topic-evidence/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CORPUS = List.of(
        "kotlin-cancellation", "rocksdb-wal", "postgresql-isolation", "kotlin-multiplatform",
        "rust-borrowing", "kotlin-overview", "kotlin-jvm");
    private static final Map<String, Source> sources = new HashMap<>();
    private static final Map<String, String> objectives = new HashMap<>();
    private static Path archive;
    private static String job;

    @ClassRule public static final TemporaryFolder TEMP = new TemporaryFolder();

    @BeforeClass public static void prepareArchive() throws Exception {
        Path work = TEMP.newFolder().toPath();
        archive = work.resolve("archive");
        job = cli("research", "plan", "--url", ORIGIN.toString(), "--max-pages", "7")
            .required("plan_id").asText();
        cli("crawl", "approve", job);
        try (var input = R100TopicEvidenceTest.class.getResourceAsStream("/research/r100/scenarios.json")) {
            for (var scenario : JSON.readTree(Objects.requireNonNull(input)).required("scenarios")) {
                objectives.put(scenario.required("scenarioId").asText(), scenario.required("objective").asText());
            }
        }
        for (int index = 0; index < CORPUS.size(); index++) {
            String name = CORPUS.get(index);
            byte[] html;
            try (var input = R100TopicEvidenceTest.class.getResourceAsStream(RESOURCE + name + ".html")) {
                html = Objects.requireNonNull(input, name).readAllBytes();
            }
            Path document = Files.write(work.resolve(name + ".html"), html);
            String url = ORIGIN.resolve("doc/" + (index + 1)).toString();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(html));
            var imported = cli("archive", "import", document.toString(), "--plan", job, "--url", url);
            assertEquals("MANUAL_IMPORT", imported.required("import_mode").asText());
            assertFalse(imported.required("transport_verified").asBoolean(true));
            sources.put(name, new Source(url, hash));
        }
        var archivedUrls = new HashSet<String>();
        var pages = cli("research", "results", job).required("pages");
        assertEquals(CORPUS.size(), pages.size());
        for (var page : pages) {
            archivedUrls.add(page.required("canonical_url").asText());
        }
        assertEquals(new HashSet<>(sources.values().stream().map(Source::url).toList()), archivedUrls);
    }

    @Test(timeout = 10_000)
    public void a01FindsCancellationSectionAndKotlinExample() throws Exception {
        String markdown = evidence("A01", "kotlin-cancellation");
        assertLines(markdown, "## Cooperating with cancellation", "Cancellation is cooperative\\.");
        assertTrue(markdown, markdown.contains("""
            ```
            while (hasWork()) {
                ensureActive()
                processNextItem()
            }
            ```
            """.strip()));
    }

    @Test(timeout = 10_000)
    public void a04FindsWalDefinitionAndControlParameter() throws Exception {
        String markdown = evidence("A04", "rocksdb-wal");
        assertLines(markdown, "## Recovery", "The write\\-ahead log records updates before memtables are flushed\\.",
            "## Write options", "Set ` WriteOptions.disableWAL ` to true to disable logging for those writes\\.");
    }

    @Test(timeout = 10_000)
    public void a05PreservesIsolationTableRowsAndColumns() throws Exception {
        String markdown = evidence("A05", "postgresql-isolation");
        assertEquals(markdown, List.of(
            "| Isolation level | Dirty read | Nonrepeatable read | Phantom read | Serialization anomaly |",
            "| --- | --- | --- | --- | --- |",
            "| Read uncommitted | No | Possible | Possible | Possible |",
            "| Read committed | No | Possible | Possible | Possible |",
            "| Repeatable read | No | No | No | Possible |",
            "| Serializable | No | No | No | No |"),
            markdown.lines().filter(line -> line.startsWith("|")).toList());
    }

    @Test(timeout = 10_000)
    public void a06PreservesMultiplatformSourceSetHierarchy() throws Exception {
        String markdown = evidence("A06", "kotlin-multiplatform");
        assertLines(markdown, "## Main source sets");
        assertEquals(markdown, List.of("- commonMain", "  - jvmMain", "  - jsMain"),
            markdown.lines().filter(line -> line.stripLeading().startsWith("- ")).toList());
    }

    @Test(timeout = 10_000)
    public void a08FindsBorrowingExplanationAndRustExample() throws Exception {
        String markdown = evidence("A08", "rust-borrowing");
        assertLines(markdown, "## Borrow without taking ownership", "A shared reference lets a function read a value without taking ownership\\.");
        assertTrue(markdown, markdown.contains("""
            ```
            fn measured(value: &str) -> usize {
                value.len()
            }
            let title = String::from("fixture");
            let length = measured(&title);
            ```
            """.strip()));
    }

    private static void assertLines(String markdown, String... excerpts) {
        var lines = markdown.lines().toList();
        for (String line : excerpts) {
            assertTrue("Missing evidence line: " + line + "\nMarkdown:\n" + markdown, lines.contains(line));
        }
    }

    private static String evidence(String scenario, String fixture) throws Exception {
        Source source = sources.get(fixture);
        var search = cli("research", "search", "--query", Objects.requireNonNull(objectives.get(scenario)),
            "--job", job, "--host", ORIGIN.getHost(), "--limit", Integer.toString(CORPUS.size()));
        assertEquals("local-archive", search.required("provider").asText());
        assertEquals(0, search.required("network_requests").asInt(-1));
        var rows = search.required("results");
        assertTrue(rows.isArray());
        assertEquals(scenario + " should exclude distractors", 1, rows.size());
        var hit = rows.get(0);
        String page = UUID.fromString(hit.required("page_id").asText()).toString();
        String snapshot = UUID.fromString(hit.required("snapshot_id").asText()).toString();
        var document = cli("research", "document", page, "--snapshot", snapshot);
        for (var observation : List.of(hit, document)) {
            assertEquals(page, observation.required("page_id").asText());
            assertEquals(snapshot, observation.required("snapshot_id").asText());
            assertEquals(job, observation.required("job_id").asText());
            assertEquals(source.url(), observation.required("canonical_url").asText());
            assertEquals(source.url(), observation.required("source_url").asText());
            assertEquals(source.hash(), observation.required("content_hash").asText());
            assertEquals("MANUAL_IMPORT", observation.required("source_kind").asText());
            assertTrue(observation.required("fetched_at").isNull());
        }
        assertEquals("HTML", document.required("document_format").asText());
        assertEquals("UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS", document.required("content_trust").asText());
        assertFalse(document.required("body_truncated").asBoolean(true));
        assertNotEquals("FAILED", document.required("extraction_status").asText());
        assertEquals(0, document.required("network_requests").asInt(-1));
        return document.required("body_markdown").asText();
    }

    private record Source(String url, String hash) {}

    private static JsonNode cli(String... command) throws IOException {
        var args = new ArrayList<>(List.of("--json", "--archive-dir", archive.toString()));
        args.addAll(List.of(command));
        var output = new StringWriter();
        var errors = new StringWriter();
        int code = Onionall.execute(args.toArray(String[]::new), new PrintWriter(output, true),
            new PrintWriter(errors, true), false, Map.of(),
            (config, url) -> { throw new AssertionError("Local evidence tests must not fetch"); });
        assertEquals(String.join(" ", command) + "\n" + output + errors, 0, code);
        assertEquals("", errors.toString());
        return JSON.readTree(output.toString());
    }
}
