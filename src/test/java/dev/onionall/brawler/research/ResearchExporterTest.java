package dev.onionall.brawler.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tools.OnionSmoke;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;

/**
 * Локальные export-файлы: source boundaries, форматы, byte limit и no-overwrite.
 * Fixture-текст не выдаётся за результат реального provider/crawler.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
public class ResearchExporterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final ObjectMapper json = new ObjectMapper();
    private static final String SOURCE = "http://" + OnionSmoke.HOST + "/thread/page-1";
    private static final String CLOCK = "2026-11-11T12:00:00Z";

    private Map<String, Object> result() {
        var value = new LinkedHashMap<String, Object>();
        value.put("session_id", UUID.randomUUID().toString());
        value.put("session", Map.of("query", "test discussion", "provider", "fixture", "candidates", List.of(Map.of("selected", true))));
        value.put("links", List.of(Map.of("source_url", SOURCE, "target_url", SOURCE + "?page=2")));
        value.put("file_candidates", List.of(Map.of("status", "CANDIDATE", "target_url", SOURCE + ".pdf")));
        value.put("errors", List.of(Map.of("category", "FETCH_ERROR")));
        return value;
    }

    private Map<String, Object> page(String text) {
        var value = new LinkedHashMap<String, Object>();
        value.put("page_id", UUID.randomUUID().toString());
        value.put("job_id", UUID.randomUUID().toString());
        value.put("canonical_url", SOURCE);
        value.put("fetched_at", CLOCK);
        value.put("recorded_at", CLOCK);
        value.put("source_kind", "CRAWLER");
        value.put("title", "Captured discussion");
        value.put("text", text);
        value.put("links", List.of(Map.of("target_url", SOURCE + "?page=2")));
        return value;
    }

    @Test public void jsonAndJsonlPreserveActualTextSourceAndResearchMetadata() throws Exception {
        Path root = temporary.newFolder().toPath();
        var research = result();
        var page = page("First line\nSecond line: discussion excerpt");
        for (String format : List.of("json", "jsonl")) {
            var result = ResearchExporter.export(research, List.of(page), format, root.resolve(format));
            Path output = Path.of((String) result.get("output_file"));
            byte[] bytes = Files.readAllBytes(output);
            assertEquals((long) bytes.length, ((Number) result.get("bytes")).longValue());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), result.get("sha256"));
            JsonNode document;
            if (format.equals("json")) {
                var full = json.readTree(bytes);
                assertEquals("test discussion", full.path("research").path("session").path("query").asText());
                assertEquals(1, full.path("research").path("file_candidates").size());
                assertEquals(1, full.path("research").path("errors").size());
                document = full.path("pages").get(0);
            } else {
                var lines = Files.readAllLines(output);
                assertEquals(2, lines.size());
                assertEquals("research", json.readTree(lines.get(0)).path("record_type").asText());
                document = json.readTree(lines.get(1));
                assertEquals("page", document.path("record_type").asText());
            }
            assertEquals(research.get("session_id"), document.path("session_id").asText());
            assertEquals(page.get("job_id"), document.path("job_id").asText());
            assertEquals(SOURCE, document.path("source_url").asText());
            assertEquals(CLOCK, document.path("fetched_at").asText());
            assertEquals(page.get("text"), document.path("page").path("text").asText());
            assertEquals("PAGE_LEVEL_TEXT_ONLY", document.path("discussion_structure").asText());
            assertEquals(0, ((Number) result.get("network_requests")).intValue());
        }
    }

    @Test public void readableFormatsKeepPageBoundariesAndDoNotRenderRemoteHtmlOrControlCodes() throws Exception {
        Path root = temporary.newFolder().toPath();
        var research = result();
        var page = page("<script>alert(1)</script>\n# forged heading\n```\n\u001b[31mremote text\u009bcontrol\u2028separator");
        var second = page("Second page discussion");
        second.put("canonical_url", SOURCE + "?page=2");
        for (String format : List.of("markdown", "text")) {
            var reply = ResearchExporter.export(research, List.of(page, second), format, root.resolve(format));
            String text = Files.readString(Path.of((String) reply.get("output_file")));
            assertTrue(text.contains("PAGE 1"));
            assertTrue(text.contains("PAGE 2"));
            String expectedSource = SOURCE;
            if (format.equals("markdown")) expectedSource = SOURCE.replace(":", "\\:").replace("/", "\\/").replace(".", "\\.").replace("-", "\\-");
            assertTrue(text.contains("Source URL: " + expectedSource));
            assertTrue(text.contains(format.equals("markdown") ? CLOCK.replace("-", "\\-").replace(":", "\\:") : CLOCK));
            assertTrue(text.contains("UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS"));
            assertTrue(text.contains("no universal post parser"));
            assertFalse(text.contains("\u001b"));
            assertFalse(text.contains("\u009b"));
            assertFalse(text.contains("\u2028"));
            if (format.equals("markdown")) {
                assertFalse(text.contains("<script>"));
                assertTrue(text.contains("&lt;script&gt;"));
                assertTrue(text.contains("> \\# forged heading"));
            }
        }
    }

    /** Golden fixtures — синтетические записи; не сетевое и не archive E2E доказательство. */
    @Test public void readableExportsMatchReviewedStableGoldenFilesWithoutMetadataDumps() throws Exception {
        Path root = temporary.newFolder().toPath();
        for (String fixture : List.of("structured", "page-text")) {
            Map<String, Object> input = fixture(fixture);
            @SuppressWarnings("unchecked")
            var research = (Map<String, Object>) input.get("result");
            @SuppressWarnings("unchecked")
            var details = (List<Map<String, Object>>) input.get("details");
            for (String format : List.of("markdown", "text")) {
                var receipt = ResearchExporter.export(research, details, format, root.resolve(fixture + "-" + format));
                String actual = Files.readString(Path.of((String) receipt.get("output_file")));
                String suffix = format.equals("markdown") ? "md" : "txt";
                try (var expected = getClass().getResourceAsStream("/research/export/" + fixture + "." + suffix)) {
                    assertNotNull("Missing reviewed golden fixture", expected);
                    assertEquals(fixture + " " + format, new String(expected.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), actual);
                }
                assertFalse(actual.contains("RESEARCH METADATA"));
                assertFalse(actual.contains("Page metadata:"));
                assertFalse(actual.contains("MACHINE_ONLY_"));
                assertTrue(actual.contains("Research summary"));
                assertTrue(actual.contains("Sources"));
                assertTrue(actual.contains("Discussions"));
                assertTrue(actual.contains("File candidates"));
                assertTrue(actual.contains("Limitations"));
                assertTrue(actual.contains("Fetch success count: UNKNOWN"));
                assertTrue(actual.contains("Completed: UNKNOWN"));
                assertEquals(0, ((Number) receipt.get("network_requests")).intValue());
                if (fixture.equals("structured")) {
                    assertTrue(actual.contains("MESSAGE 1"));
                    assertTrue(actual.contains("Author: UNKNOWN"));
                    assertTrue(actual.contains("Posted at: UNKNOWN"));
                    assertTrue(actual.contains("Quoted text 1"));
                    assertFalse(actual.contains("Full page text includes navigation"));
                    assertEquals("STRUCTURED_POSTS_WITH_PAGE_TEXT_FALLBACK", receipt.get("discussion_structure"));
                } else {
                    assertFalse(actual.contains("MESSAGE 1"));
                    assertTrue(actual.contains("PAGE 1"));
                    assertTrue(actual.contains("not invented posts"));
                    assertTrue(actual.contains("Fetched at: UNKNOWN"));
                    assertTrue(actual.contains("MANUAL"));
                    assertEquals("PAGE_LEVEL_TEXT_ONLY", receipt.get("discussion_structure"));
                }
                if (format.equals("markdown")) {
                    assertTrue(actual.contains("\n- Query: "));
                    assertTrue(actual.contains("\n\n> "));
                    assertTrue(actual.contains("\n\n| URL |"));
                    assertFalse(actual.contains("!["));
                    assertFalse(actual.contains("<script>"));
                    assertFalse(actual.contains("<div>"));
                    assertFalse(actual.contains("\n# forged"));
                }
            }
        }
    }

    @Test @SuppressWarnings("unchecked") public void deduplicatedMessagesRetainAdditionalPageProvenanceWithoutRepeatingWholePageText() throws Exception {
        Map<String, Object> input = fixture("structured");
        var research = (Map<String, Object>) input.get("result");
        var details = (List<Map<String, Object>>) input.get("details");
        var second = new LinkedHashMap<>(details.getFirst());
        second.put("page_id", "00000000-0000-4000-8000-000000000009");
        second.put("snapshot_id", "00000000-0000-4000-8000-000000000010");
        second.put("canonical_url", SOURCE + "?page=2");
        second.put("text", "SECOND_PAGE_NAVIGATION_SHOULD_NOT_DUPLICATE_MESSAGES");
        details.add(second);
        var thread = ((List<Map<String, Object>>) research.get("threads")).getFirst();
        ((List<Map<String, Object>>) thread.get("captured_pages")).add(Map.of(
            "page_id", second.get("page_id"), "snapshot_id", second.get("snapshot_id"), "url", second.get("canonical_url"),
            "extraction_status", "STRUCTURED_POSTS", "page_text_truncated", false));
        var post = ((List<Map<String, Object>>) thread.get("posts")).getFirst();
        post.put("provenance", List.of(Map.of("source_page_id", second.get("page_id"),
            "snapshot_id", second.get("snapshot_id"), "source_page_url", second.get("canonical_url"))));
        var receipt = ResearchExporter.export(research, details, "text", temporary.newFolder().toPath().resolve("deduplicated"));
        String text = Files.readString(Path.of((String) receipt.get("output_file")));
        assertTrue(text.contains("Additional source page"));
        assertTrue(text.contains((String) second.get("page_id")));
        assertTrue(text.contains("MESSAGE 1"));
        assertFalse(text.contains("SECOND_PAGE_NAVIGATION_SHOULD_NOT_DUPLICATE_MESSAGES"));
    }

    @Test @SuppressWarnings("unchecked") public void partialDynamicAndTruncatedPagesKeepFallbackAndScopeRejectionsAreVisible() throws Exception {
        for (String scenario : List.of("PARTIAL", "UNSUPPORTED_DYNAMIC_CONTENT", "TRUNCATED_POST", "TRUNCATED_PAGE")) {
            var input = fixture("structured");
            var research = (Map<String, Object>) input.get("result");
            var details = (List<Map<String, Object>>) input.get("details");
            details.getFirst().put("text", "FALLBACK_REQUIRED_" + scenario);
            var thread = ((List<Map<String, Object>>) research.get("threads")).getFirst();
            var capture = ((List<Map<String, Object>>) thread.get("captured_pages")).getFirst();
            if (scenario.equals("TRUNCATED_POST")) {
                var post = ((List<Map<String, Object>>) thread.get("posts")).getFirst();
                ((Map<String, Object>) post.get("evidence")).put("text_truncated", true);
            } else if (scenario.equals("TRUNCATED_PAGE")) capture.put("page_text_truncated", true);
            else capture.put("extraction_status", scenario);
            research.put("discussion_scope_rejected_pages", List.of(Map.of("source_page_id", "rejected-id", "url", SOURCE + "?t=99", "reason", "UNRELATED_THREAD_ID")));
            var receipt = ResearchExporter.export(research, details, "text", temporary.newFolder().toPath().resolve(scenario));
            String text = Files.readString(Path.of((String) receipt.get("output_file")));
            assertTrue(scenario, text.contains("FALLBACK_REQUIRED_" + scenario));
            assertTrue(text.contains("MESSAGE 1"));
            assertTrue(text.contains("Discussion scope rejected pages: 1"));
            assertTrue(text.contains("UNRELATED_THREAD_ID"));
        }
    }

    @Test public void structuredProvenanceAndRawRemoteTextRemainMachineReadable() throws Exception {
        Map<String, Object> input = fixture("structured");
        @SuppressWarnings("unchecked") var research = (Map<String, Object>) input.get("result");
        @SuppressWarnings("unchecked") var details = (List<Map<String, Object>>) input.get("details");
        for (String format : List.of("json", "jsonl")) {
            var receipt = ResearchExporter.export(research, details, format, temporary.newFolder().toPath().resolve(format));
            String content = Files.readString(Path.of((String) receipt.get("output_file")));
            JsonNode envelope = json.readTree(format.equals("json") ? content : content.lines().findFirst().orElseThrow());
            assertEquals(json.valueToTree(research).path("threads"), envelope.path("research").path("threads"));
            assertEquals("MACHINE_ONLY_SESSION_METADATA", envelope.path("research").path("session").path("private_fixture_field").asText());
            assertTrue(envelope.path("research").path("threads").get(0).path("posts").get(0).path("text").asText().contains("![probe]("));
            JsonNode archived = format.equals("json") ? envelope.path("pages").get(0) : json.readTree(content.lines().skip(1).findFirst().orElseThrow());
            assertEquals(json.valueToTree(details.get(0)), archived.path("page"));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/research/export/" + name + ".fixture.json")) {
            assertNotNull(input);
            return json.readValue(input, LinkedHashMap.class);
        }
    }

    @Test public void readableFallbackPrefersVerifiedReadableTextAndMachinePreservesOriginalPageText() throws Exception {
        var page = page("NAVIGATION_SENTINEL Original archived text");
        page.put("readable_text", "Visible main content only");
        Path root = temporary.newFolder().toPath();
        for (String format : List.of("markdown", "text", "json")) {
            var receipt = ResearchExporter.export(result(), List.of(page), format, root.resolve(format));
            String output = Files.readString(Path.of((String) receipt.get("output_file")));
            if (format.equals("json")) {
                assertEquals(page.get("text"), json.readTree(output).path("pages").get(0).path("page").path("text").asText());
            } else {
                assertTrue(output.contains("Visible main content only"));
                assertFalse(output.contains("NAVIGATION_SENTINEL"));
            }
        }
    }

    @Test public void byteLimitIsEnforcedBeforeAnyOutputDirectoryIsCreated() throws Exception {
        Path output = temporary.newFolder().toPath().resolve("too-large");
        var page = page("я".repeat(ResearchExporter.MAX_EXPORT_BYTES / 2));
        assertThrows(IOException.class, () -> ResearchExporter.export(result(), List.of(page), "json", output));
        assertFalse(Files.exists(output));
    }

    @Test public void markdownKeepsRemoteImagesAndLinksAsLiteralText() throws Exception {
        var page = page("![probe](https://example.invalid/pixel)\n[remote](https://example.invalid/)\n```html\n<img src=x>\n```");
        page.put("title", "![title](https://example.invalid/title)");
        var reply = ResearchExporter.export(result(), List.of(page), "markdown",
            temporary.newFolder().toPath().resolve("literal"));
        String output = Files.readString(Path.of((String) reply.get("output_file")));
        assertFalse(output.contains("![probe]("));
        assertFalse(output.contains("![title]("));
        assertFalse(output.contains("[remote]("));
        assertFalse(output.contains("<img"));
        assertTrue(output.contains("\\!\\[probe\\]\\("));
        assertTrue(output.contains("\\!\\[title\\]\\("));
        assertTrue(output.contains("\\[remote\\]\\("));
        assertTrue(output.contains("> \\`\\`\\`html"));
        assertEquals(0, ((Number) reply.get("network_requests")).intValue());
    }

    @Test public void existingContentsAndSymlinkOutputsAreNeverOverwritten() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path existing = Files.createDirectory(root.resolve("existing"));
        Path owner = existing.resolve("keep.txt");
        Files.writeString(owner, "owner work");
        assertThrows(IOException.class, () -> ResearchExporter.export(result(), List.of(page("sample")), "json", existing));
        assertEquals("owner work", Files.readString(owner));
        Path link = root.resolve("output-link");
        try { Files.createSymbolicLink(link, existing); }
        catch (UnsupportedOperationException | IOException error) { assumeNoException(error); }
        assertThrows(IOException.class, () -> ResearchExporter.export(result(), List.of(page("sample")), "text", link));
        try (var contents = Files.list(existing)) { assertEquals(1, contents.count()); }
    }

    @Test public void manualImportRetainsUnknownFetchTimestamp() throws Exception {
        var page = page("Offline captured text");
        page.put("source_kind", "MANUAL_IMPORT");
        page.put("fetched_at", null);
        var reply = ResearchExporter.export(result(), List.of(page), "json", temporary.newFolder().toPath().resolve("manual"));
        var body = json.readTree(Files.readAllBytes(Path.of((String) reply.get("output_file"))));
        assertTrue(body.path("pages").get(0).path("fetched_at").isNull());
        assertEquals(CLOCK, body.path("pages").get(0).path("recorded_at").asText());
        assertEquals("MANUAL_IMPORT", body.path("pages").get(0).path("page").path("source_kind").asText());
    }
}
