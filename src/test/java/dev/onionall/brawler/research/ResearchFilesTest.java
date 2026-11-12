package dev.onionall.brawler.research;

import dev.onionall.brawler.tools.OnionSmoke;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * File candidates из настоящих bounded HTML-файлов с проверенными SHA256.
 * Тестовые адреса не запрашиваются, MIME и размеры удалённых файлов не выдумываются.
 *
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
public class ResearchFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String HOST = OnionSmoke.HOST;
    private static final String OTHER = "aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion";
    private static final String SOURCE = "http://" + HOST + "/thread/page.html";
    private static final String CLOCK = "2026-11-11T12:00:00Z";
    private record Fixture(Path root, Path object, String session, Map<String, Object> page) { }

    private Fixture fixture(String html) throws Exception {
        Path root = temporary.newFolder().toPath().resolve("archive");
        Files.createDirectories(root.resolve("objects"));
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path file = root.resolve("objects").resolve(hash + ".html");
        Files.write(file, bytes);
        var page = new LinkedHashMap<String, Object>();
        page.put("page_id", UUID.randomUUID().toString());
        page.put("job_id", UUID.randomUUID().toString());
        page.put("snapshot_id", UUID.randomUUID().toString());
        page.put("canonical_url", SOURCE);
        page.put("content_hash", hash);
        page.put("content_size", bytes.length);
        page.put("storage_location", file.toString());
        page.put("source_kind", "CRAWLER");
        page.put("fetched_at", CLOCK);
        page.put("recorded_at", CLOCK);
        page.put("links", List.of());
        return new Fixture(root, file, UUID.randomUUID().toString(), page);
    }

    private List<Map<String, Object>> extract(Fixture fixture) throws IOException {
        return ResearchFiles.extract(fixture.session, List.of(fixture.page), id -> fixture.page, fixture.root);
    }

    @Test public void observedHintsAreDeduplicatedWithoutInventingFileResponseMetadata() throws Exception {
        var fixture = fixture("<html><body>" +
            "<a href='../docs/Guide.pdf?download=1#one' type='application/pdf' download='Guide.pdf'>PDF</a>" +
            "<a href='/docs/Guide.pdf?download=1#two'>duplicate PDF</a>" +
            "<a href='/download?id=2' type='application/epub+zip'>EPUB hint</a>" +
            "<a href='/download?id=3' download='notes.docx'>DOCX hint</a>" +
            "<a href='/readme.TXT'>text</a>" +
            "<a href='http://" + OTHER + "/foreign.pdf'>outside</a>" +
            "<a href='/program.exe'>executable</a><a href='/dump.zip'>archive</a>" +
            "<a href='javascript:alert(1)' type='application/pdf'>invalid</a>" +
            "<a href='http://127.0.0.1/private.pdf'>internal</a>" +
            "<a href='http://user:password@" + HOST + "/private.pdf'>userinfo</a>" +
            "</body></html>");
        var files = extract(fixture);
        assertEquals(files.stream().map(row -> row.get("target_url")).toList().toString(), 5, files.size());
        var pdf = files.stream().filter(row -> "pdf".equals(row.get("extension")) && Boolean.TRUE.equals(row.get("approved_host"))).findFirst().orElseThrow();
        assertEquals("http://" + HOST + "/docs/Guide.pdf?download=1", pdf.get("target_url"));
        assertEquals("Guide.pdf", pdf.get("original_filename"));
        assertEquals("application/pdf", pdf.get("declared_content_type"));
        assertNull(pdf.get("content_type"));
        assertNull(pdf.get("content_length"));
        assertEquals(false, pdf.get("content_metadata_verified"));
        assertEquals(false, pdf.get("downloaded"));
        assertEquals("CANDIDATE", pdf.get("status"));
        assertEquals(2, ((List<?>) pdf.get("provenance")).size());
        assertEquals(CLOCK, pdf.get("discovered_at"));
        assertEquals(fixture.page.get("page_id"), pdf.get("source_page_id"));
        assertEquals(fixture.page.get("job_id"), pdf.get("job_id"));
        assertEquals(fixture.session, pdf.get("session_id"));
        String identity = fixture.session + "|" + fixture.page.get("job_id") + "|" +
            fixture.page.get("page_id") + "|" + pdf.get("target_url");
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(identity.getBytes(StandardCharsets.UTF_8))), pdf.get("file_id"));
        assertEquals(pdf.get("file_id"), extract(fixture).getFirst().get("file_id"));
        var foreign = files.stream().filter(row -> Boolean.FALSE.equals(row.get("approved_host"))).findFirst().orElseThrow();
        assertEquals("OUT_OF_SCOPE", foreign.get("scope_status"));
        assertEquals("OUT_OF_SCOPE", foreign.get("status"));
        var epub = files.stream().filter(row -> "application/epub+zip".equals(row.get("declared_content_type"))).findFirst().orElseThrow();
        assertNull(epub.get("extension"));
        assertNull(epub.get("content_type"));
    }

    @Test public void manualImportPreservesRecordedClockAndDoesNotClaimActualFetch() throws Exception {
        var fixture = fixture("<html><a href='/book.md'>notes</a></html>");
        fixture.page.put("fetched_at", null);
        fixture.page.put("source_kind", "MANUAL_IMPORT");
        var file = extract(fixture).getFirst();
        assertEquals("MANUAL_IMPORT", file.get("source_kind"));
        assertEquals("SNAPSHOT_RECORDED_AT", file.get("discovery_time_basis"));
        assertEquals(CLOCK, file.get("discovered_at"));
    }

    @Test public void actualHtmlBaseIsObservedAndForeignOriginDoesNotBecomeApproved() throws Exception {
        var fixture = fixture("<html><head><base href='http://" + OTHER + "/documents/'></head>" +
            "<body><a href='guide.pdf'>guide</a></body></html>");
        var file = extract(fixture).getFirst();
        assertEquals("http://" + OTHER + "/documents/guide.pdf", file.get("target_url"));
        assertEquals(false, file.get("approved_host"));
        assertEquals("OUT_OF_SCOPE", file.get("status"));
    }

    @Test public void userinfoInOriginalHrefOrHtmlBaseCannotBeLostDuringResolution() throws Exception {
        var fixture = fixture("<html><head><base href='http://user:password@" + HOST + "/docs/'></head>" +
            "<body><a href='relative.pdf'>relative through unsafe base</a>" +
            "<a href='//user:password@" + HOST + "/private.pdf'>unsafe authority</a>" +
            "<a href='http://" + HOST + "/public.pdf'>safe absolute link</a></body></html>");
        var files = extract(fixture);
        assertEquals(1, files.size());
        assertEquals("http://" + HOST + "/public.pdf", files.getFirst().get("target_url"));
    }

    @Test public void tamperingAndUnexpectedObjectPathsAreRejectedBeforeExtraction() throws Exception {
        var fixture = fixture("<html><a href='/book.pdf'>book</a></html>");
        String original = Files.readString(fixture.object);
        Files.writeString(fixture.object, original.replace("book</a>", "baok</a>"));
        assertThrows(IOException.class, () -> extract(fixture));
        Files.writeString(fixture.object, original);
        Path outside = temporary.newFile().toPath();
        Files.writeString(outside, original);
        fixture.page.put("storage_location", outside.toString());
        assertThrows(IOException.class, () -> extract(fixture));
    }

    @Test public void symlinkObjectsAreRejectedEvenWhenTheyContainCorrectHtml() throws Exception {
        var fixture = fixture("<html><a href='/book.pdf'>book</a></html>");
        Path real = fixture.root.resolve("retained.html");
        Files.move(fixture.object, real);
        try { Files.createSymbolicLink(fixture.object, real); }
        catch (UnsupportedOperationException | IOException error) { assumeNoException(error); }
        assertThrows(IOException.class, () -> extract(fixture));
    }
}
