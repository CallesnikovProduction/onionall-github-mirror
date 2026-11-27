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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * R100 A09: retrieves a relevant guide through the CLI and checks the four
 * definitions against a verified, persisted HTML projection. The synthetic
 * addresses serve only as archive metadata.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
public class R100DocumentationEvidenceTest {
    private static final URI ORIGIN = URI.create(
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 15_000)
    public void a09FindsDefinitionsInReferenceTable() throws Exception {
        String markdown = evidence("maven-dependency-scopes-table.html", "reference.html");
        assertExactEvidence(markdown, "| Scope | Classpath availability |",
            "| compile | Available on all project classpaths by default. |",
            "| runtime | Available to run the application and tests, but not to compile the application. |",
            "| provided | Available for compilation and tests; the runtime environment supplies it. |",
            "| test | Available only to compile and run tests. |");
    }

    @Test(timeout = 15_000)
    public void a09FindsDefinitionsInReorderedHandbookSections() throws Exception {
        String markdown = evidence("maven-dependency-scopes-sections.html", "handbook/scopes.html");
        assertExactEvidence(markdown,
            "## test\n\nUse a dependency in test compilation and test execution only.",
            "## provided\n\nCompile and test against it while relying on the runtime environment to provide it.",
            "## compile\n\nUse the default scope to expose the dependency to all project classpaths.",
            "## runtime\n\nUse the dependency in runtime and test classpaths, leaving it off the compile classpath.");
    }

    private String evidence(String fixture, String guidePath) throws Exception {
        Path root = temporary.newFolder().toPath();
        Path archive = root.resolve("archive");
        byte[] html;
        try (var input = R100DocumentationEvidenceTest.class.getResourceAsStream(
                "/research/r100/documentation/" + fixture)) {
            html = Objects.requireNonNull(input, fixture).readAllBytes();
        }
        var guide = Files.write(root.resolve(fixture), html);
        var distractor = Files.writeString(root.resolve("build-news.html"), """
            <!doctype html>
            <html><head><title>Build news: test, compile, runtime</title></head>
            <body><h1>Archive of build events</h1><p>A provided artifact made the runtime test
            compile faster this week.</p></body></html>
            """);
        String url = ORIGIN.resolve(guidePath).toString();
        String job = cli(archive, "research", "plan", "--url", ORIGIN.toString(), "--max-pages", "2")
            .required("plan_id").asText();
        cli(archive, "crawl", "approve", job);
        for (var file : List.of(distractor, guide)) {
            String source = file.equals(guide) ? url : ORIGIN.resolve("news/build.html").toString();
            assertEquals("MANUAL_IMPORT", cli(archive, "archive", "import", file.toString(), "--plan", job,
                "--url", source).required("import_mode").asText());
        }
        assertEquals(2, cli(archive, "research", "results", job).required("pages").size());

        var results = cli(archive, "research", "search", "--query", "Maven dependency scopes", "--job", job,
            "--host", ORIGIN.getHost(), "--limit", "2").required("results");
        assertTrue(results.isArray());
        assertEquals(1, results.size());
        var row = results.get(0);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(html));
        assertEquals(url, row.required("canonical_url").asText());
        assertEquals(job, row.required("job_id").asText());
        assertEquals(hash, row.required("content_hash").asText());
        assertEquals("MANUAL_IMPORT", row.required("source_kind").asText());

        String page = row.required("page_id").asText();
        String snapshot = row.required("snapshot_id").asText();
        var document = cli(archive, "research", "document", page, "--snapshot", snapshot);
        assertEquals(page, document.required("page_id").asText());
        assertEquals(url, document.required("canonical_url").asText());
        assertEquals(job, document.required("job_id").asText());
        assertEquals(snapshot, document.required("snapshot_id").asText());
        assertEquals(hash, document.required("content_hash").asText());
        assertEquals("HTML", document.required("document_format").asText());
        assertEquals("MANUAL_IMPORT", document.required("source_kind").asText());
        assertEquals("UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS", document.required("content_trust").asText());
        assertFalse(document.required("body_truncated").asBoolean(true));
        assertNotEquals("FAILED", document.required("extraction_status").asText());

        var limited = cli(archive, "research", "document", page, "--snapshot", snapshot, "--max-chars", "64");
        assertTrue(limited.required("body_truncated").asBoolean());
        assertTrue(limited.required("body_markdown").asText().length() <= 64);
        assertEquals(snapshot, limited.required("snapshot_id").asText());
        assertEquals(hash, limited.required("content_hash").asText());

        var stale = run(archive, "research", "document", page, "--snapshot",
            "7f300c4b-9e15-4515-8b75-dd84457cc2c8");
        assertNotEquals(0, stale.exit());
        assertTrue(stale.json().required("errors").toString().toLowerCase(java.util.Locale.ROOT).contains("snapshot"));
        assertFalse(stale.json().has("body_markdown"));

        return document.required("body_markdown").asText();
    }

    private static void assertExactEvidence(String markdown, String... excerpts) {
        assertTrue(markdown, markdown != null && !markdown.isBlank());
        var lines = markdown.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
        String normalized = "\n" + String.join("\n", lines) + "\n";
        for (var excerpt : excerpts) {
            // The production projection escapes punctuation and retains blank layout lines.
            String expected = excerpt.replace("\n\n", "\n").replace(".", "\\.");
            assertTrue("Missing exact labelled definition:\n" + excerpt + "\nProjected evidence:\n" + markdown,
                normalized.contains("\n" + expected + "\n"));
        }
    }

    private record CliResult(int exit, JsonNode json) {}

    private static JsonNode cli(Path archive, String... command) throws IOException {
        var result = run(archive, command);
        assertEquals("CLI failed: " + String.join(" ", command) + "\n" + result.json(), 0, result.exit());
        return result.json();
    }

    private static CliResult run(Path archive, String... command) throws IOException {
        var args = new ArrayList<>(List.of("--json", "--archive-dir", archive.toString()));
        args.addAll(List.of(command));
        var output = new StringWriter();
        var errors = new StringWriter();
        int exit = Onionall.execute(args.toArray(String[]::new), new PrintWriter(output, true),
            new PrintWriter(errors, true), false, Map.of(),
            (config, url) -> { throw new AssertionError("Local evidence tests must not fetch"); });
        assertEquals("", errors.toString());
        return new CliResult(exit, JSON.readTree(output.toString()));
    }
}
