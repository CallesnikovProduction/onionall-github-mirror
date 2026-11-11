package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tools.OnionSmoke;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * Kotlin CLI через существующий JUnit entrypoint и одноразовый настоящий каталог.
 * @author Стефан Колесников
 * @author GPT-6 Astra
 */
public class ResearchCommandsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String URL = "http://" + OnionSmoke.HOST + "/";
    private record Result(int code, JsonNode json) { }

    private Result run(Path archive, String... command) throws Exception {
        var args = new ArrayList<>(List.of("--json", "--archive-dir", archive.toString()));
        args.addAll(List.of(command));
        var output = new StringWriter();
        var errors = new StringWriter();
        int code = Onionall.execute(args.toArray(String[]::new), new PrintWriter(output, true),
            new PrintWriter(errors, true), false, Map.of(),
            (config, url) -> { throw new AssertionError("Offline research must not fetch"); });
        assertEquals("", errors.toString());
        return new Result(code, new ObjectMapper().readTree(output.toString()));
    }

    @Test public void researchPlanPersistsScopeWithoutApprovalOrAdmission() throws Exception {
        Path archive = temporary.newFolder().toPath().resolve("archive");
        var result = run(archive, "research", "plan", "--url", URL + "#fragment");
        assertEquals(0, result.code);
        String id = result.json.path("research_id").asText();
        assertEquals(id, result.json.path("plan_id").asText());
        var plan = run(archive, "crawl", "show", id).json;
        assertFalse(plan.path("approved").asBoolean());
        assertEquals(2, plan.path("depth").asInt());
        assertEquals(20, plan.path("max_pages").asInt());
        assertEquals(URL, plan.path("seeds").get(0).asText());
        assertEquals("UNOBSERVED", plan.path("admission").asText());
        assertNotEquals(0, run(archive, "research", "plan", "--url", URL, "--max-pages", "21").code);
        assertNotEquals(0, run(archive, "research", "plan", "--url", "https://example.org").code);
    }

    @Test public void missingApprovalCannotInvokeRuntimeOrModifyPlan() throws Exception {
        Path archive = temporary.newFolder().toPath().resolve("archive");
        String id = run(archive, "research", "plan", "--url", URL).json.path("plan_id").asText();
        var rejected = run(archive, "research", "run", "--plan", id,
            "--runtime", "absent-runtime", "--admission-token-file", "absent-token");
        assertEquals(2, rejected.code);
        assertTrue(rejected.json.toString().contains("approval"));
        assertFalse(run(archive, "crawl", "show", id).json.path("approved").asBoolean());
    }

    @Test public void legacyLargerScopeCannotBeImplicitlyApprovedByResearch() throws Exception {
        Path archive = temporary.newFolder().toPath().resolve("archive");
        String id = run(archive, "crawl", "plan", "--seed", URL, "--depth", "3").json.path("plan_id").asText();
        var rejected = run(archive, "research", "run", "--plan", id, "--approve",
            "--runtime", "absent-runtime", "--admission-token-file", "absent-token");
        assertEquals(2, rejected.code);
        assertFalse(run(archive, "crawl", "show", id).json.path("approved").asBoolean());
    }

    @Test public void resultsDistinguishManualContentAndUnknownFetchMetrics() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path archive = directory.resolve("archive");
        String id = run(archive, "research", "plan", "--url", URL).json.path("plan_id").asText();
        run(archive, "crawl", "approve", id);
        Path html = directory.resolve("offline.html");
        Files.writeString(html, "<html><title>Local specimen</title><body><a href='/a.zip'>file</a></body></html>");
        assertEquals(0, run(archive, "archive", "import", html.toString(), "--plan", id, "--url", URL).code);
        var result = run(archive, "research", "results", id);
        assertEquals(0, result.code);
        assertEquals("MANUAL_IMPORT", result.json.path("pages").get(0).path("source_kind").asText());
        assertTrue(result.json.path("metrics").path("fetch_attempts").isNull());
        assertTrue(result.json.path("metrics").path("fetch_duration_ms").isNull());
        assertEquals(Files.size(html), result.json.path("metrics").path("listed_html_bytes").asLong());
        assertFalse(result.json.path("status").path("authoritative_readback_current").asBoolean());
        assertEquals(1, run(archive, "research", "links", id).json.path("links").size());
        assertEquals(0, run(archive, "research", "files", id).json.path("binary_downloads").asInt(-1));
        assertEquals(1, run(archive, "research", "search", "--query", URL, "--job", id,
            "--host", OnionSmoke.HOST).json.path("results").size());
        assertEquals(1, run(archive, "research", "search", "--query", "specimen", "--host", OnionSmoke.HOST)
            .json.path("results").size());
        assertEquals(0, run(archive, "research", "search", "--query", "%' OR 1=1 --")
            .json.path("results").size());
        assertNotEquals(0, run(archive, "research", "search", "--query", "specimen", "--host", "%.onion").code);
        assertNotEquals(0, run(archive, "research", "search", "--query", "specimen", "--provider", "ahmia").code);
    }

    @Test public void hostAndJobFiltersApplyBeforeResultLimit() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path archive = directory.resolve("archive");
        Path html = directory.resolve("offline.html");
        Files.writeString(html, "<html><title>shared marker</title><body>local specimen</body></html>");
        String otherHost = "aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion";
        assertNotEquals(OnionSmoke.HOST, otherHost);
        String first = run(archive, "research", "plan", "--url", URL).json.path("plan_id").asText();
        run(archive, "crawl", "approve", first);
        run(archive, "archive", "import", html.toString(), "--plan", first, "--url", URL);
        String otherUrl = "http://" + otherHost + "/";
        String second = run(archive, "research", "plan", "--url", otherUrl).json.path("plan_id").asText();
        run(archive, "crawl", "approve", second);
        run(archive, "archive", "import", html.toString(), "--plan", second, "--url", otherUrl);
        var found = run(archive, "research", "search", "--query", "shared marker",
            "--host", OnionSmoke.HOST, "--limit", "1");
        assertEquals(0, found.code);
        assertEquals(1, found.json.path("results").size());
        assertEquals(first, found.json.path("results").get(0).path("job_id").asText());
        assertEquals(0, run(archive, "research", "search", "--query", "shared marker",
            "--host", otherHost, "--job", first).json.path("results").size());
    }

    @Test public void hostFilterIncludesExplicitDefaultPorts() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path archive = directory.resolve("archive");
        Path html = directory.resolve("offline.html");
        Files.writeString(html, "<html><title>port marker</title><body>local</body></html>");
        for (String url : List.of("http://" + OnionSmoke.HOST + ":80/page",
                                  "https://" + OnionSmoke.HOST + ":443/page")) {
            String job = run(archive, "research", "plan", "--url", url).json.path("plan_id").asText();
            run(archive, "crawl", "approve", job);
            assertEquals(0, run(archive, "archive", "import", html.toString(),
                "--plan", job, "--url", url).code);
            var found = run(archive, "research", "search", "--query", "port marker",
                "--host", OnionSmoke.HOST, "--job", job, "--limit", "1");
            assertEquals(0, found.code);
            assertEquals(1, found.json.path("results").size());
            assertEquals(url.replace(":80/", "/").replace(":443/", "/"),
                found.json.path("results").get(0).path("canonical_url").asText());
        }
    }
}
