package dev.onionall.brawler.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.parse.ParseResult;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Проверяет text evidence после достижения Archive parser callback.</b>
 *
 * <p>Прямой callback fixture не квалифицирует upstream MIME routing или Tor.
 * Проверяет media/encoding validation и observable commit перед возвратом filter.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveTextParseCallbackTest {
    private static final String HOST =
        "aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion";
    private static final String URL = "http://" + HOST + "/";
    private static final byte[] TEXT = "# Unicode 🧅\n\nПротокол\n\n[Next](next.md)\n".getBytes(StandardCharsets.UTF_8);

    private static ArchiveParseFilter configured(Path root, String job) {
        Map<String, Object> conf = new HashMap<>();
        conf.put("archive.root", root.toString()); conf.put("archive.job.id", job);
        conf.put("protocol.md.prefix", "protocol."); conf.put("tor.mode", "ONLY");
        conf.put("tor.socks.host", "127.0.0.1"); conf.put("tor.socks.port", 1);
        conf.put("tor.allowed.hosts", List.of(HOST));
        conf.put("http.protocol.implementation", "dev.onionall.brawler.tor.TorProtocol");
        conf.put("https.protocol.implementation", "dev.onionall.brawler.tor.TorProtocol");
        var filter = new ArchiveParseFilter();
        filter.configure(conf, new ObjectMapper().createObjectNode(), "Archive-text-fixture");
        return filter;
    }

    private static String approved(Path root) throws Exception {
        try (var store = new ArchiveStore(root)) {
            String job = (String) store.createPlan(List.of(URL), 1, 3, "callback-fixture").get("plan_id");
            store.approvePlan(job);
            return job;
        }
    }

    private static ParseResult parse(String url, String media, String encoding) {
        var parse = new ParseResult(List.of());
        var md = new Metadata();
        md.setValue("fetch.statusCode", "200");
        if (media != null) { md.setValue("protocol.content-type", media); }
        if (encoding != null) { md.setValue("protocol.content-encoding", encoding); }
        parse.get(url).setMetadata(md);
        return parse;
    }

    @Test public void markdownAndPlainCallbacksCommitVerifiedSourceWithoutHtmlIdentity() throws Exception {
        Path root = Files.createTempDirectory("archive-text-callback");
        String job = approved(root);
        var filter = configured(root, job);
        for (String type : List.of("text/markdown; charset=UTF-8", "text/plain")) {
            String url = URL + (type.startsWith("text/markdown") ? "markdown.md" : "plain.md");
            var parse = parse(url, type, "identity");
            filter.filter(url, TEXT, null, parse);
            var md = parse.get(url).getMetadata();
            assertEquals("true", md.getFirstValue("archive.committed"));
            assertNull(md.getFirstValue("archive.page.id"));
            try (var store = new ArchiveStore(root)) {
                var document = store.researchDocument(md.getFirstValue("archive.document.id"));
                assertEquals(new String(TEXT, StandardCharsets.UTF_8), document.get("source_text"));
                assertEquals("CRAWLER", document.get("source_kind"));
                assertEquals(200, document.get("http_status"));
                assertNotNull(document.get("fetched_at"));
                assertEquals(type.startsWith("text/markdown") ? "MARKDOWN" : "PLAIN_TEXT", document.get("document_format"));
                assertEquals(document.get("document_format"), md.getFirstValue("archive.document.format"));
            }
        }
        try (var store = new ArchiveStore(root)) {
            assertEquals(2L, store.job(job).get("archived_documents"));
            assertEquals(0L, store.job(job).get("snapshots"));
        }
    }

    @Test public void unsupportedMimeEncodingCharsetAndUtf8NeverMarkCommitted() throws Exception {
        Path root = Files.createTempDirectory("archive-text-callback-invalid");
        String job = approved(root);
        var filter = configured(root, job);
        for (String type : new String[] {null, "application/octet-stream", "text/markdown;charset=windows-1251"}) {
            var parse = parse(URL, type, null);
            assertThrows(IllegalStateException.class, () -> filter.filter(URL, TEXT, null, parse));
            assertNull(parse.get(URL).getMetadata().getFirstValue("archive.committed"));
        }
        var compressed = parse(URL, "text/markdown", "gzip");
        assertThrows(IllegalStateException.class, () -> filter.filter(URL, TEXT, null, compressed));
        assertNull(compressed.get(URL).getMetadata().getFirstValue("archive.committed"));
        var invalidUtf8 = parse(URL, "text/plain", null);
        assertThrows(IllegalStateException.class, () -> filter.filter(URL, new byte[] {(byte) 0xc3, 0x28}, null, invalidUtf8));
        assertNull(invalidUtf8.get(URL).getMetadata().getFirstValue("archive.committed"));
        try (var store = new ArchiveStore(root)) {
            assertEquals(0L, store.job(job).get("archived_pages"));
            assertEquals(0L, store.job(job).get("document_versions"));
        }
    }
}
