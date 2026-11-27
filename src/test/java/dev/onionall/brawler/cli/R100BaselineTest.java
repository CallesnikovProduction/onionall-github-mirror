package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jsoup.nodes.Entities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/**
 * Measures a local CLI slice against independent fixtures. Captured product misses
 * belong in the benchmark result; invalid CLI responses fail the harness itself.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
public class R100BaselineTest {
    // Synthetic v3 address used only as archive metadata.
    private static final URI ORIGIN = URI.create(
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> UNIMPLEMENTED_DOWNLOAD_CASES =
        Set.of("H01", "H03", "H04", "H05", "H06", "H07", "H08", "H09", "H10");

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    public record Scenario(String scenarioId, String category, String objective, String acceptance) {}
    public record Inventory(int schemaVersion, List<Scenario> scenarios) {}
    public record Case(String scenarioId, String query, String researchIntent,
                       List<String> expectedTrajectory, Set<String> relevantDocumentIds) {}
    public record Document(String id, String title, String text) {}
    public record Variant(String id, String layout, List<Document> documents) {}
    public record Workloads(String workload, int maxPages, int resultLimit,
                            List<Case> cases, List<Variant> variants) {}
    private record Source(String documentId, String url, String contentHash) {}
    private record Metrics(int truePositives, int returned, int relevant,
                           Double precisionAmongReturned, Double recallWithinLimit) {}
    private record Run(String corpusId, String status, String query, String researchIntent,
                       List<String> expectedTrajectory, Boolean trajectoryMatch,
                       Set<String> expectedFindings, List<String> actualFindings,
                       List<Source> expectedEvidence, List<JsonNode> actualEvidence,
                       Set<String> distractorDocumentIds, int maxPages, int resultLimit,
                       Metrics relevanceMetrics, long latencyNanos, List<String> failureReasons) {}
    private record Verdict(String status, String reason, List<Run> runs) {}

    @Test(timeout = 30_000)
    public void runLocalArchiveSearchBaseline() throws Exception {
        var inventory = read("scenarios.json", Inventory.class);
        var workloads = read("local-search.json", Workloads.class);
        Set<String> requiredIds = new TreeSet<>();
        for (char group = 'A'; group <= 'J'; group++) {
            for (int slot = 1; slot <= 10; slot++) {
                requiredIds.add(group + (slot == 10 ? "10" : "0" + slot));
            }
        }
        assertEquals(1, inventory.schemaVersion());
        assertEquals(100, inventory.scenarios().size());
        Set<String> inventoryIds = new HashSet<>();
        for (var scenario : inventory.scenarios()) {
            assertTrue(inventoryIds.add(scenario.scenarioId()));
            assertFalse(scenario.category().isBlank());
            assertFalse(scenario.objective().isBlank());
            assertFalse(scenario.acceptance().isBlank());
        }
        assertEquals(requiredIds, inventoryIds);
        assertEquals("LOCAL_ARCHIVE_SEARCH", workloads.workload());
        assertTrue(workloads.maxPages() > 0 && workloads.maxPages() <= 20);
        assertTrue(workloads.resultLimit() >= workloads.maxPages() && workloads.resultLimit() <= 100);
        assertEquals(Set.of("development", "evaluation"),
            new HashSet<>(workloads.variants().stream().map(Variant::id).toList()));
        assertEquals(2, workloads.variants().size());
        assertFalse(workloads.cases().isEmpty());

        Map<String, Verdict> executed = new LinkedHashMap<>();
        for (var scenario : workloads.cases()) {
            assertTrue(requiredIds.contains(scenario.scenarioId()));
            assertFalse(executed.containsKey(scenario.scenarioId()));
            List<Run> runs = new ArrayList<>();
            for (var variant : workloads.variants()) {
                runs.add(evaluate(workloads, scenario, variant));
            }
            boolean failed = runs.stream().anyMatch(run -> "FAIL".equals(run.status()));
            executed.put(scenario.scenarioId(), new Verdict(
                failed ? "FAIL" : "PARTIAL",
                failed ? "At least one corpus variant missed a retrieval assertion."
                    : "Local search passed; the full research trajectory was not exercised.",
                runs));
        }

        var report = JSON.createObjectNode();
        report.put("schemaVersion", 1);
        report.put("workload", workloads.workload());
        report.put("effectiveConcurrency", 1);
        var reportScenarios = report.putArray("scenarios");
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (var scenario : inventory.scenarios()) {
            Verdict verdict = executed.get(scenario.scenarioId());
            if (verdict == null) {
                boolean missingDownload = UNIMPLEMENTED_DOWNLOAD_CASES.contains(scenario.scenarioId());
                verdict = missingDownload
                    ? new Verdict("NOT IMPLEMENTED", "The research approval and download workflow is unavailable.", List.of())
                    : new Verdict("NOT RUN", "No executable workload for this scenario in Iteration 0.", List.of());
            }
            var row = reportScenarios.addObject();
            row.put("scenarioId", scenario.scenarioId());
            row.put("category", scenario.category());
            row.put("status", verdict.status());
            row.put("reason", verdict.reason());
            row.set("runs", JSON.valueToTree(verdict.runs()));
            totals.merge(verdict.status(), 1, Integer::sum);
        }
        report.set("totals", JSON.valueToTree(totals));
        String output = System.getProperty("onionall.r100.report");
        if (output != null) {
            Files.writeString(Path.of(output), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        System.out.println("R100 Iteration 0: " + totals);
    }

    private Run evaluate(Workloads workload, Case scenario, Variant variant) throws Exception {
        assertTrue(variant.documents().size() <= workload.maxPages());
        assertTrue(variant.documents().size() >= 2);
        assertTrue(Set.of("article", "section").contains(variant.layout()));
        Path work = temporary.newFolder().toPath();
        Path archive = work.resolve("archive");
        URI base = ORIGIN.resolve(variant.id() + "/");
        String plan = cli(archive, "research", "plan", "--url", base.toString(),
            "--max-pages", Integer.toString(workload.maxPages())).required("plan_id").asText();
        cli(archive, "crawl", "approve", plan);

        Map<String, Source> sources = new LinkedHashMap<>();
        for (var doc : variant.documents()) {
            assertTrue(doc.id().matches("[a-z0-9-]+"));
            String url = base.resolve(doc.id()).toString();
            Path input = work.resolve(doc.id() + ".html");
            String tag = variant.layout();
            Files.writeString(input, "<!doctype html><html><head><title>" + Entities.escape(doc.title())
                + "</title></head><body><" + tag + "><p>" + Entities.escape(doc.text()) + "</p></"
                + tag + "></body></html>", StandardOpenOption.CREATE_NEW);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(input)));
            assertNull(sources.put(url, new Source(doc.id(), url, hash)));
            assertEquals("MANUAL_IMPORT", cli(archive, "archive", "import", input.toString(),
                "--plan", plan, "--url", url).required("import_mode").asText());
        }

        List<Source> expected = sources.values().stream()
            .filter(source -> scenario.relevantDocumentIds().contains(source.documentId())).toList();
        assertEquals(scenario.relevantDocumentIds().size(), expected.size());
        Set<String> distractors = new TreeSet<>();
        for (var source : sources.values()) {
            if (!scenario.relevantDocumentIds().contains(source.documentId())) {
                distractors.add(source.documentId());
            }
        }
        assertFalse(distractors.isEmpty());

        // Check the catalog first so an empty result cannot pass against an empty fixture.
        var listed = cli(archive, "research", "results", plan).required("pages");
        assertTrue(listed.isArray());
        Map<String, String> archivedSnapshots = new LinkedHashMap<>();
        for (var page : listed) {
            Source source = provenance(page, plan, sources);
            assertNull("Duplicate archive row for " + source.documentId(),
                archivedSnapshots.put(source.url(), snapshotId(page)));
        }
        assertEquals(sources.keySet(), archivedSnapshots.keySet());

        long start = System.nanoTime();
        var result = cli(archive, "research", "search", "--query", scenario.query(),
            "--job", plan, "--host", ORIGIN.getHost(), "--limit", Integer.toString(workload.resultLimit()));
        long latency = System.nanoTime() - start;
        assertEquals("local-archive", result.required("provider").asText());
        var rows = result.required("results");
        assertTrue(rows.isArray());
        assertTrue(rows.size() <= workload.resultLimit());

        List<String> returned = new ArrayList<>();
        List<JsonNode> evidence = new ArrayList<>();
        Set<String> found = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (JsonNode item : rows) {
            Source source = provenance(item, plan, sources);
            String snapshot = snapshotId(item);
            assertEquals(archivedSnapshots.get(source.url()), snapshot);
            if (!found.add(source.documentId())) {
                failures.add("Duplicate result: " + source.documentId());
            }
            returned.add(source.documentId());
            var observation = JSON.createObjectNode();
            observation.put("documentId", source.documentId());
            observation.put("sourceUrl", source.url());
            observation.put("contentHash", source.contentHash());
            observation.put("snapshotId", snapshot);
            observation.set("sourceKind", item.required("source_kind"));
            evidence.add(observation);
        }
        Set<String> missing = new TreeSet<>(scenario.relevantDocumentIds());
        missing.removeAll(found);
        if (!missing.isEmpty()) {
            failures.add("Missing expected documents: " + missing);
        }
        Set<String> unwanted = new TreeSet<>(found);
        unwanted.removeAll(scenario.relevantDocumentIds());
        if (!unwanted.isEmpty()) {
            failures.add("Returned distractors: " + unwanted);
        }
        int hits = expected.size() - missing.size();
        return new Run(variant.id(), failures.isEmpty() ? "PARTIAL" : "FAIL", scenario.query(),
            scenario.researchIntent(), scenario.expectedTrajectory(), null,
            scenario.relevantDocumentIds(), returned, expected, evidence, distractors, workload.maxPages(),
            workload.resultLimit(), new Metrics(hits, returned.size(), expected.size(),
                ratio(hits, returned.size()), ratio(hits, expected.size())), latency, failures);
    }

    private static Source provenance(JsonNode item, String plan, Map<String, Source> sources) {
        var source = sources.get(item.required("canonical_url").asText());
        assertNotNull("Result must belong to the owned fixture", source);
        assertEquals(plan, item.required("job_id").asText());
        assertEquals("MANUAL_IMPORT", item.required("source_kind").asText());
        assertEquals(source.contentHash(), item.required("content_hash").asText());
        return source;
    }

    private static String snapshotId(JsonNode item) {
        var id = item.required("snapshot_id");
        assertTrue(id.isTextual());
        assertFalse(id.asText().isBlank());
        return id.asText();
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static <T> T read(String filename, Class<T> type) throws IOException {
        try (var input = R100BaselineTest.class.getResourceAsStream("/research/r100/" + filename)) {
            return JSON.readValue(Objects.requireNonNull(input, filename), type);
        }
    }

    private static JsonNode cli(Path archive, String... command) throws IOException {
        var args = new ArrayList<>(List.of("--json", "--archive-dir", archive.toString()));
        args.addAll(List.of(command));
        var out = new StringWriter();
        var err = new StringWriter();
        int exit = Onionall.execute(args.toArray(String[]::new), new PrintWriter(out, true),
            new PrintWriter(err, true), false, Map.of(),
            (config, url) -> { throw new AssertionError("The baseline must not fetch remote content"); });
        assertEquals("CLI failed: " + String.join(" ", command) + "\n" + err, 0, exit);
        assertEquals("", err.toString());
        return JSON.readTree(out.toString());
    }
}
