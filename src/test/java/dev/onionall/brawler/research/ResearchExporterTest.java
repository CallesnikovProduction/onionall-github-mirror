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
            assertTrue(text.contains(SOURCE));
            assertTrue(text.contains(CLOCK));
            assertTrue(text.contains("UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS"));
            assertTrue(text.contains("no universal post parser"));
            assertFalse(text.contains("\u001b"));
            assertFalse(text.contains("\u009b"));
            assertFalse(text.contains("\u2028"));
            if (format.equals("markdown")) {
                assertFalse(text.contains("<script>"));
                assertTrue(text.contains("&lt;script&gt;"));
                assertTrue(text.contains("> # forged heading"));
            }
        }
    }

    @Test public void byteLimitIsEnforcedBeforeAnyOutputDirectoryIsCreated() throws Exception {
        Path output = temporary.newFolder().toPath().resolve("too-large");
        var page = page("я".repeat(ResearchExporter.MAX_EXPORT_BYTES / 2));
        assertThrows(IOException.class, () -> ResearchExporter.export(result(), List.of(page), "json", output));
        assertFalse(Files.exists(output));
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
