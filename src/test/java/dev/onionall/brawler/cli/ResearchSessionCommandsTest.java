package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tools.OnionSmoke;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * Независимые CLI-вызовы с настоящим временным H2-каталогом.
 * Manual seeds и пустой архив не являются fake успешным crawl или admission proof.
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
public class ResearchSessionCommandsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> URLS = List.of(
        "http://" + OnionSmoke.HOST + "/thread/one",
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/category",
        "http://aibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibejsqd.onion/start");
    private record Result(int code, JsonNode json) { }

    private Result run(Path archive, String... command) throws Exception {
        var arguments = new ArrayList<>(List.of("--json", "--archive-dir", archive.toString()));
        arguments.addAll(List.of(command));
        var output = new StringWriter();
        var errors = new StringWriter();
        int code = Onionall.execute(arguments.toArray(String[]::new), new PrintWriter(output, true),
            new PrintWriter(errors, true), false, Map.of(),
            (configuration, url) -> { throw new AssertionError("Offline session command must not fetch"); });
        assertEquals("", errors.toString());
        JsonNode json = JSON.readTree(output.toString());
        assertNotNull("Every command must provide a JSON result", json);
        return new Result(code, json);
    }

    private JsonNode success(Path archive, String... command) throws Exception {
        var result = run(archive, command);
        assertEquals(result.json.toString(), 0, result.code);
        return result.json;
    }

    private JsonNode imported(Path archive) throws Exception {
        return success(archive, "research", "import", "--label", "Three approved test hosts",
            "--seed", URLS.get(0), "--seed", URLS.get(1), "--seed", URLS.get(2));
    }

    private JsonNode candidates(Path archive, String id) throws Exception {
        return success(archive, "research", "candidates", id);
    }

    private JsonNode planned(Path archive) throws Exception {
        String id = imported(archive).path("session_id").asText();
        success(archive, "research", "select", id, "--all-valid");
        return success(archive, "research", "plan", "--session", id, "--depth", "2",
            "--pages-per-seed", "5", "--global-pages", "15");
    }

    private static int selected(JsonNode document) {
        int count = 0;
        for (JsonNode candidate : document.path("candidates")) {
            if (candidate.path("selected").asBoolean()) count++;
        }
        return count;
    }

    @Test public void manualImportPersistsThreeDistinctCandidatesWithoutApprovalOrNetwork() throws Exception {
        Path archive = temporary.newFolder().toPath().resolve("archive");
        JsonNode created = imported(archive);
        String id = created.path("session_id").asText();
        UUID.fromString(id);
        assertEquals("manual", created.path("provider").asText());
        assertEquals("MANUAL_INPUT", created.path("discovery_state").asText());
        assertEquals(0, created.path("network_requests").asInt(-1));
        assertFalse(created.path("approved").asBoolean());
        assertFalse(created.path("planned").asBoolean());
        assertEquals(0, created.path("seeds").size());
        assertEquals(3, created.path("candidates").size());
        assertEquals(0, selected(created));
        Set<String> ids = new HashSet<>();
        Set<String> urls = new HashSet<>();
        for (JsonNode candidate : created.path("candidates")) {
            assertTrue(ids.add(candidate.path("candidate_id").asText()));
            UUID.fromString(candidate.path("candidate_id").asText());
            urls.add(candidate.path("canonical_url").asText());
            assertEquals("VALID", candidate.path("validation_status").asText());
            assertEquals(URI.create(candidate.path("canonical_url").asText()).getHost(), candidate.path("host").asText());
            assertEquals("USER_SUPPLIED", candidate.path("provenance").get(0).asText());
            assertTrue(candidate.path("title").isNull());
            assertTrue(candidate.path("snippet").isNull());
            assertFalse(candidate.path("discovered_at").asText().isBlank());
        }
        assertEquals(Set.copyOf(URLS), urls);
        JsonNode reopened = candidates(archive, id);
        assertEquals(created.path("candidates"), reopened.path("candidates"));
        assertEquals(created.path("created_at"), reopened.path("created_at"));
        assertEquals(0, selected(reopened));
    }

    @Test public void importNormalizesDuplicatesAndRejectsInvalidScopesWithoutFetching() throws Exception {
        Path archive = temporary.newFolder().toPath().resolve("archive");
        JsonNode result = success(archive, "research", "import", "--label", "literal manual URLs",
            "--seed", URLS.get(0) + "#fragment", "--seed", URLS.get(0),
            "--seed", URLS.get(0).replace(".onion/", ".onion:80/"));
        assertEquals(1, result.path("candidates").size());
        assertEquals(URLS.get(0), result.path("candidates").get(0).path("canonical_url").asText());
        for (String invalid : List.of("http://127.0.0.1/private", "https://example.org/",
                "http://user@" + OnionSmoke.HOST + "/", "http://" + "a".repeat(56) + ".onion/")) {
            assertNotEquals(0, run(archive, "research", "import", "--label", "invalid", "--seed", invalid).code);
        }
        assertEquals(1, candidates(archive, result.path("session_id").asText()).path("candidates").size());
    }

    @Test public void selectionSupportsAllIndividualExclusionsAndRejectsUnknownIdsAtomically() throws Exception {
        Path archive = temporary.newFolder().toPath().resolve("archive");
        JsonNode document = imported(archive);
        String id = document.path("session_id").asText();
        String first = document.path("candidates").get(0).path("candidate_id").asText();
        String second = document.path("candidates").get(1).path("candidate_id").asText();
        String third = document.path("candidates").get(2).path("candidate_id").asText();
        assertEquals(1, selected(success(archive, "research", "select", id, "--ids", first)));
        assertEquals(2, selected(success(archive, "research", "select", id, "--all-valid", "--exclude", third)));
        assertEquals(1, selected(success(archive, "research", "select", id, "--exclude", first)));
        assertEquals(3, selected(success(archive, "research", "select", id, "--ids", first + "," + third)));
        JsonNode before = candidates(archive, id);
        assertNotEquals(0, run(archive, "research", "select", id, "--ids", UUID.randomUUID().toString()).code);
        assertEquals(before.path("candidates"), candidates(archive, id).path("candidates"));
        assertNotEquals(0, run(archive, "research", "select", id, "--exclude", UUID.randomUUID().toString()).code);
        assertEquals(before.path("candidates"), candidates(archive, id).path("candidates"));
        assertTrue(before.path("candidates").findValuesAsText("candidate_id").contains(second));
    }

    @Test public void globalAndPerSeedBudgetsAreSeparateAndFailureDoesNotCreateApprovedScope() throws Exception {
        Path archive = temporary.newFolder().toPath().resolve("archive");
        String id = imported(archive).path("session_id").asText();
        assertNotEquals(0, run(archive, "research", "plan", "--session", id).code);
        success(archive, "research", "select", id, "--all-valid");
        assertNotEquals(0, run(archive, "research", "plan", "--session", id,
            "--depth", "2", "--pages-per-seed", "5", "--global-pages", "14").code);
        JsonNode refused = candidates(archive, id);
        assertFalse(refused.path("planned").asBoolean());
        assertFalse(refused.path("approved").asBoolean());
        assertEquals(0, refused.path("seeds").size());
        JsonNode plan = success(archive, "research", "plan", "--session", id,
            "--depth", "2", "--pages-per-seed", "5", "--global-pages", "15");
        assertEquals(15, plan.path("global_page_budget").asInt());
        assertEquals(5, plan.path("pages_per_seed").asInt());
        assertEquals(3, plan.path("seeds").size());
        assertFalse(plan.path("approved").asBoolean());
    }

    @Test public void plannedScopeIsFrozenReopensIdempotentlyAndApprovalPersistsEveryChild() throws Exception {
        Path archive = temporary.newFolder().toPath().resolve("archive");
        JsonNode plan = planned(archive);
        String id = plan.path("session_id").asText();
        assertTrue(plan.path("planned").asBoolean());
        assertEquals("READY", plan.path("planning_state").asText());
        Set<String> jobs = new HashSet<>();
        for (JsonNode seed : plan.path("seeds")) {
            String job = seed.path("job_id").asText();
            assertTrue(jobs.add(job));
            JsonNode child = success(archive, "crawl", "show", job);
            assertEquals(1, child.path("seeds").size());
            assertEquals(seed.path("url").asText(), child.path("seeds").get(0).asText());
            assertEquals("same-host", child.path("scope").asText());
            assertEquals("http://" + seed.path("host").asText(), child.path("origin").asText());
            assertEquals(2, child.path("depth").asInt());
            assertEquals(5, child.path("max_pages").asInt());
            assertEquals("m6:manual", child.path("provider").asText());
            assertFalse(child.path("approved").asBoolean());
            assertEquals("UNOBSERVED", child.path("admission").asText());
        }
        JsonNode same = success(archive, "research", "plan", "--session", id,
            "--depth", "2", "--pages-per-seed", "5", "--global-pages", "15");
        assertEquals(plan.path("seeds"), same.path("seeds"));
        assertNotEquals(0, run(archive, "research", "plan", "--session", id,
            "--depth", "1", "--pages-per-seed", "5", "--global-pages", "15").code);
        assertNotEquals(0, run(archive, "research", "select", id, "--all-valid").code);
        JsonNode approved = success(archive, "research", "approve", id);
        assertTrue(approved.path("approved").asBoolean());
        assertFalse(approved.path("approved_at").asText().isBlank());
        assertEquals(plan.path("seeds"), approved.path("seeds"));
        for (String job : jobs) assertTrue(success(archive, "crawl", "show", job).path("approved").asBoolean());
        assertEquals(approved.path("approved_at"), success(archive, "research", "approve", id).path("approved_at"));
        assertTrue(candidates(archive, id).path("approved").asBoolean());
    }

    @Test public void unapprovedRunRefusesBeforeRuntimeAndDoesNotChangeExecutionState() throws Exception {
        Path archive = temporary.newFolder().toPath().resolve("archive");
        String id = planned(archive).path("session_id").asText();
        Result refused = run(archive, "research", "run", "--session", id,
            "--runtime", "absent-runtime.json", "--admission-token-file", "absent-token");
        assertNotEquals(0, refused.code);
        assertTrue(refused.json.toString().toLowerCase().contains("approval"));
        JsonNode document = candidates(archive, id);
        assertFalse(document.path("approved").asBoolean());
        assertEquals("NOT_STARTED", document.path("run_state").asText());
        assertFalse(document.has("run_started_at"));
        for (JsonNode seed : document.path("seeds")) assertEquals("PENDING", seed.path("execution_state").asText());
        assertFalse(Files.exists(archive.resolve("crawl-worker.lock.owner-v1.json")));
    }

    @Test public void offlineStatusReportsUnknownMetricsAndNeverManufacturesCompletion() throws Exception {
        Path archive = temporary.newFolder().toPath().resolve("archive");
        String id = planned(archive).path("session_id").asText();
        JsonNode awaiting = success(archive, "research", "status", id);
        assertEquals("AWAITING_APPROVAL", awaiting.path("session_status").asText());
        success(archive, "research", "approve", id);
        for (String command : List.of("status", "results")) {
            JsonNode result = success(archive, "research", command, id);
            assertEquals("PENDING", result.path("session_status").asText());
            assertNotEquals("COMPLETED", result.path("session_status").asText());
            assertEquals(0, result.path("pages").size());
            assertEquals(0, result.path("links").size());
            assertEquals(0, result.path("file_candidates").size());
            JsonNode metrics = result.path("metrics");
            assertEquals(0, metrics.path("pages_archived").asInt(-1));
            assertEquals(3, metrics.path("seeds_selected").asInt());
            assertEquals(0, metrics.path("seeds_attempted").asInt(-1));
            for (String unknown : List.of("pages_fetched", "seeds_succeeded", "seeds_failed", "pending_urls",
                    "scheduled_retries", "terminal_failures", "completed")) assertTrue(unknown, metrics.path(unknown).isNull());
            for (JsonNode host : result.path("per_host")) assertFalse(host.path("status").path("authoritative_readback_current").asBoolean());
        }
        assertEquals(0, success(archive, "research", "pages", id).path("pages").size());
        assertEquals(0, success(archive, "research", "links", id).path("links").size());
        assertEquals(0, success(archive, "research", "files", id).path("file_candidates").size());
    }

    @Test public void sessionReadbackIncludesEveryLatestPageLinkBeyondLegacyJobLimit() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path archive = directory.resolve("archive");
        String session = success(archive, "research", "import", "--seed", URLS.get(0),
            "--label", "Manual link-readback regression").path("session_id").asText();
        success(archive, "research", "select", session, "--all-valid");
        JsonNode plan = success(archive, "research", "plan", "--session", session,
            "--depth", "2", "--pages-per-seed", "5", "--global-pages", "5");
        String job = plan.path("seeds").get(0).path("job_id").asText();
        success(archive, "research", "approve", session);
        Set<String> sourcePages = new HashSet<>();
        Set<String> expectedTargets = new HashSet<>();
        for (int page = 1; page <= 2; page++) {
            StringBuilder html = new StringBuilder("<html><title>Manual discussion page ")
                .append(page).append("</title><body>");
            for (int link = 0; link < 60; link++) {
                String path = "/resource-" + page + "-" + link;
                html.append("<a href='").append(path).append("'>observed link</a>");
                expectedTargets.add("http://" + OnionSmoke.HOST + path);
            }
            html.append("</body></html>");
            Path file = directory.resolve("page-" + page + ".html");
            Files.writeString(file, html);
            JsonNode imported = success(archive, "archive", "import", file.toString(),
                "--plan", job, "--url", "http://" + OnionSmoke.HOST + "/thread/page-" + page);
            assertEquals("MANUAL_IMPORT", imported.path("import_mode").asText());
            assertEquals(0, imported.path("network_requests").asInt(-1));
        }
        JsonNode result = success(archive, "research", "results", session);
        assertEquals(2, result.path("pages").size());
        for (JsonNode page : result.path("pages")) {
            sourcePages.add(page.path("page_id").asText());
            assertEquals("MANUAL_IMPORT", page.path("source_kind").asText());
        }
        assertEquals(2, sourcePages.size());
        // Два genuine MANUAL_IMPORT snapshot по 60 ссылок: это readback, не fake fetch success.
        assertEquals(120, result.path("links").size());
        assertEquals("LATEST_SNAPSHOTS", result.path("links_view").asText());
        assertEquals(100, result.path("per_page_link_limit").asInt());
        assertEquals(120, result.path("metrics").path("listed_link_observations").asInt());
        assertTrue(result.path("metrics").path("pages_fetched").isNull());
        Set<String> observedSources = new HashSet<>();
        Set<String> observedTargets = new HashSet<>();
        for (JsonNode link : result.path("links")) {
            observedSources.add(link.path("source_page_id").asText());
            observedTargets.add(link.path("target_url").asText());
            assertEquals(job, link.path("job_id").asText());
        }
        assertEquals(sourcePages, observedSources);
        assertEquals(expectedTargets, observedTargets);
        JsonNode links = success(archive, "research", "links", session);
        assertEquals(120, links.path("links").size());
        assertEquals(result.path("links"), links.path("links"));
    }
}
